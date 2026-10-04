package de.soderer.antbuildhelp.config;

/**
 * Proxy configuration for a dependency download.
 * Exactly one of the three modes is expected to be set; direct URL takes precedence
 * over PAC, PAC takes precedence over WPAD auto-detection.
 */
public class ProxyConfig {

	private String proxyUrl;   // e.g. "http://proxy.example.com:8080"
	private String pacUrl;     // e.g. "http://wpad.example.com/proxy.pac"
	private boolean useWpad;   // auto-detect via WPAD

	public String getProxyUrl() {
		return proxyUrl;
	}

	public void setProxyUrl(final String proxyUrl) {
		this.proxyUrl = proxyUrl;
	}

	public ProxyConfig withProxyUrl(final String proxyUrlParam) {
		setProxyUrl(proxyUrlParam);
		return this;
	}

	public String getPacUrl() {
		return pacUrl;
	}

	public void setPacUrl(final String pacUrl) {
		this.pacUrl = pacUrl;
	}

	public ProxyConfig withPacUrl(final String pacUrlParam) {
		setPacUrl(pacUrlParam);
		return this;
	}

	public boolean isUseWpad() {
		return useWpad;
	}

	public void setUseWpad(final boolean useWpad) {
		this.useWpad = useWpad;
	}

	public ProxyConfig withUseWpad(final boolean useWpadParam) {
		setUseWpad(useWpadParam);
		return this;
	}
}
