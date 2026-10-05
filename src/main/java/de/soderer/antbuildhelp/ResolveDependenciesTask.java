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

	/** Value of the "repositoryRoot" attribute, default: "~/.m2/repository" */
	private String repositoryRoot = System.getProperty("user.home") + "/.m2/repository";

	/** Value of the "groupId" attribute, default: "de.soderer" */
	private String groupId = "de.soderer";

	/** Value of the "versionsJsonUrl" attribute */
	private String versionsJsonUrl;

	/** Value of the "username" attribute */
	private String username;

	/** Value of the "password" attribute */
	private String password;

	/** Value of the "proxyUrl" attribute */
	private String proxyUrl;

	/** Value of the "pacUrl" attribute */
	private String pacUrl;

	/** Value of the "useWpad" attribute */
	private boolean useWpad;

	/** Value of the "tlsCertificateFile" attribute */
	private String tlsCertificateFile;

	/** Nested {@code <dependency>} elements, in build.xml order */
	private final List<DependencyElement> dependencyElements = new ArrayList<>();

	/**
	 * Creates the task; Ant configures it via the attribute setters and nested elements afterwards.
	 */
	public ResolveDependenciesTask() {
		// Configured by Ant via the setters
	}

	/**
	 * Sets the root directory of the local repository. Defaults to "~/.m2/repository".
	 *
	 * @param repositoryRoot the repository root directory
	 */
	public void setRepositoryRoot(final String repositoryRoot) {
		this.repositoryRoot = repositoryRoot;
	}

	/**
	 * Sets the groupId used for the local repository path of all dependencies. Defaults to
	 * "de.soderer".
	 *
	 * @param groupId the groupId
	 */
	public void setGroupId(final String groupId) {
		this.groupId = groupId;
	}

	/**
	 * Sets the url of the central Versions.json, needed for dependencies with version
	 * "latest"/"current" (the default of nested elements).
	 *
	 * @param versionsJsonUrl the Versions.json url
	 */
	public void setVersionsJsonUrl(final String versionsJsonUrl) {
		this.versionsJsonUrl = versionsJsonUrl;
	}

	/**
	 * Sets the user name substituted for the {username}/&lt;username&gt; url placeholders. It is
	 * percent-encoded automatically, so it must be given raw, not url-encoded.
	 *
	 * @param username the user name
	 */
	public void setUsername(final String username) {
		this.username = username;
	}

	/**
	 * Sets the password substituted for the {password}/&lt;password&gt; url placeholders. It is
	 * percent-encoded automatically, so it must be given raw, not url-encoded (special characters
	 * like "&amp;", "#" or "@" are safe).
	 * Only used if a username is set.
	 *
	 * @param password the password
	 */
	public void setPassword(final String password) {
		this.password = password;
	}

	/**
	 * Sets a direct proxy, e.g. "http://proxy.example.com:8080". Takes precedence over
	 * pacUrl and useWpad.
	 *
	 * @param proxyUrl the proxy url
	 */
	public void setProxyUrl(final String proxyUrl) {
		this.proxyUrl = proxyUrl;
	}

	/**
	 * Sets the url of a PAC script that decides the proxy per request. Takes precedence over
	 * useWpad.
	 *
	 * @param pacUrl the PAC script url
	 */
	public void setPacUrl(final String pacUrl) {
		this.pacUrl = pacUrl;
	}

	/**
	 * Sets whether the PAC script is auto-detected via WPAD.
	 *
	 * @param useWpad true to use WPAD
	 */
	public void setUseWpad(final boolean useWpad) {
		this.useWpad = useWpad;
	}

	/**
	 * Sets an additionally trusted certificate for all requests of this task. Relative paths are
	 * resolved against the project's basedir.
	 *
	 * @param tlsCertificateFile path of the certificate file (PEM or DER)
	 */
	public void setTlsCertificateFile(final String tlsCertificateFile) {
		this.tlsCertificateFile = tlsCertificateFile;
	}

	/**
	 * Called by Ant for each nested {@code <dependency>} element.
	 *
	 * @return the new, still unconfigured element
	 */
	public DependencyElement createDependency() {
		final DependencyElement dependencyElement = new DependencyElement();
		dependencyElements.add(dependencyElement);
		return dependencyElement;
	}

	/**
	 * Resolves all nested dependencies into the local repository, loading the central
	 * Versions.json first if configured.
	 *
	 * @throws BuildException if the Versions.json or any dependency cannot be resolved
	 */
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
				if (dependencyElement.name == null) {
					throw new BuildException("Nested <dependency> element requires 'name' to be set");
				}
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

	/**
	 * Fetches the Versions.json content.
	 *
	 * @param url                    the Versions.json url
	 * @param proxyConfig            proxy settings, or null for a direct connection
	 * @param tlsCertificateFilePath additionally trusted certificate file, or null
	 * @return the response body
	 * @throws Exception if the request fails or the server does not answer with HTTP 200
	 */
	private static String fetchTextContent(final String url, final ProxyConfig proxyConfig, final String tlsCertificateFilePath) throws Exception {
		final HttpClient httpClient = HttpClientFactory.createHttpClient(url, proxyConfig, tlsCertificateFilePath);
		final HttpRequest httpRequest = HttpRequest.newBuilder(URI.create(url)).GET().build();
		final HttpResponse<String> httpResponse = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
		if (httpResponse.statusCode() != 200) {
			throw new IOException("Could not fetch Versions.json, HTTP status " + httpResponse.statusCode());
		}
		return httpResponse.body();
	}

	/**
	 * Resolves a path given in the build.xml against the project's basedir when relative.
	 *
	 * @param filePath the path, may be null
	 * @return the resolved path, or null if filePath is null
	 */
	private String resolveAgainstBaseDir(final String filePath) {
		if (filePath == null) {
			return null;
		}
		final Path path = Paths.get(filePath);
		return (path.isAbsolute() ? path : getProject().getBaseDir().toPath().resolve(path)).toString();
	}

	/**
	 * Nested {@code <dependency>} element, as configured by Ant via reflection (setters).
	 */
	public static class DependencyElement {

		/** Value of the "name" attribute */
		private String name;

		/** Value of the "version" attribute, default: "latest" */
		private String version = "latest";

		/** Value of the "url" attribute */
		private String url;

		/** Value of the "tlsCertificateFile" attribute */
		private String tlsCertificateFile;

		/**
		 * Creates the element; Ant configures it via the attribute setters afterwards.
		 */
		public DependencyElement() {
			// Configured by Ant via the setters
		}

		/**
		 * Sets the dependency name, used as key into the Versions.json and for the repository path.
		 * Required.
		 *
		 * @param name the dependency name
		 */
		public void setName(final String name) {
			this.name = name;
		}

		/**
		 * Sets the version: a fixed version, or "latest"/"current" (the default) to use the
		 * version from the Versions.json.
		 *
		 * @param version the version
		 */
		public void setVersion(final String version) {
			this.version = version;
		}

		/**
		 * Sets the download url template, with optional {name}/{version}/{username}/{password}
		 * placeholders. Defaults to the Versions.json entry's download url for the latest version.
		 *
		 * @param url the download url template
		 */
		public void setUrl(final String url) {
			this.url = url;
		}

		/**
		 * Overrides the task's tlsCertificateFile for this dependency only.
		 *
		 * @param tlsCertificateFile path of the certificate file (PEM or DER)
		 */
		public void setTlsCertificateFile(final String tlsCertificateFile) {
			this.tlsCertificateFile = tlsCertificateFile;
		}
	}
}
