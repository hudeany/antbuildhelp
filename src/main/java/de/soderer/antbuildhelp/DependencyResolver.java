package de.soderer.antbuildhelp;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import de.soderer.antbuildhelp.config.ProxyConfig;
import de.soderer.antbuildhelp.download.HttpClientFactory;

/**
 * Core dependency-resolution logic, independent of Ant: downloads a single dependency jar
 * into a local Maven-layout-compatible repository cache, then copies it into a target lib
 * directory, removing any older version of the same dependency found there first.
 *
 * Deliberately has no dependency on org.apache.tools.ant.* so it can be used both from
 * {@link GetDependencyTask} (the Ant task) and from {@link AntBuildHelpMain} (the standalone
 * CLI entry point of antbuildhelp.jar), without the CLI needing ant.jar on its runtime
 * classpath.
 *
 * Follows the setX()/withX() fluent convention: setters stay void (bean-style), withX()
 * additionally returns this for chained construction.
 */
public class DependencyResolver {

	/**
	 * Default for {@link #setUseDownloadFileName}. True: callers (Ant task, CLI) that don't set
	 * useDownloadFileName explicitly get this default too - the single source of truth for it.
	 */
	public static final boolean DEFAULT_USE_DOWNLOAD_FILE_NAME = true;

	/** Repository base url used in Maven artifact mode if no url is given */
	private static final String MAVEN_CENTRAL_BASE_URL = "https://repo1.maven.org/maven2/";

	/** Matches Maven's group/artifact-level metadata.xml &lt;release&gt;/&lt;latest&gt; tags, used to resolve version="RELEASE"/"LATEST". */
	private static final Pattern RELEASE_VERSION_TAG_PATTERN = Pattern.compile("<release>\\s*([^<\\s]+)\\s*</release>");
	/** Matches Maven's metadata.xml &lt;latest&gt; tag, used to resolve version="LATEST". */
	private static final Pattern LATEST_VERSION_TAG_PATTERN = Pattern.compile("<latest>\\s*([^<\\s]+)\\s*</latest>");

	/** Matches a GitHub releases-download url, capturing owner/repo, e.g. github.com/&lt;owner&gt;/&lt;repo&gt;/releases/download/... */
	private static final Pattern GITHUB_RELEASE_URL_PATTERN =
			Pattern.compile("^https?://github\\.com/([^/]+)/([^/]+)/releases/download/");
	/** Matches the "tag_name" property of a GitHub API release response */
	private static final Pattern GITHUB_TAG_NAME_JSON_PATTERN = Pattern.compile("\"tag_name\"\\s*:\\s*\"([^\"]+)\"");

	/** Checksum file extensions to try against the download URL, strongest algorithm first. */
	private static final Map<String, String> CHECKSUM_EXTENSION_TO_DIGEST_ALGORITHM = new LinkedHashMap<>();
	static {
		CHECKSUM_EXTENSION_TO_DIGEST_ALGORITHM.put("sha512", "SHA-512");
		CHECKSUM_EXTENSION_TO_DIGEST_ALGORITHM.put("sha256", "SHA-256");
		CHECKSUM_EXTENSION_TO_DIGEST_ALGORITHM.put("sha1", "SHA-1");
		CHECKSUM_EXTENSION_TO_DIGEST_ALGORITHM.put("md5", "MD5");
	}

	/** Local/logical dependency name, used for the local repository cache path */
	private String name;

	/** Dependency version, or "RELEASE"/"LATEST" until resolved */
	private String version;

	/** Full download url (default mode) or repository base url (Maven artifact mode) */
	private String url;

	/** If set: Maven artifact mode - 'url' (if set) becomes the repository base url, 'name' falls back to this, checksum gets verified */
	private String artifactId;

	/** Maven artifact mode only: e.g. "sources"/"javadoc" - appended to the download filename */
	private String classifier;

	/** Maven groupId, also used for the local repository cache path */
	private String groupId = "de.soderer";

	/** Resolved absolute (or caller-relative) target directory; caller decides the default */
	private Path libDir;

	/** Root directory of the local repository cache */
	private String repositoryRoot = System.getProperty("user.home") + "/.m2/repository";

	/** Direct proxy url */
	private String proxyUrl;

	/** PAC script url */
	private String pacUrl;

	/** Whether to auto-detect the PAC script via WPAD */
	private boolean useWpad;

	/** Additionally trusted certificate file, or null for the JVM's default CAs only */
	private String tlsCertificateFile;

	/** Optional: path of the jar entry to extract from a downloaded zip archive */
	private String zipEntry;

	/** Default mode only (no artifactId): name the libDir target file as the download itself specifies */
	private boolean useDownloadFileName = DEFAULT_USE_DOWNLOAD_FILE_NAME;

	/** Set from a fresh download's Content-Disposition header, if useDownloadFileName and one was sent */
	private String resolvedDownloadFileName;

	/** Receiver of progress/status lines */
	private Consumer<String> logger = message -> { /* no-op by default */ };

	/**
	 * Creates a resolver, to be configured via the setters or withX() methods before calling
	 * {@link #resolve()}.
	 */
	public DependencyResolver() {
		// Configured via the setters
	}

	/**
	 * Returns the local/logical dependency name.
	 *
	 * @return the local/logical dependency name (after {@link #resolve()} also the artifactId fallback), or null if not set
	 */
	public String getName() {
		return name;
	}

	/**
	 * Sets the local/logical dependency name, used for the local repository cache path and, in
	 * the default mode, for the libDir target filename {@code <name>-<version>.jar}. Optional in
	 * Maven artifact mode, where it defaults to artifactId (plus "-classifier").
	 *
	 * @param name the local/logical dependency name
	 */
	public void setName(final String name) {
		this.name = name;
	}

	/**
	 * Fluent variant of {@link #setName(String)}.
	 *
	 * @param nameParam the local/logical dependency name
	 * @return this resolver, for method chaining
	 */
	public DependencyResolver withName(final String nameParam) {
		setName(nameParam);
		return this;
	}

	/**
	 * Returns the dependency version.
	 *
	 * @return the dependency version (after {@link #resolve()} the resolved
	 *         version, if a version keyword was given), or null if not set
	 */
	public String getVersion() {
		return version;
	}

	/**
	 * May be "RELEASE" or "LATEST" (case-insensitive) instead of an explicit version number, to
	 * resolve the actual version before use: via maven-metadata.xml in Maven artifact mode (see
	 * {@link #setArtifactId}), or via GitHub's "latest release" API when {@link #url} is a
	 * GitHub releases-download url. See {@link #setUrl} for the "{version}" placeholder.
	 *
	 * @param version the dependency version
	 */
	public void setVersion(final String version) {
		this.version = version;
	}

	/**
	 * Fluent variant of {@link #setVersion(String)}.
	 *
	 * @param versionParam the dependency version
	 * @return this resolver, for method chaining
	 */
	public DependencyResolver withVersion(final String versionParam) {
		setVersion(versionParam);
		return this;
	}

	/**
	 * Returns the download url.
	 *
	 * @return the download url (default mode) or repository base url (Maven artifact mode) (after
	 *         {@link #resolve()} the full, resolved download url), or null if not set
	 */
	public String getUrl() {
		return url;
	}

	/**
	 * The download url. In the default mode this must be the full, literal download url, and may
	 * contain a "{version}" placeholder that gets substituted with {@link #version} before use
	 * (e.g. for a GitHub releases-download url: {@code
	 * https://github.com/<owner>/<repo>/releases/download/v{version}/asset-{version}.jar} - if
	 * this is a github.com releases-download url and version is "RELEASE"/"LATEST", it is
	 * resolved via GitHub's "latest release" API before substitution, and the download is then
	 * verified against a checksum file next to it if one exists, same as in Maven artifact mode).
	 * In Maven artifact mode (see {@link #setArtifactId}), this is instead treated as the
	 * repository BASE url (e.g. a private Maven-layout mirror) - the full artifact path is
	 * always derived and appended; leave unset to use Maven Central (repo1.maven.org).
	 *
	 * @param url the download url (default mode) or repository base url (Maven artifact mode)
	 */
	public void setUrl(final String url) {
		this.url = url;
	}

	/**
	 * Fluent variant of {@link #setUrl(String)}.
	 *
	 * @param urlParam the download url (default mode) or repository base url (Maven artifact mode)
	 * @return this resolver, for method chaining
	 */
	public DependencyResolver withUrl(final String urlParam) {
		setUrl(urlParam);
		return this;
	}

	/**
	 * Returns the Maven artifactId.
	 *
	 * @return the Maven artifactId, or null if not set (default mode)
	 */
	public String getArtifactId() {
		return artifactId;
	}

	/**
	 * Setting this switches the dependency into Maven artifact mode:
	 * <ul>
	 * <li>{@link #url}, if set, is treated as the repository base url (e.g. a private
	 * Maven-layout mirror) instead of the full download url; if unset, Maven Central
	 * (repo1.maven.org) is used as the base. Either way, the full artifact path
	 * (groupId/artifactId/version/artifactId-version[-classifier].jar) is always derived and
	 * appended.</li>
	 * <li>{@link #name}, if not explicitly set, falls back to this artifactId (plus
	 * "-classifier" if {@link #classifier} is set). This affects only the internal local
	 * repository cache path, not the libDir target filename, which always uses Maven order
	 * (artifactId-version[-classifier].jar) in this mode regardless of name.</li>
	 * <li>{@link #version} may be "RELEASE" or "LATEST" (case-insensitive); it is resolved to
	 * the actual version number via the repository's maven-metadata.xml before use, so cache
	 * path, target filename and checksum all use the real version, not the literal keyword.</li>
	 * <li>the download is verified against the strongest available checksum file
	 * (.sha512/.sha256/.sha1/.md5, in that order).</li>
	 * </ul>
	 *
	 * @param artifactId the Maven artifactId
	 */
	public void setArtifactId(final String artifactId) {
		this.artifactId = artifactId;
	}

	/**
	 * Fluent variant of {@link #setArtifactId(String)}.
	 *
	 * @param artifactIdParam the Maven artifactId
	 * @return this resolver, for method chaining
	 */
	public DependencyResolver withArtifactId(final String artifactIdParam) {
		setArtifactId(artifactIdParam);
		return this;
	}

	/**
	 * Returns the Maven classifier.
	 *
	 * @return the Maven classifier, e.g. "sources" or "javadoc", or null if not set
	 */
	public String getClassifier() {
		return classifier;
	}

	/**
	 * Maven artifact mode only (see {@link #setArtifactId}): the standard Maven classifier,
	 * e.g. "sources" or "javadoc" - appended to both the download filename and the libDir target
	 * filename as {@code <artifactId>-<version>-<classifier>.jar}. If {@link #name} is not
	 * explicitly set, it then defaults to {@code <artifactId>-<classifier>} instead of just
	 * {@code artifactId} - this only affects the internal local repository cache path (so the
	 * classified artifact gets its own cache entry instead of colliding with the unclassified
	 * one), not the libDir filename. Does not affect the group/artifact-level maven-metadata.xml
	 * lookup used to resolve version="RELEASE"/"LATEST", which is always based on
	 * groupId/artifactId.
	 *
	 * @param classifier the Maven classifier, e.g. "sources" or "javadoc"
	 */
	public void setClassifier(final String classifier) {
		this.classifier = classifier;
	}

	/**
	 * Fluent variant of {@link #setClassifier(String)}.
	 *
	 * @param classifierParam the Maven classifier, e.g. "sources" or "javadoc"
	 * @return this resolver, for method chaining
	 */
	public DependencyResolver withClassifier(final String classifierParam) {
		setClassifier(classifierParam);
		return this;
	}

	/**
	 * Returns the Maven groupId.
	 *
	 * @return the Maven groupId, "de.soderer" by default
	 */
	public String getGroupId() {
		return groupId;
	}

	/**
	 * Defaults to "de.soderer". In Maven artifact mode (see {@link #setArtifactId}) this must be
	 * set to the artifact's real Maven groupId, or resolution against the repository base url
	 * will fail (typically HTTP 404).
	 *
	 * @param groupId the Maven groupId
	 */
	public void setGroupId(final String groupId) {
		if (groupId != null) {
			this.groupId = groupId;
		}
	}

	/**
	 * Fluent variant of {@link #setGroupId(String)}.
	 *
	 * @param groupIdParam the Maven groupId
	 * @return this resolver, for method chaining
	 */
	public DependencyResolver withGroupId(final String groupIdParam) {
		setGroupId(groupIdParam);
		return this;
	}

	/**
	 * Returns the target directory the jar is copied into.
	 *
	 * @return the target directory the jar is copied into, or null if not set
	 */
	public Path getLibDir() {
		return libDir;
	}

	/**
	 * Sets the target directory the jar is copied into.
	 *
	 * @param libDir the target directory the jar is copied into
	 */
	public void setLibDir(final Path libDir) {
		this.libDir = libDir;
	}

	/**
	 * Fluent variant of {@link #setLibDir(Path)}.
	 *
	 * @param libDirParam the target directory the jar is copied into
	 * @return this resolver, for method chaining
	 */
	public DependencyResolver withLibDir(final Path libDirParam) {
		setLibDir(libDirParam);
		return this;
	}

	/**
	 * Returns the root directory of the local repository cache.
	 *
	 * @return the root directory of the local repository cache, "~/.m2/repository" by default
	 */
	public String getRepositoryRoot() {
		return repositoryRoot;
	}

	/**
	 * Sets the root directory of the local repository cache.
	 *
	 * @param repositoryRoot the root directory of the local repository cache
	 */
	public void setRepositoryRoot(final String repositoryRoot) {
		if (repositoryRoot != null) {
			this.repositoryRoot = repositoryRoot;
		}
	}

	/**
	 * Fluent variant of {@link #setRepositoryRoot(String)}.
	 *
	 * @param repositoryRootParam the root directory of the local repository cache
	 * @return this resolver, for method chaining
	 */
	public DependencyResolver withRepositoryRoot(final String repositoryRootParam) {
		setRepositoryRoot(repositoryRootParam);
		return this;
	}

	/**
	 * Returns the direct proxy url.
	 *
	 * @return the direct proxy url, e.g. "http://proxy.example.com:8080", or null if not set
	 */
	public String getProxyUrl() {
		return proxyUrl;
	}

	/**
	 * Sets the direct proxy url, e.g. "http://proxy.example.com:8080".
	 *
	 * @param proxyUrl the direct proxy url, e.g. "http://proxy.example.com:8080"
	 */
	public void setProxyUrl(final String proxyUrl) {
		this.proxyUrl = proxyUrl;
	}

	/**
	 * Fluent variant of {@link #setProxyUrl(String)}.
	 *
	 * @param proxyUrlParam the direct proxy url, e.g. "http://proxy.example.com:8080"
	 * @return this resolver, for method chaining
	 */
	public DependencyResolver withProxyUrl(final String proxyUrlParam) {
		setProxyUrl(proxyUrlParam);
		return this;
	}

	/**
	 * Returns the url of a PAC script that decides the proxy per request.
	 *
	 * @return the url of a PAC script that decides the proxy per request, or null if not set
	 */
	public String getPacUrl() {
		return pacUrl;
	}

	/**
	 * Sets the url of a PAC script that decides the proxy per request.
	 *
	 * @param pacUrl the url of a PAC script that decides the proxy per request
	 */
	public void setPacUrl(final String pacUrl) {
		this.pacUrl = pacUrl;
	}

	/**
	 * Fluent variant of {@link #setPacUrl(String)}.
	 *
	 * @param pacUrlParam the url of a PAC script that decides the proxy per request
	 * @return this resolver, for method chaining
	 */
	public DependencyResolver withPacUrl(final String pacUrlParam) {
		setPacUrl(pacUrlParam);
		return this;
	}

	/**
	 * Returns the whether the PAC script is auto-detected via WPAD.
	 *
	 * @return the whether the PAC script is auto-detected via WPAD
	 */
	public boolean isUseWpad() {
		return useWpad;
	}

	/**
	 * Sets the whether the PAC script is auto-detected via WPAD.
	 *
	 * @param useWpad the whether the PAC script is auto-detected via WPAD
	 */
	public void setUseWpad(final boolean useWpad) {
		this.useWpad = useWpad;
	}

	/**
	 * Fluent variant of {@link #setUseWpad(boolean)}.
	 *
	 * @param useWpadParam the whether the PAC script is auto-detected via WPAD
	 * @return this resolver, for method chaining
	 */
	public DependencyResolver withUseWpad(final boolean useWpadParam) {
		setUseWpad(useWpadParam);
		return this;
	}

	/**
	 * Returns the path of an additionally trusted X.509 certificate file (PEM or DER).
	 *
	 * @return the path of an additionally trusted X.509 certificate file (PEM or DER), or null if not set
	 */
	public String getTlsCertificateFile() {
		return tlsCertificateFile;
	}

	/**
	 * Sets the path of an additionally trusted X.509 certificate file (PEM or DER).
	 *
	 * @param tlsCertificateFile the path of an additionally trusted X.509 certificate file (PEM or DER)
	 */
	public void setTlsCertificateFile(final String tlsCertificateFile) {
		this.tlsCertificateFile = tlsCertificateFile;
	}

	/**
	 * Fluent variant of {@link #setTlsCertificateFile(String)}.
	 *
	 * @param tlsCertificateFileParam the path of an additionally trusted X.509 certificate file (PEM or DER)
	 * @return this resolver, for method chaining
	 */
	public DependencyResolver withTlsCertificateFile(final String tlsCertificateFileParam) {
		setTlsCertificateFile(tlsCertificateFileParam);
		return this;
	}

	/**
	 * Returns the path of the entry to extract from a downloaded zip archive.
	 *
	 * @return the path of the entry to extract from a downloaded zip archive, or null if not set
	 */
	public String getZipEntry() {
		return zipEntry;
	}

	/**
	 * Sets the path of the entry to extract from a downloaded zip archive.
	 *
	 * @param zipEntry the path of the entry to extract from a downloaded zip archive
	 */
	public void setZipEntry(final String zipEntry) {
		this.zipEntry = zipEntry;
	}

	/**
	 * Fluent variant of {@link #setZipEntry(String)}.
	 *
	 * @param zipEntryParam the path of the entry to extract from a downloaded zip archive
	 * @return this resolver, for method chaining
	 */
	public DependencyResolver withZipEntry(final String zipEntryParam) {
		setZipEntry(zipEntryParam);
		return this;
	}

	/**
	 * Returns the whether the libDir target filename is taken from the download itself.
	 *
	 * @return the whether the libDir target filename is taken from the download itself
	 */
	public boolean isUseDownloadFileName() {
		return useDownloadFileName;
	}

	/**
	 * Default mode only (no {@link #setArtifactId}): defaults to {@link
	 * #DEFAULT_USE_DOWNLOAD_FILE_NAME} (true). If true, the libDir target filename is taken from
	 * the download itself instead of the usual {@code <name>-<version>.jar} - preferring the
	 * server's {@code Content-Disposition} response header if one is sent, else the last path
	 * segment of {@link #url} if it ends in ".jar" or ".zip" (query string ignored), else falling
	 * back to {@code <name>-<version>.jar} if neither yields anything usable - so with url
	 * patterns like {@code .../index.php?download=x.jar} that give no real filename either way,
	 * this setting changes nothing in practice. With {@link #setZipEntry} set, the name is taken
	 * from the downloaded zip archive itself (not the extracted entry, which is often identically
	 * named across multiple platform-specific downloads, e.g. SWT's "swt.jar"), with a trailing
	 * ".zip" swapped for ".jar" (see {@link #deriveExtractedJarFileName}). Note that when the
	 * repository cache already has the file from a previous run, no request is made this run
	 * (see {@link #resolve}), so only the url-based fallback is available then - the
	 * Content-Disposition-derived name from a past run is not remembered. Also note {@link
	 * #removeOldVersions} still globs old candidates by {@code name + "-*.jar"} - with a
	 * download-derived filename that doesn't happen to start with "name-", old versions won't be
	 * found and cleaned up automatically (a stale extra jar, not a wrongly deleted one). Set to
	 * false to always use {@code <name>-<version>.jar} regardless of what the download itself
	 * suggests.
	 *
	 * @param useDownloadFileName the whether the libDir target filename is taken from the download itself
	 */
	public void setUseDownloadFileName(final boolean useDownloadFileName) {
		this.useDownloadFileName = useDownloadFileName;
	}

	/**
	 * Fluent variant of {@link #setUseDownloadFileName(boolean)}.
	 *
	 * @param useDownloadFileNameParam the whether the libDir target filename is taken from the download itself
	 * @return this resolver, for method chaining
	 */
	public DependencyResolver withUseDownloadFileName(final boolean useDownloadFileNameParam) {
		setUseDownloadFileName(useDownloadFileNameParam);
		return this;
	}

	/**
	 * Sets where progress/status lines are sent (e.g. Ant's Task#log, or System.out::println for
	 * the CLI). A null logger is ignored, the default discards all messages.
	 *
	 * @param logger the message consumer
	 */
	public void setLogger(final Consumer<String> logger) {
		if (logger != null) {
			this.logger = logger;
		}
	}

	/**
	 * Fluent variant of {@link #setLogger(Consumer)}.
	 *
	 * @param loggerParam the message consumer
	 * @return this resolver, for method chaining
	 */
	public DependencyResolver withLogger(final Consumer<String> loggerParam) {
		setLogger(loggerParam);
		return this;
	}

	/**
	 * Resolves this dependency: downloads it into the local repository cache if not already
	 * present, then copies it into libDir (removing older versions of the same name there
	 * first).
	 *
	 * An existing cache entry is trusted without verifying it again; downloads are only moved
	 * into the cache after a successful checksum verification (where applicable).
	 *
	 * Resolution updates name, version and url to their resolved values (artifactId fallback,
	 * resolved version keyword, full download url), so an instance is meant for a single call.
	 *
	 * @return the path of the jar inside libDir
	 * @throws Exception if the configuration is incomplete, the version cannot be resolved, the
	 *                   download or checksum verification fails, or the file operations fail
	 */
	public Path resolve() throws Exception {
		resolvedDownloadFileName = null;
		if (name == null && artifactId != null) {
			// Maven artifact mode: fall back to using artifactId as the local/logical name if not
			// explicitly set. This 'name' is used only for the internal repository cache path
			// (buildRepositoryJarPath()) - the libDir target filename is computed separately (see
			// buildTargetFileName()) and always uses Maven order (artifactId-version[-classifier].jar).
			// With a classifier set, 'name' gets it appended too, so the "sources" jar doesn't
			// collide with the unclassified jar's repository cache entry (both would otherwise
			// resolve to the same cache path and silently overwrite/reuse each other).
			name = classifier != null ? artifactId + "-" + classifier : artifactId;
		}
		if (name == null || version == null) {
			throw new IllegalStateException("DependencyResolver requires 'name' and 'version' to be set");
		}
		if (artifactId != null && isMetaVersionKeyword(version)) {
			// version="RELEASE"/"LATEST": resolve the real version number via the repository's
			// group/artifact-level maven-metadata.xml before building any paths/urls with it.
			final String repositoryBaseUrl = url != null ? url : MAVEN_CENTRAL_BASE_URL;
			final String resolvedVersion = resolveMavenMetaVersion(repositoryBaseUrl, version);
			logger.accept("Resolved version '" + version + "' of " + groupId + ":" + artifactId + " to " + resolvedVersion);
			version = resolvedVersion;
		}
		if (artifactId != null) {
			// Maven artifact mode: 'url', if given, is treated as the repository BASE url (e.g. a
			// private Maven-layout mirror such as "https://some-repository.com/maven2") - not the full
			// download url. The full artifact path is always derived and appended. If 'url' is
			// not given, Maven Central's repo1.maven.org is used as the default base.
			url = buildMavenArtifactUrl(url != null ? url : MAVEN_CENTRAL_BASE_URL);
		} else if (url != null) {
			// Default mode (no artifactId): 'url' is the full download url, optionally containing
			// a "{version}" placeholder. version="RELEASE"/"LATEST" against a GitHub
			// releases-download url is resolved via GitHub's "latest release" API first; then any
			// "{version}" placeholder in the url is substituted with the (possibly just resolved)
			// version.
			if (isMetaVersionKeyword(version) && isGithubReleaseUrl(url)) {
				final String resolvedVersion = resolveGithubLatestVersion(url, version);
				logger.accept("Resolved version '" + version + "' via GitHub releases (" + url + ") to " + resolvedVersion);
				version = resolvedVersion;
			}
			if (url.contains("{version}")) {
				url = url.replace("{version}", version);
			}
		}
		if (url == null) {
			throw new IllegalStateException("DependencyResolver requires either 'url', or 'artifactId' "
					+ "(Maven artifact mode, deriving the URL from groupId/artifactId/version) to be set");
		}
		if (libDir == null) {
			throw new IllegalStateException("DependencyResolver requires 'libDir' to be set");
		}

		final Path repositoryJarPath = buildRepositoryJarPath();
		if (!Files.isRegularFile(repositoryJarPath)) {
			logger.accept("Downloading " + name + " " + version + " from " + url + " ...");
			resolveIntoRepository(url, repositoryJarPath);
		} else {
			logger.accept(name + " " + version + " already present in local repository, skipping download");
		}

		Files.createDirectories(libDir);
		removeOldVersions(libDir);

		final Path targetJarPath = libDir.resolve(buildTargetFileName());
		if (!Files.isRegularFile(targetJarPath)) {
			// Copied via a temporary file, so an interrupted copy never leaves a partial jar that
			// would be taken as already present on the next run
			final Path tempTargetFile = Files.createTempFile(libDir, ".copy-", ".tmp");
			try {
				Files.copy(repositoryJarPath, tempTargetFile, StandardCopyOption.REPLACE_EXISTING);
				moveIntoPlace(tempTargetFile, targetJarPath);
			} finally {
				Files.deleteIfExists(tempTargetFile);
			}
			logger.accept("-> " + targetJarPath);
		} else {
			logger.accept(name + " " + version + " already present in " + libDir);
		}
		return targetJarPath;
	}

	/**
	 * The libDir target filename. In Maven artifact mode (see {@link #setArtifactId}) this uses
	 * standard Maven order, {@code <artifactId>-<version>[-<classifier>].jar}, independent of
	 * {@link #name} (which only affects the internal repository cache path). If {@link
	 * #setUseDownloadFileName} is enabled (default mode), the download-derived name is used
	 * instead when one could be determined - with {@link #zipEntry} set, that name is for the
	 * downloaded zip archive itself (not the extracted entry, which may be identically named
	 * across multiple platform-specific downloads, e.g. SWT's "swt.jar"), so it's run through
	 * {@link #deriveExtractedJarFileName} first (swapping a trailing ".zip" for ".jar"). Falls
	 * back to {@code <name>-<version>.jar} if useDownloadFileName is off or nothing usable could
	 * be determined.
	  *
	  * @return the filename of the jar in libDir
	 */
	private String buildTargetFileName() {
		if (artifactId != null) {
			final String classifierSuffix = classifier != null ? "-" + classifier : "";
			return artifactId + "-" + version + classifierSuffix + ".jar";
		}
		if (useDownloadFileName) {
			final String downloadFileName =
					resolvedDownloadFileName != null ? resolvedDownloadFileName : extractFileNameFromUrlPath(url);
			if (downloadFileName != null) {
				return zipEntry != null ? deriveExtractedJarFileName(downloadFileName) : downloadFileName;
			}
		}
		return name + "-" + version + ".jar";
	}

	/**
	 * Adjusts a zip archive's own filename into a sensible name for the jar extracted from it:
	 * a trailing ".zip" (case-insensitive) becomes ".jar"; a name already ending in ".jar" is
	 * used as-is; anything else just gets ".jar" appended.
	  *
	  * @param zipFileName the zip archive's filename
	  * @return the filename for the extracted jar
	 */
	private static String deriveExtractedJarFileName(final String zipFileName) {
		final String lowerCaseFileName = zipFileName.toLowerCase(Locale.ROOT);
		if (lowerCaseFileName.endsWith(".zip")) {
			return zipFileName.substring(0, zipFileName.length() - ".zip".length()) + ".jar";
		}
		if (lowerCaseFileName.endsWith(".jar")) {
			return zipFileName;
		}
		return zipFileName + ".jar";
	}

	/**
	 * Builds the path of this dependency's jar in the local repository cache:
	 * {@code <repositoryRoot>/<groupId as path>/<name>/<version>/<name>-<version>.jar}.
	 *
	 * @return the cache path, which may not exist yet
	 */
	private Path buildRepositoryJarPath() {
		final String groupPath = groupId.replace('.', '/');
		return Paths.get(repositoryRoot)
				.resolve(Paths.get(groupPath, name, version))
				.resolve(name + "-" + version + ".jar");
	}

	/**
	 * Builds the full artifact download URL from a Maven-layout repository base URL plus this
	 * resolver's groupId/artifactId/version(/classifier), following the standard Maven repository
	 * layout: {@code <repositoryBaseUrl>/<groupId as path>/<artifactId>/<version>/<artifactId>-<version>[-<classifier>].jar}.
	 *
	 * Note: this intentionally uses {@link #artifactId} (the Maven coordinate), not {@link #name}
	 * (the local/logical dependency name used only for the internal repository cache path - see
	 * {@link #buildRepositoryJarPath}; the libDir target filename is built separately by
	 * {@link #buildTargetFileName} and always uses artifactId here, same as this URL).
	  *
	  * @param repositoryBaseUrl the Maven-layout repository base url, with or without trailing slash
	  * @return the full artifact download url
	 */
	private String buildMavenArtifactUrl(final String repositoryBaseUrl) {
		final String normalizedBaseUrl = repositoryBaseUrl.endsWith("/") ? repositoryBaseUrl : repositoryBaseUrl + "/";
		final String groupPath = groupId.replace('.', '/');
		final String classifierSuffix = classifier != null ? "-" + classifier : "";
		return normalizedBaseUrl + groupPath + "/" + artifactId + "/" + version + "/" + artifactId + "-" + version + classifierSuffix + ".jar";
	}

	/**
	 * Checks for the version keywords that need resolution before use.
	 *
	 * @param versionValue the version value, may be null
	 * @return true for "RELEASE" or "LATEST" (case-insensitive)
	 */
	private static boolean isMetaVersionKeyword(final String versionValue) {
		return "RELEASE".equalsIgnoreCase(versionValue) || "LATEST".equalsIgnoreCase(versionValue);
	}

	/**
	 * Resolves the metaVersion ("RELEASE" or "LATEST", case-insensitive) against the
	 * group/artifact-level {@code maven-metadata.xml} published at
	 * {@code <repositoryBaseUrl>/<groupId as path>/<artifactId>/maven-metadata.xml}, reading the
	 * matching &lt;release&gt; or &lt;latest&gt; tag from its &lt;versioning&gt; section.
	  *
	  * @param repositoryBaseUrl the Maven-layout repository base url, with or without trailing slash
	  * @param metaVersion       "RELEASE" or "LATEST" (case-insensitive)
	  * @return the resolved version number
	  * @throws Exception if the maven-metadata.xml is not available or contains no matching tag
	 */
	private String resolveMavenMetaVersion(final String repositoryBaseUrl, final String metaVersion) throws Exception {
		final String normalizedBaseUrl = repositoryBaseUrl.endsWith("/") ? repositoryBaseUrl : repositoryBaseUrl + "/";
		final String groupPath = groupId.replace('.', '/');
		final String metadataUrl = normalizedBaseUrl + groupPath + "/" + artifactId + "/maven-metadata.xml";

		final HttpClient httpClient = buildHttpClient(metadataUrl);
		final String metadataContent = fetchRequiredText(httpClient, metadataUrl,
				"Could not resolve version '" + metaVersion + "' for " + groupId + ":" + artifactId + ": maven-metadata.xml");

		final boolean isLatest = "LATEST".equalsIgnoreCase(metaVersion);
		final Pattern tagPattern = isLatest ? LATEST_VERSION_TAG_PATTERN : RELEASE_VERSION_TAG_PATTERN;
		final Matcher tagMatcher = tagPattern.matcher(metadataContent);
		if (!tagMatcher.find()) {
			throw new IOException("Could not find <" + (isLatest ? "latest" : "release") + "> version tag in "
					+ metadataUrl);
		}
		return tagMatcher.group(1);
	}

	/**
	 * Checks whether a url is a github.com releases-download url.
	 *
	 * @param urlValue the url, may be null
	 * @return true if the url matches {@link #GITHUB_RELEASE_URL_PATTERN}
	 */
	private static boolean isGithubReleaseUrl(final String urlValue) {
		return urlValue != null && GITHUB_RELEASE_URL_PATTERN.matcher(urlValue).find();
	}

	/**
	 * Resolves metaVersion ("RELEASE" or "LATEST", case-insensitive - GitHub has no separate
	 * distinction between the two, so both resolve to the same thing here) for a GitHub
	 * releases-download url ({@code https://github.com/<owner>/<repo>/releases/download/...})
	 * via GitHub's "latest release" API ({@code https://api.github.com/repos/<owner>/<repo>/releases/latest}).
	 *
	 * The returned tag_name is used as the resolved version, except a single leading "v"/"V"
	 * directly followed by a digit is stripped (the common "v1.2.3" tag naming convention), so
	 * the result is usable as a plain version number in a "{version}" url placeholder, e.g.
	 * {@code https://github.com/<owner>/<repo>/releases/download/v{version}/asset-{version}.jar}.
	  *
	  * @param downloadUrlTemplate the github.com releases-download url
	  * @param metaVersion         "RELEASE" or "LATEST" (case-insensitive), for error messages
	  * @return the resolved version
	  * @throws Exception if the GitHub API is not available (e.g. rate limit) or its response
	  *                   contains no tag_name
	 */
	private String resolveGithubLatestVersion(final String downloadUrlTemplate, final String metaVersion) throws Exception {
		final Matcher ownerRepoMatcher = GITHUB_RELEASE_URL_PATTERN.matcher(downloadUrlTemplate);
		if (!ownerRepoMatcher.find()) {
			throw new IOException("Could not parse GitHub owner/repo from url: " + downloadUrlTemplate);
		}
		final String owner = ownerRepoMatcher.group(1);
		final String repo = ownerRepoMatcher.group(2);
		final String githubApiUrl = "https://api.github.com/repos/" + owner + "/" + repo + "/releases/latest";

		final HttpClient httpClient = buildHttpClient(githubApiUrl);
		final String apiResponseBody = fetchRequiredText(httpClient, githubApiUrl,
				"Could not resolve version '" + metaVersion + "' for " + owner + "/" + repo + ": GitHub releases/latest");
		final Matcher tagNameMatcher = GITHUB_TAG_NAME_JSON_PATTERN.matcher(apiResponseBody);
		if (!tagNameMatcher.find()) {
			throw new IOException("Could not find tag_name in GitHub API response from " + githubApiUrl);
		}
		return stripLeadingVPrefix(tagNameMatcher.group(1));
	}

	/**
	 * Strips a single leading "v"/"V" from tagName if directly followed by a digit (e.g. "v1.2.3" -&gt; "1.2.3").
	 *
	 * @param tagName the release tag name
	 * @return the tag name without the "v" prefix
	 */
	private static String stripLeadingVPrefix(final String tagName) {
		if (tagName.length() > 1 && (tagName.charAt(0) == 'v' || tagName.charAt(0) == 'V') && Character.isDigit(tagName.charAt(1))) {
			return tagName.substring(1);
		}
		return tagName;
	}

	/**
	 * Heuristic used by {@link #removeOldVersions} (Maven artifact mode, unclassified target
	 * only) to tell a plain version string apart from a classified sibling's suffix: matches only
	 * the strict dotted-number shape - one or more digit groups separated by dots, nothing else -
	 * e.g. "26.2.51" or "4.38". Deliberately does NOT allow a trailing "-qualifier" (unlike a
	 * looser version pattern would), because such a qualifier is textually indistinguishable from
	 * a classifier suffix (e.g. "26.2.51-sources"): allowing it back in would risk the unclassified
	 * cleanup deleting a classified sibling's freshly downloaded file again. The trade-off: a
	 * version with a real "-qualifier" (e.g. "2.0.1-SNAPSHOT", or a hyphenated build id like SWT's
	 * "4.38-win32-win32-x86_64") won't be recognized as an old version of an unclassified target
	 * and so won't be cleaned up automatically - a stale extra jar, not a wrongly deleted one, so
	 * this still fails safe.
	 */
	private static final Pattern PLAIN_VERSION_PATTERN = Pattern.compile("^\\d+(\\.\\d+)*$");

	/**
	 * Removes any previously downloaded jar of the same dependency but a different version from
	 * libDir, matching against {@link #buildTargetFileName}'s naming scheme:
	 * <ul>
	 * <li>Default mode (no artifactId): candidates are found via the {@code name + "-*.jar"} glob
	 * and removed unless they equal the current target filename exactly.</li>
	 * <li>Maven artifact mode, classifier set: candidates are found via the
	 * {@code artifactId + "-*-" + classifier + ".jar"} glob - anchored on both the artifactId
	 * prefix and the "-classifier.jar" suffix, so the version in between (which may itself
	 * contain hyphens) can't cause a false match against the unclassified artifact or a
	 * different classifier.</li>
	 * <li>Maven artifact mode, no classifier: candidates are found via the
	 * {@code artifactId + "-*.jar"} glob, which would also catch a classified sibling's file
	 * (e.g. artifactId="foo" vs. a sibling classifier file "foo-1.0-sources.jar"). To avoid
	 * deleting that sibling, a candidate is only removed if what follows the "artifactId-" prefix
	 * (with ".jar" removed) matches {@link #PLAIN_VERSION_PATTERN} - see its javadoc for the
	 * trade-off this implies.</li>
	 * </ul>
	  *
	  * @param targetLibDir the libDir to clean up
	  * @throws IOException if the directory cannot be listed or a file cannot be deleted
	 */
	private void removeOldVersions(final Path targetLibDir) throws IOException {
		final String currentFileName = buildTargetFileName();
		if (artifactId != null && classifier != null) {
			final String glob = artifactId + "-*-" + classifier + ".jar";
			try (DirectoryStream<Path> directoryStream = Files.newDirectoryStream(targetLibDir, glob)) {
				for (final Path existingFile : directoryStream) {
					final String existingFileName = existingFile.getFileName().toString();
					if (!existingFileName.equals(currentFileName)) {
						logger.accept("Removing old version: " + existingFileName);
						Files.delete(existingFile);
					}
				}
			}
		} else if (artifactId != null) {
			final String prefix = artifactId + "-";
			try (DirectoryStream<Path> directoryStream =
					Files.newDirectoryStream(targetLibDir, artifactId + "-*.jar")) {
				for (final Path existingFile : directoryStream) {
					final String existingFileName = existingFile.getFileName().toString();
					if (existingFileName.equals(currentFileName)) {
						continue;
					}
					final String withoutJarSuffix = existingFileName.substring(0, existingFileName.length() - ".jar".length());
					final String remainderAfterPrefix = withoutJarSuffix.substring(prefix.length());
					if (PLAIN_VERSION_PATTERN.matcher(remainderAfterPrefix).matches()) {
						logger.accept("Removing old version: " + existingFileName);
						Files.delete(existingFile);
					}
				}
			}
		} else {
			try (DirectoryStream<Path> directoryStream =
					Files.newDirectoryStream(targetLibDir, name + "-*.jar")) {
				for (final Path existingFile : directoryStream) {
					final String existingFileName = existingFile.getFileName().toString();
					if (!existingFileName.equals(currentFileName)) {
						logger.accept("Removing old version: " + existingFileName);
						Files.delete(existingFile);
					}
				}
			}
		}
	}

	/**
	 * Downloads the given URL into the local repository. If zipEntry is set, the download is
	 * treated as a zip archive and only the given entry is extracted as the resulting jar
	 * (e.g. swt.jar out of an Eclipse SWT distribution zip) - the temp zip is discarded afterward.
	 *
	 * Everything is written to temporary files next to the final cache entry first and only
	 * moved into place after a successful checksum verification (and zip extraction). This is
	 * essential, because {@link #resolve()} trusts an existing cache entry without verifying it
	 * again: a tampered or partially downloaded file left in the cache would otherwise be used
	 * silently on the next run.
	  *
	  * @param downloadUrl       the full download url
	  * @param repositoryJarPath the final path in the local repository cache
	  * @throws Exception if the download, checksum verification or zip extraction fails
	 */
	private void resolveIntoRepository(final String downloadUrl, final Path repositoryJarPath) throws Exception {
		final boolean checksumVerificationEnabled = artifactId != null || isGithubReleaseUrl(downloadUrl);
		final Path repositoryDirectory = repositoryJarPath.getParent();
		Files.createDirectories(repositoryDirectory);

		final Path tempDownloadFile = Files.createTempFile(repositoryDirectory, ".download-", ".tmp");
		Path tempExtractedFile = null;
		try {
			downloadToFile(downloadUrl, tempDownloadFile);
			if (checksumVerificationEnabled) {
				verifyChecksum(downloadUrl, tempDownloadFile);
			}
			if (zipEntry != null) {
				tempExtractedFile = Files.createTempFile(repositoryDirectory, ".extract-", ".tmp");
				extractZipEntry(tempDownloadFile, zipEntry, tempExtractedFile);
				moveIntoPlace(tempExtractedFile, repositoryJarPath);
			} else {
				moveIntoPlace(tempDownloadFile, repositoryJarPath);
			}
		} finally {
			Files.deleteIfExists(tempDownloadFile);
			if (tempExtractedFile != null) {
				Files.deleteIfExists(tempExtractedFile);
			}
		}
	}

	/**
	 * Moves a completely written file to its final path, atomically if the file system supports it.
	 *
	 * @param sourceFile the completely written file
	 * @param targetFile the final path, replaced if it exists
	 * @throws IOException if the move fails
	 */
	private static void moveIntoPlace(final Path sourceFile, final Path targetFile) throws IOException {
		try {
			Files.move(sourceFile, targetFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
		} catch (@SuppressWarnings("unused") final AtomicMoveNotSupportedException e) {
			Files.move(sourceFile, targetFile, StandardCopyOption.REPLACE_EXISTING);
		}
	}

	/**
	 * Extracts a single entry of a zip archive into a file.
	 *
	 * @param zipFile    the zip archive
	 * @param entryPath  the path of the entry inside the archive
	 * @param targetFile the file to write, replaced if it exists
	 * @throws IOException if the archive cannot be read or does not contain the entry
	 */
	private static void extractZipEntry(final Path zipFile, final String entryPath, final Path targetFile) throws IOException {
		try (ZipFile zip = new ZipFile(zipFile.toFile())) {
			final ZipEntry entry = zip.getEntry(entryPath);
			if (entry == null) {
				throw new IOException("Zip entry '" + entryPath + "' not found in downloaded archive: " + zipFile);
			}
			Files.createDirectories(targetFile.getParent());
			try (InputStream entryStream = zip.getInputStream(entry)) {
				Files.copy(entryStream, targetFile, StandardCopyOption.REPLACE_EXISTING);
			}
		}
	}

	/**
	 * Downloads a url into a file. In the default mode with useDownloadFileName, the filename
	 * suggested by the server's Content-Disposition header is remembered for
	 * {@link #buildTargetFileName()}.
	 *
	 * @param downloadUrl the url to download
	 * @param targetPath  the file to write, replaced if it exists
	 * @throws Exception if the request fails or the server does not answer with HTTP 200
	 */
	private void downloadToFile(final String downloadUrl, final Path targetPath) throws Exception {
		Files.createDirectories(targetPath.getParent());

		final HttpClient httpClient = buildHttpClient(downloadUrl);

		final HttpRequest httpRequest = HttpRequest.newBuilder(URI.create(downloadUrl)).GET().build();
		final HttpResponse<InputStream> httpResponse =
				httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofInputStream());

		if (httpResponse.statusCode() != 200) {
			// Closing the unread body releases the connection
			httpResponse.body().close();
			throw new IOException("Download failed with HTTP status " + httpResponse.statusCode()
					+ " for URL: " + downloadUrl);
		}

		if (useDownloadFileName && artifactId == null) {
			// Captures the *current* download's own suggested filename - for the zipEntry case
			// this method is downloading the zip archive itself, not the extracted jar, so the
			// name gets ".zip" -&gt; ".jar"-adjusted in buildTargetFileName() before use.
			resolvedDownloadFileName = extractFileNameFromContentDisposition(httpResponse.headers());
		}

		try (InputStream inputStream = httpResponse.body()) {
			Files.copy(inputStream, targetPath, StandardCopyOption.REPLACE_EXISTING);
		}
	}

	/**
	 * Builds an HttpClient configured with this resolver's proxy and TLS-trust settings for targetUrl.
	 *
	 * @param targetUrl url the client is used for, needed for PAC/WPAD proxy resolution
	 * @return the configured HttpClient
	 * @throws Exception if the proxy cannot be determined or the certificate file cannot be read
	 */
	private HttpClient buildHttpClient(final String targetUrl) throws Exception {
		final ProxyConfig proxyConfig = proxyUrl != null || pacUrl != null || useWpad
				? new ProxyConfig().withProxyUrl(proxyUrl).withPacUrl(pacUrl).withUseWpad(useWpad)
				: null;
		return HttpClientFactory.createHttpClient(targetUrl, proxyConfig, tlsCertificateFile);
	}

	/** Matches the RFC 5987 extended form, e.g. {@code filename*=UTF-8''some%20file.jar}, preferred when present. */
	private static final Pattern CONTENT_DISPOSITION_FILENAME_STAR_PATTERN =
			Pattern.compile("(?i)\\bfilename\\*\\s*=\\s*(?:[^']*'[^']*')?([^;]+)");
	/** Matches the basic form, e.g. {@code filename="some file.jar"} or {@code filename=some-file.jar}. */
	private static final Pattern CONTENT_DISPOSITION_FILENAME_PATTERN =
			Pattern.compile("(?i)\\bfilename\\s*=\\s*\"?([^\";]+)\"?");

	/**
	 * Extracts a filename from a Content-Disposition response header, if present, preferring the
	 * RFC 5987 extended {@code filename*=} form (percent-decoded) over the basic {@code filename=}
	 * form. Returns null if the header is absent or neither form is found.
	  *
	  * @param responseHeaders the HTTP response headers
	  * @return the sanitized filename, or null
	 */
	private static String extractFileNameFromContentDisposition(final HttpHeaders responseHeaders) {
		final String headerValue = responseHeaders.firstValue("Content-Disposition").orElse(null);
		if (headerValue == null) {
			return null;
		}
		final Matcher starMatcher = CONTENT_DISPOSITION_FILENAME_STAR_PATTERN.matcher(headerValue);
		if (starMatcher.find()) {
			try {
				return sanitizeFileName(URLDecoder.decode(starMatcher.group(1).trim(), StandardCharsets.UTF_8));
			} catch (@SuppressWarnings("unused") final IllegalArgumentException malformedEncoding) {
				// fall through to the basic form below
			}
		}
		final Matcher plainMatcher = CONTENT_DISPOSITION_FILENAME_PATTERN.matcher(headerValue);
		if (plainMatcher.find()) {
			return sanitizeFileName(plainMatcher.group(1).trim());
		}
		return null;
	}

	/**
	 * Extracts the last path segment of downloadUrl (query string and fragment ignored) as a
	 * fallback filename, e.g. {@code https://example.com/dl/lib-1.0.jar?token=x} -&gt;
	 * {@code lib-1.0.jar}. Only accepts a segment ending in ".jar" or ".zip" (case-insensitive) -
	 * without this restriction, a query-string-driven download url like {@code
	 * https://example.com/index.php?download=lib.jar} would wrongly yield "index.php" as a
	 * "filename" (its path segment does contain a dot, just not a useful one). Returns null if
	 * there's no such segment, so the caller can fall back further.
	  *
	  * @param downloadUrl the download url
	  * @return the filename, or null
	 */
	private static String extractFileNameFromUrlPath(final String downloadUrl) {
		final String path;
		try {
			path = URI.create(downloadUrl).getPath();
		} catch (@SuppressWarnings("unused") final IllegalArgumentException malformedUrl) {
			return null;
		}
		if (path == null) {
			return null;
		}
		final int lastSlashIndex = path.lastIndexOf('/');
		final String candidate = lastSlashIndex >= 0 ? path.substring(lastSlashIndex + 1) : path;
		final String lowerCaseCandidate = candidate.toLowerCase(Locale.ROOT);
		return (lowerCaseCandidate.endsWith(".jar") || lowerCaseCandidate.endsWith(".zip")) ? candidate : null;
	}

	/**
	 * Reduces a filename taken from an external source (header/URL) to its bare basename, discarding any path.
	 *
	 * @param rawFileName the filename as received, may be null
	 * @return the basename, or null if nothing usable remains (empty, "." or "..")
	 */
	private static String sanitizeFileName(final String rawFileName) {
		if (rawFileName == null || rawFileName.isBlank()) {
			return null;
		}
		final String normalized = rawFileName.replace('\\', '/');
		final int lastSlashIndex = normalized.lastIndexOf('/');
		final String candidate = lastSlashIndex >= 0 ? normalized.substring(lastSlashIndex + 1) : normalized;
		if (candidate.isBlank() || ".".equals(candidate.trim()) || "..".equals(candidate.trim())) {
			// "." or ".." would resolve to libDir itself or its parent instead of a file in libDir
			return null;
		}
		return candidate;
	}

	/**
	 * Verifies downloadedFile (the raw bytes fetched from downloadUrl) against a checksum file
	 * published alongside it (downloadUrl + ".sha512"/".sha256"/".sha1"/".md5"), trying the
	 * strongest algorithm first and using the first one that is actually available (HTTP 200).
	 * Throws if a checksum file is found but does not match; only logs a warning if none of the
	 * checksum file variants exist.
	  *
	  * @param downloadUrl    the url the file was downloaded from
	  * @param downloadedFile the downloaded file
	  * @throws Exception if a checksum file is found but invalid or not matching, or a request fails
	 */
	private void verifyChecksum(final String downloadUrl, final Path downloadedFile) throws Exception {
		final HttpClient httpClient = buildHttpClient(downloadUrl);
		for (final Map.Entry<String, String> checksumVariant : CHECKSUM_EXTENSION_TO_DIGEST_ALGORITHM.entrySet()) {
			final String checksumExtension = checksumVariant.getKey();
			final String digestAlgorithm = checksumVariant.getValue();
			final String checksumUrl = downloadUrl + "." + checksumExtension;

			final String checksumFileContent = fetchTextIfPresent(httpClient, checksumUrl);
			if (checksumFileContent != null) {
				final String expectedHash = extractHexHash(checksumFileContent, checksumUrl);
				final String actualHash = computeHexDigest(downloadedFile, digestAlgorithm);
				if (!expectedHash.equalsIgnoreCase(actualHash)) {
					throw new IOException("Checksum verification failed for " + downloadUrl + " (" + digestAlgorithm
							+ "): expected " + expectedHash + " but downloaded file has " + actualHash);
				}
				logger.accept("Checksum verified (" + digestAlgorithm + "): " + downloadUrl);
				return;
			}
		}
		logger.accept("Warning: no checksum file (.sha512/.sha256/.sha1/.md5) found for " + downloadUrl
				+ " - integrity was NOT verified");
	}

	/** Matches a non-empty hexadecimal hash value */
	private static final Pattern HEX_HASH_PATTERN = Pattern.compile("[0-9a-fA-F]+");

	/**
	 * Fetches the text content at url, failing with the HTTP status if it is not available.
	 *
	 * @param httpClient   the client to use
	 * @param url          the url to fetch
	 * @param errorContext start of the error message, describing what was fetched for what purpose
	 * @return the response body
	 * @throws Exception if the request fails or the server does not answer with HTTP 200
	 */
	private static String fetchRequiredText(final HttpClient httpClient, final String url, final String errorContext) throws Exception {
		final HttpRequest httpRequest = HttpRequest.newBuilder(URI.create(url)).GET().build();
		final HttpResponse<String> httpResponse = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
		if (httpResponse.statusCode() != 200) {
			throw new IOException(errorContext + " not available, HTTP status " + httpResponse.statusCode() + " (" + url + ")");
		}
		return httpResponse.body();
	}

	/**
	 * Fetches the text content at url, or null if it does not exist (HTTP 404) or is otherwise not available.
	 *
	 * @param httpClient the client to use
	 * @param url        the url to fetch
	 * @return the response body, or null for any status other than HTTP 200
	 * @throws Exception if the request itself fails
	 */
	private static String fetchTextIfPresent(final HttpClient httpClient, final String url) throws Exception {
		final HttpRequest httpRequest = HttpRequest.newBuilder(URI.create(url)).GET().build();
		final HttpResponse<String> httpResponse = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
		return httpResponse.statusCode() == 200 ? httpResponse.body() : null;
	}

	/**
	 * Extracts the hex hash from a checksum file's content: plain "&lt;hexhash&gt;", or the
	 * common "&lt;hexhash&gt;  &lt;filename&gt;" / "&lt;hexhash&gt; *&lt;filename&gt;" format, separated by
	 * any whitespace.
	 *
	 * @param checksumFileContent the content of the checksum file
	 * @param checksumUrl         the url of the checksum file, for error messages
	 * @return the hex hash
	 * @throws IOException if the content does not start with a hex hash
	 */
	private static String extractHexHash(final String checksumFileContent, final String checksumUrl) throws IOException {
		final String firstToken = checksumFileContent.trim().split("\\s+", 2)[0];
		if (!HEX_HASH_PATTERN.matcher(firstToken).matches()) {
			throw new IOException("Checksum file " + checksumUrl + " contains no hex hash");
		}
		return firstToken;
	}

	/**
	 * Computes the digest of a file.
	 *
	 * @param file      the file
	 * @param algorithm the MessageDigest algorithm, e.g. "SHA-256"
	 * @return the digest as lower-case hex string
	 * @throws Exception if the algorithm is not available or the file cannot be read
	 */
	private static String computeHexDigest(final Path file, final String algorithm) throws Exception {
		final MessageDigest messageDigest = MessageDigest.getInstance(algorithm);
		try (InputStream inputStream = Files.newInputStream(file)) {
			final byte[] buffer = new byte[8192];
			int bytesRead;
			while ((bytesRead = inputStream.read(buffer)) != -1) {
				messageDigest.update(buffer, 0, bytesRead);
			}
		}
		final StringBuilder hexString = new StringBuilder();
		for (final byte digestByte : messageDigest.digest()) {
			hexString.append(String.format("%02x", digestByte));
		}
		return hexString.toString();
	}
}
