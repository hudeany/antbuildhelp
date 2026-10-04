package de.soderer.antbuildhelp.download;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import de.soderer.antbuildhelp.config.DependencyEntry;
import de.soderer.antbuildhelp.repository.RepositoryPathBuilder;
import de.soderer.antbuildhelp.versions.VersionResolver;
import de.soderer.antbuildhelp.versions.VersionResolver.ResolvedDependency;

/**
 * Orchestrates the download of a single dependency:
 * resolve version -> build download URL -> check local repository -> download if needed.
 */
public class DependencyDownloader {

	private final VersionResolver versionResolver;
	private final RepositoryPathBuilder repositoryPathBuilder;
	private final CredentialsProvider credentialsProvider;
	private final String defaultGroupId; // e.g. "de.soderer"

	public DependencyDownloader(final VersionResolver versionResolver,
			final RepositoryPathBuilder repositoryPathBuilder,
			final CredentialsProvider credentialsProvider,
			final String defaultGroupId) {
		this.versionResolver = versionResolver;
		this.repositoryPathBuilder = repositoryPathBuilder;
		this.credentialsProvider = credentialsProvider;
		this.defaultGroupId = defaultGroupId;
	}

	/**
	 * Resolves and, if not already present locally, downloads the dependency's jar.
	 *
	 * @return the path of the jar in the local repository
	 */
	public Path resolveAndDownload(final DependencyEntry dependencyEntry) throws IOException, InterruptedException {
		final ResolvedDependency resolvedDependency = versionResolver.resolve(dependencyEntry);

		final Path targetPath = repositoryPathBuilder.buildJarPath(
				defaultGroupId, resolvedDependency.getName().toLowerCase(), resolvedDependency.getVersion());

		if (Files.isRegularFile(targetPath)) {
			return targetPath; // already present, nothing to do
		}

		final Map<String, String> placeholderValues = new HashMap<>();
		placeholderValues.put("name", resolvedDependency.getName());
		placeholderValues.put("version", resolvedDependency.getVersion());
		if (credentialsProvider != null) {
			placeholderValues.put("username", credentialsProvider.getUsername());
			placeholderValues.put("password", credentialsProvider.getPassword());
		}

		final String downloadUrl = UrlTemplateResolver.resolve(
				resolvedDependency.getDownloadUrlTemplate(), placeholderValues);

		downloadToFile(downloadUrl, targetPath, dependencyEntry.isTlsCertCheckEnabled());
		return targetPath;
	}

	private static void downloadToFile(final String downloadUrl, final Path targetPath,
			final boolean tlsCertCheckEnabled) throws IOException, InterruptedException {
		Files.createDirectories(targetPath.getParent());

		final HttpClient.Builder httpClientBuilder = HttpClient.newBuilder();
		if (!tlsCertCheckEnabled) {
			// NOTE: disables certificate validation entirely, intended for corporate MITM proxies (e.g. Zscaler).
			httpClientBuilder.sslContext(TrustAllSslContextFactory.create());
		}
		final HttpClient httpClient = httpClientBuilder.build();

		final HttpRequest httpRequest = HttpRequest.newBuilder(URI.create(downloadUrl)).GET().build();
		final HttpResponse<InputStream> httpResponse = httpClient.send(httpRequest,
				HttpResponse.BodyHandlers.ofInputStream());

		if (httpResponse.statusCode() != 200) {
			throw new IOException("Download failed with HTTP status " + httpResponse.statusCode()
					+ " for URL: " + downloadUrl);
		}

		try (InputStream inputStream = httpResponse.body()) {
			Files.copy(inputStream, targetPath);
		}
	}
}
