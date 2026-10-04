package de.soderer.antbuildhelp.download;

/**
 * Provides credentials for placeholder substitution in download URLs.
 * Placeholder implementation: reads from a local properties file under the user's home directory.
 * TODO: decide final source (properties file vs. environment variables vs. interactive prompt).
 */
public class CredentialsProvider {

	private final String username;
	private final String password;

	public CredentialsProvider(final String username, final String password) {
		this.username = username;
		this.password = password;
	}

	public String getUsername() {
		return username;
	}

	public String getPassword() {
		return password;
	}
}
