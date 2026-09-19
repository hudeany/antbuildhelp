package de.soderer.antbuildhelp.config;

/**
 * A single dependency entry as read from the dependencies file (YAML/JSON/XML).
 * Bean-style setters stay void; withX() methods are provided for fluent construction.
 */
public class DependencyEntry {

	private String name;
	private String version; // fixed version string, or "latest"/"current"
	private String downloadUrlTemplate; // may contain {name}, {version}, {baseUrl}, <username>, <password>, ...
	private ProxyConfig proxyConfig;
	private boolean tlsCertCheckEnabled = true;

	public String getName() {
		return name;
	}

	public void setName(final String name) {
		this.name = name;
	}

	public DependencyEntry withName(final String nameParam) {
		setName(nameParam);
		return this;
	}

	public String getVersion() {
		return version;
	}

	public void setVersion(final String version) {
		this.version = version;
	}

	public DependencyEntry withVersion(final String versionParam) {
		setVersion(versionParam);
		return this;
	}

	public boolean isLatestVersionRequested() {
		return version == null
				|| "latest".equalsIgnoreCase(version)
				|| "current".equalsIgnoreCase(version);
	}

	public String getDownloadUrlTemplate() {
		return downloadUrlTemplate;
	}

	public void setDownloadUrlTemplate(final String downloadUrlTemplate) {
		this.downloadUrlTemplate = downloadUrlTemplate;
	}

	public DependencyEntry withDownloadUrlTemplate(final String downloadUrlTemplateParam) {
		setDownloadUrlTemplate(downloadUrlTemplateParam);
		return this;
	}

	public ProxyConfig getProxyConfig() {
		return proxyConfig;
	}

	public void setProxyConfig(final ProxyConfig proxyConfig) {
		this.proxyConfig = proxyConfig;
	}

	public DependencyEntry withProxyConfig(final ProxyConfig proxyConfigParam) {
		setProxyConfig(proxyConfigParam);
		return this;
	}

	public boolean isTlsCertCheckEnabled() {
		return tlsCertCheckEnabled;
	}

	public void setTlsCertCheckEnabled(final boolean tlsCertCheckEnabled) {
		this.tlsCertCheckEnabled = tlsCertCheckEnabled;
	}

	public DependencyEntry withTlsCertCheckEnabled(final boolean tlsCertCheckEnabledParam) {
		setTlsCertCheckEnabled(tlsCertCheckEnabledParam);
		return this;
	}
}
