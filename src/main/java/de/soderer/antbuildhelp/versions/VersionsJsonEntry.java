package de.soderer.antbuildhelp.versions;

/**
 * A single entry of the central Versions.json file:
 * artifact name mapped to its currently published version and download URL template.
 */
public class VersionsJsonEntry {

	private String version;
	private String downloadUrl;

	public String getVersion() {
		return version;
	}

	public void setVersion(final String version) {
		this.version = version;
	}

	public VersionsJsonEntry withVersion(final String versionParam) {
		setVersion(versionParam);
		return this;
	}

	public String getDownloadUrl() {
		return downloadUrl;
	}

	public void setDownloadUrl(final String downloadUrl) {
		this.downloadUrl = downloadUrl;
	}

	public VersionsJsonEntry withDownloadUrl(final String downloadUrlParam) {
		setDownloadUrl(downloadUrlParam);
		return this;
	}
}
