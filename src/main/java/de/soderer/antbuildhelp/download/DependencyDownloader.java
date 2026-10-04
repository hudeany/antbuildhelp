package de.soderer.antbuildhelp.download;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
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
	public Path resolveAndDownload(final DependencyEntry dependencyEntry) throws Exception {
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

		downloadToFile(downloadUrl, targetPath, dependencyEntry);
		return targetPath;
	}

	/**
	 * Downloads into a temporary file next to the target first and moves it into place only when
	 * complete, because an existing file in the local repository is trusted on the next run: an
	 * interrupted download must never leave a partial jar there.
	 */
	private static void downloadToFile(final String downloadUrl, final Path targetPath, final DependencyEntry dependencyEntry) throws Exception {
		Files.createDirectories(targetPath.getParent());

		final HttpClient httpClient = HttpClientFactory.createHttpClient(downloadUrl, dependencyEntry.getProxyConfig(), dependencyEntry.getTlsCertificateFile());

		final HttpRequest httpRequest = HttpRequest.newBuilder(URI.create(downloadUrl)).GET().build();
		final HttpResponse<InputStream> httpResponse = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofInputStream());

		if (httpResponse.statusCode() != 200) {
			httpResponse.body().close();
			// The url may contain credentials, so only the dependency name is reported
			throw new IOException("Download failed with HTTP status " + httpResponse.statusCode()
					+ " for dependency '" + dependencyEntry.getName() + "'");
		}

		final Path tempFile = Files.createTempFile(targetPath.getParent(), ".download-", ".tmp");
		try {
			try (InputStream inputStream = httpResponse.body()) {
				Files.copy(inputStream, tempFile, StandardCopyOption.REPLACE_EXISTING);
			}
			try {
				Files.move(tempFile, targetPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
			} catch (@SuppressWarnings("unused") final AtomicMoveNotSupportedException e) {
				Files.move(tempFile, targetPath, StandardCopyOption.REPLACE_EXISTING);
			}
		} finally {
			Files.deleteIfExists(tempFile);
		}
	}
}
