package de.soderer.antbuildhelp.repository;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Builds Maven-compatible repository paths under the local repository root (typically ~/.m2/repository),
 * so downloaded jars integrate with tooling that expects a standard Maven layout.
 */
public class RepositoryPathBuilder {

	private final Path repositoryRoot;

	public RepositoryPathBuilder(final Path repositoryRoot) {
		this.repositoryRoot = repositoryRoot;
	}

	/**
	 * @param groupId    dot-separated, e.g. "de.soderer"
	 * @param artifactId e.g. "multied"
	 * @param version    e.g. "26.1.73"
	 */
	public Path buildJarPath(final String groupId, final String artifactId, final String version) {
		final String groupPath = groupId.replace('.', '/');
		return repositoryRoot
				.resolve(Paths.get(groupPath, artifactId, version))
				.resolve(artifactId + "-" + version + ".jar");
	}

	public boolean jarAlreadyPresent(final String groupId, final String artifactId, final String version) {
		return buildJarPath(groupId, artifactId, version).toFile().isFile();
	}
}
