package de.soderer.antbuildhelp;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.KeyStore;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;

/**
 * Self-signed server certificate for 127.0.0.1, generated once per test run with the JDK's
 * keytool, so HTTPS tests need neither checked-in key material nor external libraries.
 */
final class TestCertificate {

	private static final String PASSWORD = "changeit";
	private static final String ALIAS = "antbuildhelp-test";

	private static TestCertificate instance;

	private final Path keyStoreFile;
	private final Path certificateFile;

	private TestCertificate(final Path keyStoreFile, final Path certificateFile) {
		this.keyStoreFile = keyStoreFile;
		this.certificateFile = certificateFile;
	}

	static synchronized TestCertificate get() throws Exception {
		if (instance == null) {
			final Path directory = Files.createTempDirectory("antbuildhelp-test-certificate");
			final Path keyStoreFile = directory.resolve("server.p12");
			final Path certificateFile = directory.resolve("server.pem");
			runKeytool("-genkeypair", "-alias", ALIAS, "-keyalg", "RSA", "-keysize", "2048", "-validity", "2",
					"-dname", "CN=127.0.0.1", "-ext", "SAN=ip:127.0.0.1,dns:localhost",
					"-keystore", keyStoreFile.toString(), "-storetype", "PKCS12", "-storepass", PASSWORD, "-keypass", PASSWORD);
			runKeytool("-exportcert", "-rfc", "-alias", ALIAS, "-file", certificateFile.toString(),
					"-keystore", keyStoreFile.toString(), "-storetype", "PKCS12", "-storepass", PASSWORD);
			Runtime.getRuntime().addShutdownHook(new Thread(() -> deleteRecursively(directory)));
			instance = new TestCertificate(keyStoreFile, certificateFile);
		}
		return instance;
	}

	/** PEM file of the self-signed server certificate, as used for tlsCertificateFile */
	Path getCertificateFile() {
		return certificateFile;
	}

	/** Server-side SSLContext for {@link TestHttpServer} */
	SSLContext createServerSslContext() throws Exception {
		final KeyStore keyStore = KeyStore.getInstance("PKCS12");
		try (InputStream inputStream = Files.newInputStream(keyStoreFile)) {
			keyStore.load(inputStream, PASSWORD.toCharArray());
		}
		final KeyManagerFactory keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
		keyManagerFactory.init(keyStore, PASSWORD.toCharArray());
		final SSLContext sslContext = SSLContext.getInstance("TLS");
		sslContext.init(keyManagerFactory.getKeyManagers(), null, null);
		return sslContext;
	}

	private static void runKeytool(final String... arguments) throws Exception {
		final boolean isWindows = System.getProperty("os.name").toLowerCase().contains("windows");
		final Path keytool = Paths.get(System.getProperty("java.home"), "bin", isWindows ? "keytool.exe" : "keytool");
		final List<String> command = new java.util.ArrayList<>();
		command.add(keytool.toString());
		command.addAll(Arrays.asList(arguments));
		final Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
		final String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
		if (process.waitFor() != 0) {
			throw new IllegalStateException("keytool failed: " + output);
		}
	}

	private static void deleteRecursively(final Path directory) {
		try (Stream<Path> paths = Files.walk(directory)) {
			paths.sorted((a, b) -> b.compareTo(a)).forEach(path -> {
				try {
					Files.deleteIfExists(path);
				} catch (@SuppressWarnings("unused") final IOException e) {
					// Best effort cleanup of temporary test files
				}
			});
		} catch (@SuppressWarnings("unused") final IOException e) {
			// Best effort cleanup of temporary test files
		}
	}
}
