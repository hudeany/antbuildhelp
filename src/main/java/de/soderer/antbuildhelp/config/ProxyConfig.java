package de.soderer.antbuildhelp.config;

/**
 * Proxy configuration for a dependency download.
 * Exactly one of the three modes is expected to be set; direct URL takes precedence
 * over PAC, PAC takes precedence over WPAD auto-detection.
 * Bean-style setters stay void; withX() methods are provided for fluent construction.
 */
public class ProxyConfig {

	/** Direct proxy, e.g. "http://proxy.example.com:8080" or "proxy.example.com:8080" */
	private String proxyUrl;

	/** Url of a PAC script, e.g. "http://wpad.example.com/proxy.pac" */
	private String pacUrl;

	/** Whether to auto-detect the PAC script via WPAD */
	private boolean useWpad;

	/**
	 * Creates an empty proxy configuration (no proxy), to be filled via the setters or withX()
	 * methods.
	 */
	public ProxyConfig() {
		// Nothing to initialize
	}

	/**
	 * Returns the direct proxy url.
	 *
	 * @return the proxy url, or null if not set
	 */
	public String getProxyUrl() {
		return proxyUrl;
	}

	/**
	 * Sets a direct proxy, e.g. "http://proxy.example.com:8080". The scheme is optional, and the
	 * port defaults to 80 if omitted. Takes precedence over PAC url and WPAD.
	 *
	 * @param proxyUrl the proxy url, or null
	 */
	public void setProxyUrl(final String proxyUrl) {
		this.proxyUrl = proxyUrl;
	}

	/**
	 * Fluent variant of {@link #setProxyUrl(String)}.
	 *
	 * @param proxyUrlParam the proxy url, or null
	 * @return this configuration, for method chaining
	 */
	public ProxyConfig withProxyUrl(final String proxyUrlParam) {
		setProxyUrl(proxyUrlParam);
		return this;
	}

	/**
	 * Returns the url of the PAC script.
	 *
	 * @return the PAC url, or null if not set
	 */
	public String getPacUrl() {
		return pacUrl;
	}

	/**
	 * Sets the url of a PAC script that decides the proxy per download url. Takes precedence over
	 * WPAD.
	 *
	 * @param pacUrl the PAC url, or null
	 */
	public void setPacUrl(final String pacUrl) {
		this.pacUrl = pacUrl;
	}

	/**
	 * Fluent variant of {@link #setPacUrl(String)}.
	 *
	 * @param pacUrlParam the PAC url, or null
	 * @return this configuration, for method chaining
	 */
	public ProxyConfig withPacUrl(final String pacUrlParam) {
		setPacUrl(pacUrlParam);
		return this;
	}

	/**
	 * Returns whether the PAC script is auto-detected via WPAD.
	 *
	 * @return true if WPAD is used
	 */
	public boolean isUseWpad() {
		return useWpad;
	}

	/**
	 * Sets whether the PAC script is auto-detected via WPAD. Only used if neither proxy url nor
	 * PAC url is set.
	 *
	 * @param useWpad true to use WPAD
	 */
	public void setUseWpad(final boolean useWpad) {
		this.useWpad = useWpad;
	}

	/**
	 * Fluent variant of {@link #setUseWpad(boolean)}.
	 *
	 * @param useWpadParam true to use WPAD
	 * @return this configuration, for method chaining
	 */
	public ProxyConfig withUseWpad(final boolean useWpadParam) {
		setUseWpad(useWpadParam);
		return this;
	}
}
