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
import java.util.Locale;
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

	/** Resolves the effective version and url template of each dependency */
	private final VersionResolver versionResolver;

	/** Builds the jar paths in the local repository */
	private final RepositoryPathBuilder repositoryPathBuilder;

	/** Credentials for url placeholder substitution, may be null */
	private final CredentialsProvider credentialsProvider;

	/** GroupId used for the local repository path of all dependencies, e.g. "de.soderer" */
	private final String defaultGroupId;

	/**
	 * Creates a downloader.
	 *
	 * @param versionResolver       resolves the effective version and url template of each dependency
	 * @param repositoryPathBuilder builds the jar paths in the local repository
	 * @param credentialsProvider   credentials for the {username}/{password} url placeholders, or
	 *                              null if no credentials are needed
	 * @param defaultGroupId        groupId used for the local repository path of all
	 *                              dependencies, e.g. "de.soderer"
	 */
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
	 * An existing jar in the local repository is trusted without any further check, so it is
	 * not downloaded again.
	 *
	 * @param dependencyEntry the dependency to resolve
	 * @return the path of the jar in the local repository
	 * @throws Exception if the dependency is incompletely defined, the version cannot be
	 *                   resolved, or the download fails
	 */
	public Path resolveAndDownload(final DependencyEntry dependencyEntry) throws Exception {
		if (dependencyEntry.getName() == null) {
			throw new IllegalStateException("Dependency requires 'name' to be set");
		}
		final ResolvedDependency resolvedDependency = versionResolver.resolve(dependencyEntry);
		if (resolvedDependency.getVersion() == null) {
			throw new IllegalStateException("No version available for dependency '" + resolvedDependency.getName() + "'");
		}

		final Path targetPath = repositoryPathBuilder.buildJarPath(
				defaultGroupId, resolvedDependency.getName().toLowerCase(Locale.ROOT), resolvedDependency.getVersion());

		if (Files.isRegularFile(targetPath)) {
			return targetPath; // already present, nothing to do
		}

		if (resolvedDependency.getDownloadUrlTemplate() == null) {
			throw new IllegalStateException("No download url available for dependency '" + resolvedDependency.getName()
					+ "': set 'url' or provide a Versions.json entry");
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
	 *
	 * @param downloadUrl     the url to download, may contain credentials
	 * @param targetPath      the final path in the local repository
	 * @param dependencyEntry the dependency, for proxy/TLS settings and error messages
	 * @throws Exception if the download fails or the server does not answer with HTTP 200
	 */
	private static void downloadToFile(final String downloadUrl, final Path targetPath, final DependencyEntry dependencyEntry) throws Exception {
		Files.createDirectories(targetPath.getParent());

		final HttpClient httpClient = HttpClientFactory.createHttpClient(downloadUrl, dependencyEntry.getProxyConfig(), dependencyEntry.getTlsCertificateFile());

		final URI downloadUri;
		try {
			downloadUri = URI.create(downloadUrl);
		} catch (@SuppressWarnings("unused") final IllegalArgumentException e) {
			// The exception message would contain the url and so possibly the credentials
			throw new IOException("Invalid download url for dependency '" + dependencyEntry.getName() + "'");
		}
		final HttpRequest httpRequest = HttpRequest.newBuilder(downloadUri).GET().build();
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
