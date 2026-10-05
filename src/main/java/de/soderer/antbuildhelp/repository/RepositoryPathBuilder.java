package de.soderer.antbuildhelp.repository;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Builds Maven-compatible repository paths under the local repository root (typically ~/.m2/repository),
 * so downloaded jars integrate with tooling that expects a standard Maven layout.
 */
public class RepositoryPathBuilder {

	/** Root directory of the local repository */
	private final Path repositoryRoot;

	/**
	 * Creates a path builder for the given local repository.
	 *
	 * @param repositoryRoot root directory of the local repository, e.g. ~/.m2/repository
	 */
	public RepositoryPathBuilder(final Path repositoryRoot) {
		this.repositoryRoot = repositoryRoot;
	}

	/**
	 * Builds the path of an artifact's jar in Maven layout:
	 * {@code <repositoryRoot>/<groupId as path>/<artifactId>/<version>/<artifactId>-<version>.jar}.
	 *
	 * @param groupId    dot-separated, e.g. "de.soderer"
	 * @param artifactId e.g. "multied"
	 * @param version    e.g. "26.1.73"
	 * @return the path of the jar, which may not exist yet
	 */
	public Path buildJarPath(final String groupId, final String artifactId, final String version) {
		final String groupPath = groupId.replace('.', '/');
		return repositoryRoot
				.resolve(Paths.get(groupPath, artifactId, version))
				.resolve(artifactId + "-" + version + ".jar");
	}

	/**
	 * Checks whether an artifact's jar already exists in the local repository.
	 *
	 * @param groupId    dot-separated, e.g. "de.soderer"
	 * @param artifactId e.g. "multied"
	 * @param version    e.g. "26.1.73"
	 * @return true if the jar exists as regular file
	 */
	public boolean jarAlreadyPresent(final String groupId, final String artifactId, final String version) {
		return buildJarPath(groupId, artifactId, version).toFile().isFile();
	}
}
