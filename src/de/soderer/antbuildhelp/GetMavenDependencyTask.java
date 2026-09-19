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
 * <getMavenDependency repositoryUrl="https://soderer.de/maven2" groupId="de.soderer"
 *                     artifactId="soderer-utilities" version="26.2.53" />
 * <getMavenDependency repositoryUrl="https://soderer.de/maven2" groupId="de.soderer"
 *                     artifactId="soderer-utilities" version="26.2.53" classifier="sources" />
 * }</pre>
 */
public class GetMavenDependencyTask extends Task {

	private String groupId;
	private String artifactId;
	private String version;
	private String classifier;
	private String repositoryUrl;
	private String name;
	private String libDir; // default: "<project dir>/lib"
	private String repositoryRoot;
	private String proxyUrl;
	private String pacUrl;
	private boolean useWpad;
	private String tlsCertificateFile;

	/** Defaults to "de.soderer" (see {@link DependencyResolver#setGroupId}) - set to the artifact's real Maven groupId. */
	public void setGroupId(final String groupId) {
		this.groupId = groupId;
	}

	public void setArtifactId(final String artifactId) {
		this.artifactId = artifactId;
	}

	/** May be "RELEASE" or "LATEST" (case-insensitive) to resolve via the repository's maven-metadata.xml. */
	public void setVersion(final String version) {
		this.version = version;
	}

	/** The standard Maven classifier, e.g. "sources" or "javadoc". */
	public void setClassifier(final String classifier) {
		this.classifier = classifier;
	}

	/**
	 * The Maven-layout repository base url, e.g. a private mirror such as
	 * "https://soderer.de/maven2". Defaults to Maven Central (repo1.maven.org) if not set.
	 */
	public void setRepositoryUrl(final String repositoryUrl) {
		this.repositoryUrl = repositoryUrl;
	}

	/** Local/logical dependency name for the local repository cache; defaults to artifactId (plus "-classifier") if not set. */
	public void setName(final String name) {
		this.name = name;
	}

	public void setLibDir(final String libDir) {
		this.libDir = libDir;
	}

	public void setRepositoryRoot(final String repositoryRoot) {
		this.repositoryRoot = repositoryRoot;
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

	public void setTlsCertificateFile(final String tlsCertificateFile) {
		this.tlsCertificateFile = tlsCertificateFile;
	}

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
			throw new BuildException("GetMavenDependencyTask: could not resolve dependency '" + resolveDisplayName() + "'", e);
		}
	}

	/** Mirrors DependencyResolver's name/artifactId fallback, for error messages only. */
	private String resolveDisplayName() {
		return name != null ? name : artifactId;
	}

	private java.nio.file.Path resolveLibDir() {
		if (libDir != null) {
			final java.nio.file.Path path = java.nio.file.Paths.get(libDir);
			return path.isAbsolute() ? path : getProject().getBaseDir().toPath().resolve(path);
		} else {
			return getProject().getBaseDir().toPath().resolve("lib");
		}
	}

	/** Resolves tlsCertificateFile against the project's basedir when given as a relative path. */
	private String resolveTlsCertificateFile() {
		if (tlsCertificateFile == null) {
			return null;
		}
		final java.nio.file.Path path = java.nio.file.Paths.get(tlsCertificateFile);
		final java.nio.file.Path resolved = path.isAbsolute() ? path : getProject().getBaseDir().toPath().resolve(path);
		return resolved.toString();
	}
}
