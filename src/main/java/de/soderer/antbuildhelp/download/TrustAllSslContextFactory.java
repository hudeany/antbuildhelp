package de.soderer.antbuildhelp.download;

import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * Creates an SSLContext that accepts any server certificate.
 * Only used when TLS certificate checking is explicitly disabled in the dependency configuration.
 */
public class TrustAllSslContextFactory {

	public static SSLContext create() {
		try {
			final TrustManager[] trustAllManagers = new TrustManager[] {
					new X509TrustManager() {
						@Override
						public void checkClientTrusted(final X509Certificate[] chain, final String authType) {
							// intentionally accept all
						}

						@Override
						public void checkServerTrusted(final X509Certificate[] chain, final String authType) {
							// intentionally accept all
						}

						@Override
						public X509Certificate[] getAcceptedIssuers() {
							return new X509Certificate[0];
						}
					}
			};
			final SSLContext sslContext = SSLContext.getInstance("TLS");
			sslContext.init(null, trustAllManagers, new SecureRandom());
			return sslContext;
		} catch (final NoSuchAlgorithmException | KeyManagementException e) {
			throw new IllegalStateException("Could not create trust-all SSLContext", e);
		}
	}
}
