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
 *                url="https://www.some-repository.com/index.php?download=RestClient.jar"
 *                useWpad="true"
 *                tlsCertificateFile="zscaler-root.cer" />
 *
 * <getDependency name="swt-win"
 *                version="4.38-win32-win32-x86_64"
 *                groupId="org.eclipse.swt"
 *                url="https://archive.eclipse.org/eclipse/downloads/drops4/R-4.38-202512010920/swt-4.38-win32-win32-x86_64.zip"
 *                zipEntry="swt.jar" />
 * <!-- useDownloadFileName (default true) here names the libDir file after the zip itself,
 *      "swt-4.38-win32-win32-x86_64.jar" - not "swt.jar", the extracted entry's own name,
 *      which would collide with the Linux download below extracting the very same entry
 *      name. Pass useDownloadFileName="false" to instead get "swt-win-4.38...jar"
 *      (<name>-<version>.jar). -->
 *
 * <!-- Maven artifact mode: setting artifactId derives the download URL from
 *      repo1.maven.org (groupId/artifactId/version) when url is not given, and verifies
 *      the download against the strongest available checksum (.sha512/.sha256/.sha1/.md5). -->
 * <getDependency url="https://repo1.maven.org/maven2" groupId="com.sun.mail" artifactId="mailapi" version="2.0.1" />
 * or
 * <getDependency url="https://repo1.maven.org/maven2" groupId="com.sun.mail" artifactId="mailapi" version="RELEASE" />
 * or
 * <getDependency url="https://some-repository.com/maven2" groupId="de.soderer" artifactId="csv" version="26.1.1" />
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
 *                url="https://www.some-repository.com/index.php?download=csv.jar"
 *                useDownloadFileName="true" />
 * }</pre>
 */
public class GetDependencyTask extends Task {

	/** Value of the "name" attribute */
	private String name;

	/** Value of the "version" attribute */
	private String version;

	/** Value of the "url" attribute */
	private String url;

	/** Value of the "artifactId" attribute */
	private String artifactId;

	/** Value of the "classifier" attribute */
	private String classifier;

	/** Value of the "groupId" attribute */
	private String groupId;

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

	/** Value of the "zipEntry" attribute */
	private String zipEntry;

	/** Value of the "useDownloadFileName" attribute, null if not set by the build.xml author, then DependencyResolver.DEFAULT_USE_DOWNLOAD_FILE_NAME applies */
	private Boolean useDownloadFileName;

	/**
	 * Creates the task; Ant configures it via the attribute setters afterwards.
	 */
	public GetDependencyTask() {
		// Configured by Ant via the setters
	} // null = not set by the build.xml author, inherit DependencyResolver.DEFAULT_USE_DOWNLOAD_FILE_NAME

	/**
	 * Sets the local/logical dependency name, used for the local repository cache path and, in the
	 * default mode, for the libDir target filename {@code <name>-<version>.jar}. Optional in Maven
	 * artifact mode, where it defaults to artifactId (plus "-classifier").
	 *
	 * @param name the dependency name
	 */
	public void setName(final String name) {
		this.name = name;
	}

	/**
	 * Sets the dependency version. May be "RELEASE" or "LATEST" (case-insensitive) in Maven
	 * artifact mode, or together with a github.com releases-download url, to resolve the actual
	 * version first. Required.
	 *
	 * @param version the version or version keyword
	 */
	public void setVersion(final String version) {
		this.version = version;
	}

	/**
	 * Sets the download url. In the default mode this is the full download url, optionally
	 * containing a "{version}" placeholder. In Maven artifact mode (see
	 * {@link #setArtifactId(String)}) it is the repository base url instead, defaulting to Maven
	 * Central if not set.
	 *
	 * @param url the download url or repository base url
	 */
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
	 *
	 * @param artifactId the Maven artifactId
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
	 *
	 * @param classifier the Maven classifier
	 */
	public void setClassifier(final String classifier) {
		this.classifier = classifier;
	}

	/**
	 * Sets the Maven groupId. Defaults to "de.soderer" (see
	 * {@link DependencyResolver#setGroupId(String)}); in Maven artifact mode it must be set to the
	 * artifact's real groupId.
	 *
	 * @param groupId the groupId
	 */
	public void setGroupId(final String groupId) {
		this.groupId = groupId;
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
	 * Sets the path of an entry to extract from the download, which is then treated as zip
	 * archive (e.g. "swt.jar" out of an Eclipse SWT distribution zip).
	 *
	 * @param zipEntry the entry path inside the zip archive
	 */
	public void setZipEntry(final String zipEntry) {
		this.zipEntry = zipEntry;
	}

	/**
	 * Default mode only (no artifactId): defaults to {@link
	 * DependencyResolver#DEFAULT_USE_DOWNLOAD_FILE_NAME} (true) if not set. If true, the libDir
	 * target filename is taken from the download itself instead of the usual
	 * {@code <name>-<version>.jar} - preferring the server's Content-Disposition response header,
	 * else the last path segment of {@code url} if it ends in ".jar"/".zip", else falling back to
	 * {@code <name>-<version>.jar}. With zipEntry set, the name is taken from the downloaded zip
	 * archive itself (not the extracted entry, which may be identically named across several
	 * platform-specific downloads), with a trailing ".zip" swapped for ".jar". Set to false to
	 * always use {@code <name>-<version>.jar}.
	 *
	 * @param useDownloadFileName true to name the target file after the download
	 */
	public void setUseDownloadFileName(final boolean useDownloadFileName) {
		this.useDownloadFileName = useDownloadFileName;
	}

	/**
	 * Resolves the dependency: downloads it into the local repository cache if not already
	 * present, then copies it into libDir, removing older versions there.
	 *
	 * @throws BuildException if the dependency cannot be resolved
	 */
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
