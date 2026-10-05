package de.soderer.antbuildhelp.versions;

/**
 * A single entry of the central Versions.json file:
 * artifact name mapped to its currently published version and download URL template.
 * Bean-style setters stay void; withX() methods are provided for fluent construction.
 */
public class VersionsJsonEntry {

	/** Currently published version */
	private String version;

	/** Download url template of the currently published version */
	private String downloadUrl;

	/**
	 * Creates an empty entry, to be filled via the setters or withX() methods.
	 */
	public VersionsJsonEntry() {
		// Nothing to initialize
	}

	/**
	 * Returns the currently published version.
	 *
	 * @return the version, or null if not set
	 */
	public String getVersion() {
		return version;
	}

	/**
	 * Sets the currently published version.
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
	public VersionsJsonEntry withVersion(final String versionParam) {
		setVersion(versionParam);
		return this;
	}

	/**
	 * Returns the download url template of the currently published version.
	 *
	 * @return the url template, which may contain &lt;username&gt;/&lt;password&gt; placeholders,
	 *         or null if not set
	 */
	public String getDownloadUrl() {
		return downloadUrl;
	}

	/**
	 * Sets the download url template of the currently published version.
	 *
	 * @param downloadUrl the url template, which may contain &lt;username&gt;/&lt;password&gt;
	 *                    placeholders
	 */
	public void setDownloadUrl(final String downloadUrl) {
		this.downloadUrl = downloadUrl;
	}

	/**
	 * Fluent variant of {@link #setDownloadUrl(String)}.
	 *
	 * @param downloadUrlParam the url template
	 * @return this entry, for method chaining
	 */
	public VersionsJsonEntry withDownloadUrl(final String downloadUrlParam) {
		setDownloadUrl(downloadUrlParam);
		return this;
	}
}
