package de.soderer.antbuildhelp;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import org.apache.tools.ant.BuildException;
import org.apache.tools.ant.Task;

import de.soderer.antbuildhelp.config.DependencyEntry;
import de.soderer.antbuildhelp.download.CredentialsProvider;
import de.soderer.antbuildhelp.download.DependencyDownloader;
import de.soderer.antbuildhelp.repository.RepositoryPathBuilder;
import de.soderer.antbuildhelp.versions.VersionResolver;
import de.soderer.antbuildhelp.versions.VersionsJson;

/**
 * Ant task that resolves and downloads one or more dependencies into the local
 * (Maven-layout-compatible) repository.
 *
 * Example usage in a build.xml:
 *
 * <pre>{@code
 * <taskdef name="resolvedeps"
 *          classname="de.soderer.antbuildhelp.ant.ResolveDependenciesTask"
 *          classpath="lib/antbuildhelp.jar:lib/json.jar"/>
 *
 * <resolvedeps repositoryRoot="${user.home}/.m2/repository"
 *              groupId="de.soderer"
 *              versionsJsonUrl="https://www.soderer.de/index.php?download=Versions.json"
 *              username="myuser"
 *              password="mypassword">
 *     <dependency name="RestClient" version="latest"/>
 *     <dependency name="SomeThirdPartyLib" version="1.2.3"
 *                 url="https://example.com/libs/{name}-{version}.jar"
 *                 tlsCertCheck="false"/>
 * </resolvedeps>
 * }</pre>
 */
public class ResolveDependenciesTask extends Task {

	private String repositoryRoot = System.getProperty("user.home") + "/.m2/repository";
	private String groupId = "de.soderer";
	private String versionsJsonUrl;
	private String username;
	private String password;

	private final List<DependencyElement> dependencyElements = new ArrayList<>();

	public void setRepositoryRoot(final String repositoryRoot) {
		this.repositoryRoot = repositoryRoot;
	}

	public void setGroupId(final String groupId) {
		this.groupId = groupId;
	}

	public void setVersionsJsonUrl(final String versionsJsonUrl) {
		this.versionsJsonUrl = versionsJsonUrl;
	}

	public void setUsername(final String username) {
		this.username = username;
	}

	public void setPassword(final String password) {
		this.password = password;
	}

	/** Called by Ant for each nested <dependency> element. */
	public DependencyElement createDependency() {
		final DependencyElement dependencyElement = new DependencyElement();
		dependencyElements.add(dependencyElement);
		return dependencyElement;
	}

	@Override
	public void execute() throws BuildException {
		try {
			final VersionsJson versionsJson = versionsJsonUrl != null
					? VersionsJson.parse(fetchTextContent(versionsJsonUrl))
					: null;

			final VersionResolver versionResolver = new VersionResolver(versionsJson);
			final RepositoryPathBuilder repositoryPathBuilder =
					new RepositoryPathBuilder(Paths.get(repositoryRoot));
			final CredentialsProvider credentialsProvider =
					username != null ? new CredentialsProvider(username, password) : null;
			final DependencyDownloader dependencyDownloader = new DependencyDownloader(
					versionResolver, repositoryPathBuilder, credentialsProvider, groupId);

			for (final DependencyElement dependencyElement : dependencyElements) {
				final DependencyEntry dependencyEntry = new DependencyEntry()
						.withName(dependencyElement.name)
						.withVersion(dependencyElement.version)
						.withDownloadUrlTemplate(dependencyElement.url)
						.withTlsCertCheckEnabled(dependencyElement.tlsCertCheck);

				log("Resolving dependency '" + dependencyElement.name + "' ...");
				final Path jarPath = dependencyDownloader.resolveAndDownload(dependencyEntry);
				log("-> " + jarPath);
			}
		} catch (final Exception e) {
			throw new BuildException("AntBuildHelp: dependency resolution failed", e);
		}
	}

	private static String fetchTextContent(final String url) throws IOException, InterruptedException {
		final HttpClient httpClient = HttpClient.newHttpClient();
		final HttpRequest httpRequest = HttpRequest.newBuilder(URI.create(url)).GET().build();
		final HttpResponse<String> httpResponse = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
		if (httpResponse.statusCode() != 200) {
			throw new IOException("Could not fetch Versions.json, HTTP status " + httpResponse.statusCode());
		}
		return httpResponse.body();
	}

	/** Nested <dependency> element, as configured by Ant via reflection (setters). */
	public static class DependencyElement {

		private String name;
		private String version = "latest";
		private String url;
		private boolean tlsCertCheck = true;

		public void setName(final String name) {
			this.name = name;
		}

		public void setVersion(final String version) {
			this.version = version;
		}

		public void setUrl(final String url) {
			this.url = url;
		}

		public void setTlsCertCheck(final boolean tlsCertCheck) {
			this.tlsCertCheck = tlsCertCheck;
		}
	}
}
