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
import de.soderer.antbuildhelp.config.ProxyConfig;
import de.soderer.antbuildhelp.download.CredentialsProvider;
import de.soderer.antbuildhelp.download.DependencyDownloader;
import de.soderer.antbuildhelp.download.HttpClientFactory;
import de.soderer.antbuildhelp.repository.RepositoryPathBuilder;
import de.soderer.antbuildhelp.versions.VersionResolver;
import de.soderer.antbuildhelp.versions.VersionsJson;

/**
 * Ant task that resolves and downloads one or more dependencies into the local
 * (Maven-layout-compatible) repository.
 *
 * Proxy (proxyUrl / pacUrl / useWpad) and TLS trust (tlsCertificateFile) settings apply to the
 * Versions.json request as well as to all downloads. TLS certificate validation cannot be
 * switched off: for a corporate TLS-inspecting proxy, its root certificate is trusted
 * additionally via tlsCertificateFile instead. A dependency may override the task's
 * tlsCertificateFile with its own.
 *
 * Example usage in a build.xml:
 *
 * <pre>{@code
 * <typedef resource="de/soderer/antbuildhelp/antlib.xml" classpath="lib_build/antbuildhelp.jar" />
 *
 * <resolveDependencies repositoryRoot="${user.home}/.m2/repository"
 *                      groupId="de.soderer"
 *                      versionsJsonUrl="https://www.soderer.de/index.php?download=Versions.json"
 *                      username="myuser"
 *                      password="mypassword"
 *                      useWpad="true"
 *                      tlsCertificateFile="zscaler-root.cer">
 *     <dependency name="RestClient" version="latest"/>
 *     <dependency name="SomeThirdPartyLib" version="1.2.3"
 *                 url="https://example.com/libs/{name}-{version}.jar"
 *                 tlsCertificateFile="example-com-root.cer"/>
 * </resolveDependencies>
 * }</pre>
 */
public class ResolveDependenciesTask extends Task {

	private String repositoryRoot = System.getProperty("user.home") + "/.m2/repository";
	private String groupId = "de.soderer";
	private String versionsJsonUrl;
	private String username;
	private String password;
	private String proxyUrl;
	private String pacUrl;
	private boolean useWpad;
	private String tlsCertificateFile;

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

	public void setProxyUrl(final String proxyUrl) {
		this.proxyUrl = proxyUrl;
	}

	public void setPacUrl(final String pacUrl) {
		this.pacUrl = pacUrl;
	}

	public void setUseWpad(final boolean useWpad) {
		this.useWpad = useWpad;
	}

	/** Additionally trusted certificate for all requests of this task, relative paths are resolved against the project's basedir */
	public void setTlsCertificateFile(final String tlsCertificateFile) {
		this.tlsCertificateFile = tlsCertificateFile;
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
			final ProxyConfig proxyConfig = proxyUrl != null || pacUrl != null || useWpad
					? new ProxyConfig().withProxyUrl(proxyUrl).withPacUrl(pacUrl).withUseWpad(useWpad)
					: null;
			final String taskTlsCertificateFile = resolveAgainstBaseDir(tlsCertificateFile);

			final VersionsJson versionsJson = versionsJsonUrl != null
					? VersionsJson.parse(fetchTextContent(versionsJsonUrl, proxyConfig, taskTlsCertificateFile))
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
						.withProxyConfig(proxyConfig)
						.withTlsCertificateFile(dependencyElement.tlsCertificateFile != null
								? resolveAgainstBaseDir(dependencyElement.tlsCertificateFile)
								: taskTlsCertificateFile);

				log("Resolving dependency '" + dependencyElement.name + "' ...");
				final Path jarPath = dependencyDownloader.resolveAndDownload(dependencyEntry);
				log("-> " + jarPath);
			}
		} catch (final Exception e) {
			throw new BuildException("AntBuildHelp: dependency resolution failed: " + e.getMessage(), e);
		}
	}

	private static String fetchTextContent(final String url, final ProxyConfig proxyConfig, final String tlsCertificateFilePath) throws Exception {
		final HttpClient httpClient = HttpClientFactory.createHttpClient(url, proxyConfig, tlsCertificateFilePath);
		final HttpRequest httpRequest = HttpRequest.newBuilder(URI.create(url)).GET().build();
		final HttpResponse<String> httpResponse = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
		if (httpResponse.statusCode() != 200) {
			throw new IOException("Could not fetch Versions.json, HTTP status " + httpResponse.statusCode());
		}
		return httpResponse.body();
	}

	/** Resolves a path given in the build.xml against the project's basedir when relative; null stays null */
	private String resolveAgainstBaseDir(final String filePath) {
		if (filePath == null) {
			return null;
		}
		final Path path = Paths.get(filePath);
		return (path.isAbsolute() ? path : getProject().getBaseDir().toPath().resolve(path)).toString();
	}

	/** Nested <dependency> element, as configured by Ant via reflection (setters). */
	public static class DependencyElement {

		private String name;
		private String version = "latest";
		private String url;
		private String tlsCertificateFile;

		public void setName(final String name) {
			this.name = name;
		}

		public void setVersion(final String version) {
			this.version = version;
		}

		public void setUrl(final String url) {
			this.url = url;
		}

		/** Overrides the task's tlsCertificateFile for this dependency only */
		public void setTlsCertificateFile(final String tlsCertificateFile) {
			this.tlsCertificateFile = tlsCertificateFile;
		}
	}
}
