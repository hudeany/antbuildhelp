package de.soderer.antbuildhelp.versions;

import de.soderer.antbuildhelp.config.DependencyEntry;

/**
 * Resolves a dependency's effective version and download URL template,
 * taking "latest"/"current" requests from the central VersionsJson into account.
 */
public class VersionResolver {

	private final VersionsJson versionsJson; // may be null if not configured

	public VersionResolver(final VersionsJson versionsJson) {
		this.versionsJson = versionsJson;
	}

	public ResolvedDependency resolve(final DependencyEntry dependencyEntry) {
		if (dependencyEntry.isLatestVersionRequested()) {
			if (versionsJson == null || !versionsJson.containsEntry(dependencyEntry.getName())) {
				throw new IllegalStateException("Cannot resolve 'latest' version for '"
						+ dependencyEntry.getName() + "': no Versions.json entry available");
			}
			final VersionsJsonEntry centralEntry = versionsJson.getEntry(dependencyEntry.getName());
			final String urlTemplate = dependencyEntry.getDownloadUrlTemplate() != null
					? dependencyEntry.getDownloadUrlTemplate()
					: centralEntry.getDownloadUrl();
			return new ResolvedDependency(dependencyEntry.getName(), centralEntry.getVersion(), urlTemplate);
		} else {
			return new ResolvedDependency(dependencyEntry.getName(), dependencyEntry.getVersion(),
					dependencyEntry.getDownloadUrlTemplate());
		}
	}

	/**
	 * Immutable result of version resolution: definite name/version/urlTemplate triple.
	 */
	public static class ResolvedDependency {

		private final String name;
		private final String version;
		private final String downloadUrlTemplate;

		public ResolvedDependency(final String name, final String version, final String downloadUrlTemplate) {
			this.name = name;
			this.version = version;
			this.downloadUrlTemplate = downloadUrlTemplate;
		}

		public String getName() {
			return name;
		}

		public String getVersion() {
			return version;
		}

		public String getDownloadUrlTemplate() {
			return downloadUrlTemplate;
		}
	}
}
