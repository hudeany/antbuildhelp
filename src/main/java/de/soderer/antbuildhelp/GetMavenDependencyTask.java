package de.soderer.antbuildhelp;

import org.apache.tools.ant.BuildException;
import org.apache.tools.ant.Task;

/**
 * Thin Ant task wrapper around {@link DependencyResolver}, specifically for Maven-layout
 * repositories (Maven Central and Maven-compatible mirrors): always resolves via
 * groupId/artifactId/version, verifies the download against the strongest available checksum
 * file, and supports version="RELEASE"/"LATEST" via the repository's maven-metadata.xml.
 *
 * This is the Maven-only counterpart of {@link GetDependencyTask}, which also supports Maven
 * artifact mode (via its own {@code artifactId} attribute) alongside plain URL downloads, GitHub
 * releases and zip extraction. getMavenDependency exists purely to make the common, pure-Maven
 * case - the majority of dependencies in most projects - shorter and clearer to read, without
 * the plain-URL-related attributes getDependency also carries (and without the risk of {@code
 * artifactId} being left unset by accident, silently falling back to a literal-URL download
 * instead of Maven resolution - this task requires it and fails fast otherwise).
 *
 * The actual resolution logic lives in {@link DependencyResolver}, shared with
 * {@link GetDependencyTask} and {@link AntBuildHelpMain}.
 *
 * Example usage in a build.xml:
 *
 * <pre>{@code
 * <typedef resource="de/soderer/antbuildhelp/antlib.xml" classpath="lib_build/antbuildhelp.jar" />
 *
 * <getMavenDependency groupId="org.apache.poi" artifactId="poi" version="5.2.4" />
 *
 * <getMavenDependency groupId="com.sun.mail" artifactId="mailapi" version="RELEASE" />
 *
 * <!-- private Maven-layout mirror as repository base, instead of Maven Central -->
 * <getMavenDependency repositoryUrl="https://some-repository.com/maven2" groupId="de.soderer"
 *                     artifactId="soderer-utilities" version="26.2.53" />
 * <getMavenDependency repositoryUrl="https://some-repository.com/maven2" groupId="de.soderer"
 *                     artifactId="soderer-utilities" version="26.2.53" classifier="sources" />
 * }</pre>
 */
public class GetMavenDependencyTask extends Task {

	/** Value of the "groupId" attribute */
	private String groupId;

	/** Value of the "artifactId" attribute */
	private String artifactId;

	/** Value of the "version" attribute */
	private String version;

	/** Value of the "classifier" attribute */
	private String classifier;

	/** Value of the "repositoryUrl" attribute */
	private String repositoryUrl;

	/** Value of the "name" attribute */
	private String name;

	/** Value of the "libDir" attribute, default: "&lt;project dir&gt;/lib" */
	private String libDir;

	/** Value of the "repositoryRoot" attribute */
	private String repositoryRoot;

	/** Value of the "proxyUrl" attribute */
	private String proxyUrl;

	/** Value of the "pacUrl" attribute */
	private String pacUrl;

	/** Value of the "useWpad" attribute */
	private boolean useWpad;

	/** Value of the "tlsCertificateFile" attribute */
	private String tlsCertificateFile;

	/**
	 * Creates the task; Ant configures it via the attribute setters afterwards.
	 */
	public GetMavenDependencyTask() {
		// Configured by Ant via the setters
	}

	/**
	 * Sets the artifact's Maven groupId. Defaults to "de.soderer" (see
	 * {@link DependencyResolver#setGroupId(String)}) - set to the artifact's real Maven groupId.
	 *
	 * @param groupId the Maven groupId
	 */
	public void setGroupId(final String groupId) {
		this.groupId = groupId;
	}

	/**
	 * Sets the Maven artifactId. Required.
	 *
	 * @param artifactId the Maven artifactId
	 */
	public void setArtifactId(final String artifactId) {
		this.artifactId = artifactId;
	}

	/**
	 * Sets the artifact version. May be "RELEASE" or "LATEST" (case-insensitive) to resolve
	 * via the repository's maven-metadata.xml. Required.
	 *
	 * @param version the version or version keyword
	 */
	public void setVersion(final String version) {
		this.version = version;
	}

	/**
	 * Sets the standard Maven classifier, e.g. "sources" or "javadoc".
	 *
	 * @param classifier the Maven classifier
	 */
	public void setClassifier(final String classifier) {
		this.classifier = classifier;
	}

	/**
	 * Sets the Maven-layout repository base url, e.g. a private mirror such as
	 * "https://some-repository.com/maven2". Defaults to Maven Central (repo1.maven.org) if not set.
	 *
	 * @param repositoryUrl the repository base url
	 */
	public void setRepositoryUrl(final String repositoryUrl) {
		this.repositoryUrl = repositoryUrl;
	}

	/**
	 * Sets the local/logical dependency name for the local repository cache; defaults to
	 * artifactId (plus "-classifier") if not set.
	 *
	 * @param name the dependency name
	 */
	public void setName(final String name) {
		this.name = name;
	}

	/**
	 * Sets the target directory the jar is copied into. Relative paths are resolved against
	 * the project's basedir. Defaults to "&lt;project dir&gt;/lib".
	 *
	 * @param libDir the target directory
	 */
	public void setLibDir(final String libDir) {
		this.libDir = libDir;
	}

	/**
	 * Sets the root directory of the local repository cache. Defaults to
	 * "~/.m2/repository".
	 *
	 * @param repositoryRoot the repository root directory
	 */
	public void setRepositoryRoot(final String repositoryRoot) {
		this.repositoryRoot = repositoryRoot;
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
	 * Sets an additionally trusted X.509 certificate file (PEM or DER), e.g. the root certificate
	 * of a corporate TLS-inspecting proxy. Relative paths are resolved against the project's
	 * basedir. TLS certificate validation itself can never be switched off.
	 *
	 * @param tlsCertificateFile path of the certificate file
	 */
	public void setTlsCertificateFile(final String tlsCertificateFile) {
		this.tlsCertificateFile = tlsCertificateFile;
	}

	/**
	 * Resolves the artifact: downloads it into the local repository cache if not already
	 * present, verifies its checksum, then copies it into libDir, removing older versions there.
	 *
	 * @throws BuildException if artifactId is not set or the artifact cannot be resolved
	 */
	@Override
	public void execute() throws BuildException {
		if (artifactId == null) {
			throw new BuildException("getMavenDependency requires 'artifactId' to be set");
		}
		try {
			new DependencyResolver()
					.withName(name)
					.withVersion(version)
					.withUrl(repositoryUrl)
					.withArtifactId(artifactId)
					.withClassifier(classifier)
					.withGroupId(groupId)
					.withLibDir(resolveLibDir())
					.withRepositoryRoot(repositoryRoot)
					.withProxyUrl(proxyUrl)
					.withPacUrl(pacUrl)
					.withUseWpad(useWpad)
					.withTlsCertificateFile(resolveTlsCertificateFile())
					.withLogger(this::log)
					.resolve();
		} catch (final Exception e) {
			throw new BuildException("GetMavenDependencyTask: could not resolve dependency '" + resolveDisplayName()
					+ "': " + e.getMessage(), e);
		}
	}

	/**
	 * Mirrors DependencyResolver's name/artifactId fallback, for error messages only.
	 *
	 * @return the name if set, else the artifactId
	 */
	private String resolveDisplayName() {
		return name != null ? name : artifactId;
	}

	/**
	 * Resolves libDir against the project's basedir when given as a relative path.
	 *
	 * @return the absolute target directory, "&lt;project dir&gt;/lib" if libDir is not set
	 */
	private java.nio.file.Path resolveLibDir() {
		if (libDir != null) {
			final java.nio.file.Path path = java.nio.file.Paths.get(libDir);
			return path.isAbsolute() ? path : getProject().getBaseDir().toPath().resolve(path);
		} else {
			return getProject().getBaseDir().toPath().resolve("lib");
		}
	}

	/**
	 * Resolves tlsCertificateFile against the project's basedir when given as a relative path.
	 *
	 * @return the resolved path, or null if not set
	 */
	private String resolveTlsCertificateFile() {
		if (tlsCertificateFile == null) {
			return null;
		}
		final java.nio.file.Path path = java.nio.file.Paths.get(tlsCertificateFile);
		final java.nio.file.Path resolved = path.isAbsolute() ? path : getProject().getBaseDir().toPath().resolve(path);
		return resolved.toString();
	}
}
