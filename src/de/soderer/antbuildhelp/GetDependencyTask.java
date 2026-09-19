package de.soderer.antbuildhelp;

import org.apache.tools.ant.BuildException;
import org.apache.tools.ant.Task;

/**
 * Thin Ant task wrapper around {@link DependencyResolver}: downloads a single dependency jar
 * and places it into the project's local lib directory, using a Maven-layout-compatible local
 * repository as cache.
 *
 * The actual resolution logic (download, cache, proxy/PAC/WPAD, TLS trust, zip extraction) lives
 * in DependencyResolver, which has no dependency on org.apache.tools.ant.* and is reused as-is
 * by {@link AntBuildHelpMain} for the standalone CLI entry point of antbuildhelp.jar.
 *
 * Example usage in a build.xml:
 *
 * <pre>{@code
 * <typedef resource="de/soderer/antbuildhelp/antlib.xml" classpath="lib_build/antbuildhelp.jar" />
 *
 * <getDependency name="RestClient"
 *                version="26.0.82"
 *                url="https://www.soderer.de/index.php?download=RestClient.jar"
 *                useWpad="true"
 *                tlsCertificateFile="zscaler-root.cer" />
 *
 * <getDependency name="swt-win"
 *                version="4.38-win32-win32-x86_64"
 *                groupId="org.eclipse.swt"
 *                url="https://archive.eclipse.org/eclipse/downloads/drops4/R-4.38-202512010920/swt-4.38-win32-win32-x86_64.zip"
 *                zipEntry="swt.jar" />
 *
 * <!-- Maven artifact mode: setting artifactId derives the download URL from
 *      repo1.maven.org (groupId/artifactId/version) when url is not given, and verifies
 *      the download against the strongest available checksum (.sha512/.sha256/.sha1/.md5). -->
 * <getDependency url="https://repo1.maven.org/maven2" groupId="com.sun.mail" artifactId="mailapi" version="2.0.1" />
 * or
 * <getDependency url="https://repo1.maven.org/maven2" groupId="com.sun.mail" artifactId="mailapi" version="RELEASE" />
 * or
 * <getDependency url="http://soderer.de/maven2" groupId="de.soderer" artifactId="csv" version="26.1.1" />
 *
 * <!-- classifier: downloads the "sources" jar alongside the regular one -->
 * <getDependency groupId="de.soderer" artifactId="JavaUtilities" version="26.2.51" classifier="sources" />
 *
 * <!-- GitHub releases: a "{version}" placeholder in url is substituted with version; if url is
 *      a github.com releases-download url and version is RELEASE/LATEST, the actual tag is
 *      resolved via GitHub's API first. Checksum verification applies here too, if the release
 *      provides a matching .sha512/.sha256/.sha1/.md5 asset. -->
 * <getDependency name="somelib"
 *                version="RELEASE"
 *                url="https://github.com/someowner/somelib/releases/download/v{version}/somelib-{version}.jar" />
 *
 * <!-- useDownloadFileName defaults to true, so this is the default behavior, shown here
 *      explicitly: names the libDir file as the download itself specifies (here via
 *      Content-Disposition, since the query-string url gives no filename of its own) instead
 *      of "csv-26.1.1.jar". Set useDownloadFileName="false" to opt out and always get
 *      <name>-<version>.jar. -->
 * <getDependency name="csv"
 *                version="26.1.1"
 *                url="https://www.soderer.de/index.php?download=csv.jar"
 *                useDownloadFileName="true" />
 * }</pre>
 */
public class GetDependencyTask extends Task {

	private String name;
	private String version;
	private String url;
	private String artifactId;
	private String classifier;
	private String groupId;
	private String libDir; // default: "<project dir>/lib"
	private String repositoryRoot;
	private String proxyUrl;
	private String pacUrl;
	private boolean useWpad;
	private String tlsCertificateFile;
	private String zipEntry;
	private Boolean useDownloadFileName; // null = not set by the build.xml author, inherit DependencyResolver.DEFAULT_USE_DOWNLOAD_FILE_NAME

	public void setName(final String name) {
		this.name = name;
	}

	public void setVersion(final String version) {
		this.version = version;
	}

	public void setUrl(final String url) {
		this.url = url;
	}

	/**
	 * Setting this switches into Maven artifact mode: {@code url}, if given, is treated as the
	 * repository base url (e.g. a private Maven-layout mirror) instead of the full download url
	 * - the full artifact path (groupId/artifactId/version/artifactId-version.jar) is always
	 * derived and appended; leave {@code url} unset to use Maven Central (repo1.maven.org). If
	 * {@code name} is not explicitly set, it falls back to this artifactId. The download is
	 * verified against the strongest available checksum file (.sha512/.sha256/.sha1/.md5).
	 *
	 * Without artifactId, {@code url} may instead contain a "{version}" placeholder substituted
	 * with {@code version} - if it is a github.com releases-download url and version is
	 * RELEASE/LATEST, the actual tag is resolved via GitHub's API first, and the download is
	 * checksum-verified the same way, if a matching checksum asset exists.
	 */
	public void setArtifactId(final String artifactId) {
		this.artifactId = artifactId;
	}

	/**
	 * Maven artifact mode only: the standard Maven classifier, e.g. "sources" or "javadoc" -
	 * appended to both the download filename and the libDir target filename as
	 * {@code <artifactId>-<version>-<classifier>.jar}. If {@code name} is not explicitly set, it
	 * then defaults to {@code <artifactId>-<classifier>} instead of just {@code artifactId} -
	 * this only affects the internal local repository cache path (so it doesn't collide with the
	 * unclassified artifact's cache entry), not the libDir filename.
	 */
	public void setClassifier(final String classifier) {
		this.classifier = classifier;
	}

	public void setGroupId(final String groupId) {
		this.groupId = groupId;
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

	public void setZipEntry(final String zipEntry) {
		this.zipEntry = zipEntry;
	}

	/**
	 * Default mode only (no artifactId), and only without zipEntry: defaults to {@link
	 * DependencyResolver#DEFAULT_USE_DOWNLOAD_FILE_NAME} (true) if not set. If true, the libDir
	 * target filename is taken from the download itself instead of the usual
	 * {@code <name>-<version>.jar} - preferring the server's Content-Disposition response header,
	 * else the last path segment of {@code url} if it ends in ".jar"/".zip", else falling back to
	 * {@code <name>-<version>.jar}. Set to false to always use {@code <name>-<version>.jar}.
	 */
	public void setUseDownloadFileName(final boolean useDownloadFileName) {
		this.useDownloadFileName = useDownloadFileName;
	}

	@Override
	public void execute() throws BuildException {
		try {
			new DependencyResolver()
					.withName(name)
					.withVersion(version)
					.withUrl(url)
					.withArtifactId(artifactId)
					.withClassifier(classifier)
					.withGroupId(groupId)
					.withLibDir(resolveLibDir())
					.withRepositoryRoot(repositoryRoot)
					.withProxyUrl(proxyUrl)
					.withPacUrl(pacUrl)
					.withUseWpad(useWpad)
					.withTlsCertificateFile(resolveTlsCertificateFile())
					.withZipEntry(zipEntry)
					.withUseDownloadFileName(useDownloadFileName != null ? useDownloadFileName : DependencyResolver.DEFAULT_USE_DOWNLOAD_FILE_NAME)
					.withLogger(this::log)
					.resolve();
		} catch (final Exception e) {
			throw new BuildException("GetDependencyTask: could not resolve dependency '" + resolveDisplayName()
					+ "': " + e.getMessage(), e);
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
