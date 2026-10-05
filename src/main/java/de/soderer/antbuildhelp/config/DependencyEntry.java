package de.soderer.antbuildhelp.config;

/**
 * A single dependency to resolve, as configured by a nested {@code <dependency>} element of the
 * {@code resolveDependencies} Ant task.
 * Bean-style setters stay void; withX() methods are provided for fluent construction.
 */
public class DependencyEntry {

	/** Logical dependency name, used as key into the central Versions.json and for the repository path */
	private String name;

	/** Fixed version string, or "latest"/"current" (or null) to use the version from the central Versions.json */
	private String version;

	/** Download url, may contain {name}, {version}, {username}, {password} and the legacy &lt;username&gt;/&lt;password&gt; placeholders */
	private String downloadUrlTemplate;

	/** Proxy settings for the download, or null for a direct connection */
	private ProxyConfig proxyConfig;

	/** Additionally trusted certificate (e.g. corporate TLS-inspecting proxy), null for JVM default CAs only */
	private String tlsCertificateFile;

	/**
	 * Creates an empty dependency entry, to be filled via the setters or withX() methods.
	 */
	public DependencyEntry() {
		// Nothing to initialize
	}

	/**
	 * Returns the logical dependency name.
	 *
	 * @return the name, or null if not set
	 */
	public String getName() {
		return name;
	}

	/**
	 * Sets the logical dependency name. It is used as key into the central Versions.json and,
	 * lower-cased, as artifact directory in the local repository.
	 *
	 * @param name the name
	 */
	public void setName(final String name) {
		this.name = name;
	}

	/**
	 * Fluent variant of {@link #setName(String)}.
	 *
	 * @param nameParam the name
	 * @return this entry, for method chaining
	 */
	public DependencyEntry withName(final String nameParam) {
		setName(nameParam);
		return this;
	}

	/**
	 * Returns the requested version.
	 *
	 * @return the fixed version string, "latest"/"current", or null
	 */
	public String getVersion() {
		return version;
	}

	/**
	 * Sets the requested version: a fixed version string, or "latest"/"current"
	 * (case-insensitive) or null to use the version published in the central Versions.json.
	 *
	 * @param version the version
	 */
	public void setVersion(final String version) {
		this.version = version;
	}

	/**
	 * Fluent variant of {@link #setVersion(String)}.
	 *
	 * @param versionParam the version
	 * @return this entry, for method chaining
	 */
	public DependencyEntry withVersion(final String versionParam) {
		setVersion(versionParam);
		return this;
	}

	/**
	 * Checks whether the version has to be taken from the central Versions.json.
	 *
	 * @return true if the version is null, "latest" or "current" (case-insensitive)
	 */
	public boolean isLatestVersionRequested() {
		return version == null
				|| "latest".equalsIgnoreCase(version)
				|| "current".equalsIgnoreCase(version);
	}

	/**
	 * Returns the download url template.
	 *
	 * @return the template, or null to use the download url of the central Versions.json
	 */
	public String getDownloadUrlTemplate() {
		return downloadUrlTemplate;
	}

	/**
	 * Sets the download url template. It may contain the placeholders {name}, {version},
	 * {username} and {password}, and the legacy forms &lt;username&gt; and &lt;password&gt;
	 * (see {@link de.soderer.antbuildhelp.download.UrlTemplateResolver}). Substituted values are
	 * percent-encoded automatically.
	 *
	 * @param downloadUrlTemplate the template
	 */
	public void setDownloadUrlTemplate(final String downloadUrlTemplate) {
		this.downloadUrlTemplate = downloadUrlTemplate;
	}

	/**
	 * Fluent variant of {@link #setDownloadUrlTemplate(String)}.
	 *
	 * @param downloadUrlTemplateParam the template
	 * @return this entry, for method chaining
	 */
	public DependencyEntry withDownloadUrlTemplate(final String downloadUrlTemplateParam) {
		setDownloadUrlTemplate(downloadUrlTemplateParam);
		return this;
	}

	/**
	 * Returns the proxy settings for the download.
	 *
	 * @return the proxy settings, or null for a direct connection
	 */
	public ProxyConfig getProxyConfig() {
		return proxyConfig;
	}

	/**
	 * Sets the proxy settings for the download.
	 *
	 * @param proxyConfig the proxy settings, or null for a direct connection
	 */
	public void setProxyConfig(final ProxyConfig proxyConfig) {
		this.proxyConfig = proxyConfig;
	}

	/**
	 * Fluent variant of {@link #setProxyConfig(ProxyConfig)}.
	 *
	 * @param proxyConfigParam the proxy settings, or null for a direct connection
	 * @return this entry, for method chaining
	 */
	public DependencyEntry withProxyConfig(final ProxyConfig proxyConfigParam) {
		setProxyConfig(proxyConfigParam);
		return this;
	}

	/**
	 * Returns the path of an additionally trusted X.509 certificate file.
	 *
	 * @return the path, or null to trust the JVM's default CAs only
	 */
	public String getTlsCertificateFile() {
		return tlsCertificateFile;
	}

	/**
	 * Sets the path of an additionally trusted X.509 certificate file (PEM or DER), e.g. the root
	 * certificate of a corporate TLS-inspecting proxy.
	 *
	 * @param tlsCertificateFile the path, or null to trust the JVM's default CAs only
	 */
	public void setTlsCertificateFile(final String tlsCertificateFile) {
		this.tlsCertificateFile = tlsCertificateFile;
	}

	/**
	 * Fluent variant of {@link #setTlsCertificateFile(String)}.
	 *
	 * @param tlsCertificateFileParam the path, or null to trust the JVM's default CAs only
	 * @return this entry, for method chaining
	 */
	public DependencyEntry withTlsCertificateFile(final String tlsCertificateFileParam) {
		setTlsCertificateFile(tlsCertificateFileParam);
		return this;
	}
}
