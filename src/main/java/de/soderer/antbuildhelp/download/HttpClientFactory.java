package de.soderer.antbuildhelp.download;

import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

import de.soderer.antbuildhelp.config.ProxyConfig;
import de.soderer.pac.utilities.ProxyConfiguration;
import de.soderer.pac.utilities.ProxyConfiguration.ProxyConfigurationType;

/**
 * Creates the HttpClients for all downloads of AntBuildHelp (getDependency, getMavenDependency,
 * resolveDependencies and the CLI), so proxy and TLS trust settings are handled identically
 * everywhere.
 *
 * TLS certificate validation is never switched off. Instead, one additional certificate can be
 * trusted on top of the JVM's default CAs, e.g. the root certificate of a corporate
 * TLS-inspecting proxy such as Zscaler.
 */
public final class HttpClientFactory {

	/**
	 * Utility class, not instantiable.
	 */
	private HttpClientFactory() {
		// Utility class
	}

	/**
	 * Creates an HttpClient for the given target url, following redirects (except https to http).
	 *
	 * @param targetUrl          url the client is used for, needed for PAC/WPAD proxy resolution
	 * @param proxyConfig        proxy settings, or null for a direct connection
	 * @param tlsCertificateFile path of an additionally trusted X.509 certificate file (PEM or DER),
	 *                           or null to trust the JVM's default CAs only
	 * @return the configured HttpClient
	 * @throws Exception if the proxy cannot be determined (invalid proxy url, PAC script not
	 *                   loadable) or the certificate file cannot be read
	 */
	public static HttpClient createHttpClient(final String targetUrl, final ProxyConfig proxyConfig, final String tlsCertificateFile) throws Exception {
		final HttpClient.Builder httpClientBuilder = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL);
		final ProxySelector proxySelector = resolveProxySelector(proxyConfig, targetUrl);
		if (proxySelector != null) {
			httpClientBuilder.proxy(proxySelector);
		}
		if (tlsCertificateFile != null) {
			httpClientBuilder.sslContext(buildSslContextWithAdditionalTrust(tlsCertificateFile));
		}
		return httpClientBuilder.build();
	}

	/**
	 * Resolves the proxy to use for the given target url, in precedence order:
	 * explicit proxyUrl &gt; explicit PAC url &gt; WPAD auto-discovery.
	 *
	 * @param proxyConfig proxy settings, or null for a direct connection
	 * @param targetUrl   url the client is used for, needed for PAC/WPAD proxy resolution
	 * @return the proxy selector, or null for DIRECT (no proxy configured / no proxy needed for
	 *         this url)
	 * @throws Exception if the proxy url is invalid or the PAC script cannot be evaluated
	 */
	private static ProxySelector resolveProxySelector(final ProxyConfig proxyConfig, final String targetUrl) throws Exception {
		if (proxyConfig == null) {
			return null;
		} else if (proxyConfig.getProxyUrl() != null) {
			return ProxySelector.of(parseProxyAddress(proxyConfig.getProxyUrl()));
		} else if (proxyConfig.getPacUrl() != null) {
			return toProxySelector(new ProxyConfiguration(ProxyConfigurationType.PACURL, proxyConfig.getPacUrl()).getProxy(targetUrl));
		} else if (proxyConfig.isUseWpad()) {
			return toProxySelector(new ProxyConfiguration(ProxyConfigurationType.WPAD).getProxy(targetUrl));
		} else {
			return null;
		}
	}

	/**
	 * Parses a direct proxy given as "scheme://host:port" or as plain "host:port". The port
	 * defaults to 80 if omitted.
	 *
	 * @param proxyUrl the proxy url
	 * @return the socket address of the proxy
	 * @throws IllegalArgumentException if the proxy url has no host or an invalid port
	 */
	static InetSocketAddress parseProxyAddress(final String proxyUrl) {
		final String trimmedProxyUrl = proxyUrl.trim();
		// Without "://", "proxy:8080" would be parsed as an opaque URI with scheme "proxy" and no host
		final URI proxyUri;
		try {
			proxyUri = URI.create(trimmedProxyUrl.contains("://") ? trimmedProxyUrl : "http://" + trimmedProxyUrl);
		} catch (final IllegalArgumentException e) {
			throw new IllegalArgumentException("Invalid proxyUrl '" + proxyUrl + "': " + e.getMessage(), e);
		}
		if (proxyUri.getHost() == null) {
			throw new IllegalArgumentException("Invalid proxyUrl '" + proxyUrl + "': no host found (expected e.g. http://proxy.example.com:8080)");
		}
		final int port = proxyUri.getPort() >= 0 ? proxyUri.getPort() : 80;
		return new InetSocketAddress(proxyUri.getHost(), port);
	}

	/**
	 * Wraps a single proxy, as returned by PAC/WPAD evaluation, into a ProxySelector.
	 *
	 * @param proxy the proxy, may be null
	 * @return the proxy selector, or null for DIRECT
	 */
	private static ProxySelector toProxySelector(final Proxy proxy) {
		if (proxy == null || proxy.type() == Proxy.Type.DIRECT) {
			return null; // HttpClient defaults to DIRECT when no ProxySelector is set
		}
		return ProxySelector.of((InetSocketAddress) proxy.address());
	}

	/**
	 * Builds an SSLContext that trusts the JVM's default CAs plus the given additional
	 * certificate (e.g. a corporate MITM proxy root certificate such as Zscaler's).
	 *
	 * @param certificateFilePath path of the additionally trusted X.509 certificate file (PEM or DER)
	 * @return the SSLContext
	 * @throws Exception if the certificate file cannot be read or parsed, or the default trust
	 *                   store is not available
	 */
	static SSLContext buildSslContextWithAdditionalTrust(final String certificateFilePath) throws Exception {
		final TrustManagerFactory defaultTrustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
		defaultTrustManagerFactory.init((KeyStore) null);

		final KeyStore combinedTrustStore = KeyStore.getInstance(KeyStore.getDefaultType());
		combinedTrustStore.load(null, null);

		int aliasIndex = 0;
		for (final TrustManager trustManager : defaultTrustManagerFactory.getTrustManagers()) {
			if (trustManager instanceof X509TrustManager) {
				for (final X509Certificate issuer : ((X509TrustManager) trustManager).getAcceptedIssuers()) {
					combinedTrustStore.setCertificateEntry("default-" + aliasIndex++, issuer);
				}
			}
		}

		try (InputStream certificateInputStream = Files.newInputStream(Paths.get(certificateFilePath))) {
			final Certificate additionalCertificate = CertificateFactory.getInstance("X.509").generateCertificate(certificateInputStream);
			combinedTrustStore.setCertificateEntry("additional-trusted-cert", additionalCertificate);
		}

		final TrustManagerFactory combinedTrustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
		combinedTrustManagerFactory.init(combinedTrustStore);

		final SSLContext sslContext = SSLContext.getInstance("TLS");
		sslContext.init(null, combinedTrustManagerFactory.getTrustManagers(), new SecureRandom());
		return sslContext;
	}
}
