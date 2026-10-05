package de.soderer.antbuildhelp;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import javax.net.ssl.SSLContext;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;

/**
 * Minimal local HTTP server for the Ant task tests, based on the JDK's built-in
 * com.sun.net.httpserver, so the tests need neither network access nor external libraries.
 *
 * Responses are registered per raw request path including the query string (e.g.
 * "/download?id=42"). Unregistered paths are answered with HTTP 404. GET requests are counted per
 * path, so tests can check whether a file was actually downloaded or taken from the local cache.
 * With an SSLContext the server speaks HTTPS instead of plain HTTP.
 */
class TestHttpServer implements AutoCloseable {

	private static class Response {
		private final int statusCode;
		private final byte[] body;
		private final Map<String, String> headers;

		private Response(final int statusCode, final byte[] body, final Map<String, String> headers) {
			this.statusCode = statusCode;
			this.body = body;
			this.headers = headers;
		}
	}

	private final HttpServer httpServer;
	private final String scheme;
	private final Map<String, Response> responses = new ConcurrentHashMap<>();
	private final Map<String, AtomicInteger> getRequestCounts = new ConcurrentHashMap<>();

	TestHttpServer() throws IOException {
		this(null);
	}

	/** HTTPS server using the given server-side SSLContext, or plain HTTP if null */
	TestHttpServer(final SSLContext sslContext) throws IOException {
		final InetSocketAddress address = new InetSocketAddress(InetAddress.getLoopbackAddress(), 0);
		if (sslContext == null) {
			httpServer = HttpServer.create(address, 0);
			scheme = "http";
		} else {
			final HttpsServer httpsServer = HttpsServer.create(address, 0);
			httpsServer.setHttpsConfigurator(new HttpsConfigurator(sslContext));
			httpServer = httpsServer;
			scheme = "https";
		}
		httpServer.createContext("/", this::handle);
		httpServer.start();
	}

	/** Base url without trailing slash, e.g. "http://127.0.0.1:54321" */
	String getBaseUrl() {
		return scheme + "://" + httpServer.getAddress().getHostString() + ":" + httpServer.getAddress().getPort();
	}

	void addFile(final String pathAndQuery, final byte[] body) {
		addFile(pathAndQuery, body, Collections.emptyMap());
	}

	void addFile(final String pathAndQuery, final byte[] body, final Map<String, String> headers) {
		responses.put(pathAndQuery, new Response(200, body, new LinkedHashMap<>(headers)));
	}

	/** Registers an empty response with the given HTTP status code, e.g. 503 */
	void addStatus(final String pathAndQuery, final int statusCode) {
		responses.put(pathAndQuery, new Response(statusCode, new byte[0], Collections.emptyMap()));
	}

	void addText(final String pathAndQuery, final String text) {
		addFile(pathAndQuery, text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
	}

	/**
	 * Registers a file together with its checksum files (".md5", ".sha1", ".sha256", ".sha512",
	 * each containing only the lowercase hex digest, as in Maven repositories).
	 */
	void addFileWithChecksums(final String path, final byte[] body) {
		addFile(path, body);
		for (final String algorithm : new String[] { "md5", "sha1", "sha256", "sha512" }) {
			addText(path + "." + algorithm, AntTestUtilities.checksum(body, algorithm));
		}
	}

	/**
	 * Registers a Maven-layout artifact below repositoryBasePath (e.g. "/maven2"), including checksum files.
	 * classifier may be null.
	 */
	void addMavenArtifact(final String repositoryBasePath, final String groupId, final String artifactId, final String version, final String classifier, final byte[] body) {
		addFileWithChecksums(getMavenArtifactPath(repositoryBasePath, groupId, artifactId, version, classifier), body);
	}

	static String getMavenArtifactPath(final String repositoryBasePath, final String groupId, final String artifactId, final String version, final String classifier) {
		return repositoryBasePath + "/" + groupId.replace('.', '/') + "/" + artifactId + "/" + version + "/"
				+ artifactId + "-" + version + (classifier == null ? "" : "-" + classifier) + ".jar";
	}

	int getGetRequestCount(final String pathAndQuery) {
		final AtomicInteger count = getRequestCounts.get(pathAndQuery);
		return count == null ? 0 : count.get();
	}

	private void handle(final HttpExchange httpExchange) throws IOException {
		try {
			final String pathAndQuery = httpExchange.getRequestURI().getRawPath()
					+ (httpExchange.getRequestURI().getRawQuery() == null ? "" : "?" + httpExchange.getRequestURI().getRawQuery());
			final boolean isHeadRequest = "HEAD".equalsIgnoreCase(httpExchange.getRequestMethod());
			if ("GET".equalsIgnoreCase(httpExchange.getRequestMethod())) {
				getRequestCounts.computeIfAbsent(pathAndQuery, key -> new AtomicInteger()).incrementAndGet();
			}

			final Response response = responses.get(pathAndQuery);
			if (response == null) {
				httpExchange.sendResponseHeaders(404, -1);
			} else {
				for (final Map.Entry<String, String> header : response.headers.entrySet()) {
					httpExchange.getResponseHeaders().add(header.getKey(), header.getValue());
				}
				if (isHeadRequest) {
					httpExchange.getResponseHeaders().add("Content-Length", Integer.toString(response.body.length));
					httpExchange.sendResponseHeaders(response.statusCode, -1);
				} else {
					httpExchange.sendResponseHeaders(response.statusCode, response.body.length == 0 ? -1 : response.body.length);
					try (OutputStream outputStream = httpExchange.getResponseBody()) {
						outputStream.write(response.body);
					}
				}
			}
		} finally {
			httpExchange.close();
		}
	}

	@Override
	public void close() {
		httpServer.stop(0);
	}
}
