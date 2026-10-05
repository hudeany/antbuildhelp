package de.soderer.antbuildhelp.download;

/**
 * Holds the credentials that are substituted for the {username}/{password} (and legacy
 * &lt;username&gt;/&lt;password&gt;) placeholders of download url templates.
 */
public class CredentialsProvider {

	/** User name for url placeholder substitution */
	private final String username;

	/** Password for url placeholder substitution */
	private final String password;

	/**
	 * Creates a credentials holder.
	 *
	 * @param username the user name, may be null
	 * @param password the password, may be null
	 */
	public CredentialsProvider(final String username, final String password) {
		this.username = username;
		this.password = password;
	}

	/**
	 * Returns the user name.
	 *
	 * @return the user name, may be null
	 */
	public String getUsername() {
		return username;
	}

	/**
	 * Returns the password.
	 *
	 * @return the password, may be null
	 */
	public String getPassword() {
		return password;
	}
}
