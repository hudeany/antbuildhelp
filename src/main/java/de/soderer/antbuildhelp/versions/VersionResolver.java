package de.soderer.antbuildhelp.versions;

import de.soderer.antbuildhelp.config.DependencyEntry;

/**
 * Resolves a dependency's effective version and download URL template,
 * taking "latest"/"current" requests from the central VersionsJson into account.
 */
public class VersionResolver {

	/** Central version information, may be null if not configured */
	private final VersionsJson versionsJson;

	/**
	 * Creates a resolver.
	 *
	 * @param versionsJson central version information, or null if not configured (then only
	 *                     dependencies with a fixed version can be resolved)
	 */
	public VersionResolver(final VersionsJson versionsJson) {
		this.versionsJson = versionsJson;
	}

	/**
	 * Resolves the effective version and download url template of a dependency. A fixed version
	 * is taken as is. For "latest"/"current" the version is taken from the central Versions.json,
	 * and so is the url template, unless the dependency defines its own.
	 *
	 * @param dependencyEntry the dependency to resolve
	 * @return the resolved name/version/url template
	 * @throws IllegalStateException if the latest version is requested but no Versions.json entry
	 *                               is available for the dependency
	 */
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

		/** Logical dependency name */
		private final String name;

		/** Definite version */
		private final String version;

		/** Download url template, may be null if neither dependency nor Versions.json define one */
		private final String downloadUrlTemplate;

		/**
		 * Creates a resolution result.
		 *
		 * @param name                logical dependency name
		 * @param version             definite version
		 * @param downloadUrlTemplate download url template, or null if none is defined
		 */
		public ResolvedDependency(final String name, final String version, final String downloadUrlTemplate) {
			this.name = name;
			this.version = version;
			this.downloadUrlTemplate = downloadUrlTemplate;
		}

		/**
		 * Returns the logical dependency name.
		 *
		 * @return the name
		 */
		public String getName() {
			return name;
		}

		/**
		 * Returns the definite version.
		 *
		 * @return the version
		 */
		public String getVersion() {
			return version;
		}

		/**
		 * Returns the download url template.
		 *
		 * @return the url template, or null if neither dependency nor Versions.json define one
		 */
		public String getDownloadUrlTemplate() {
			return downloadUrlTemplate;
		}
	}
}
