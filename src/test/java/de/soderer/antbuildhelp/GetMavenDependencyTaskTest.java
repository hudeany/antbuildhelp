package de.soderer.antbuildhelp;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.apache.tools.ant.BuildException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for the getMavenDependency Ant task against a local Maven-layout repository served by
 * {@link TestHttpServer}. The local repository cache is redirected into a temporary directory, so
 * the user's real ~/.m2/repository is never touched.
 */
class GetMavenDependencyTaskTest {

	private static final String REPOSITORY_PATH = "/maven2";

	@TempDir
	Path tempDir;

	private TestHttpServer testHttpServer;
	private Path baseDir;
	private Path repositoryRoot;

	@BeforeEach
	void setUp() throws Exception {
		testHttpServer = new TestHttpServer();
		baseDir = Files.createDirectories(tempDir.resolve("project"));
		repositoryRoot = Files.createDirectories(tempDir.resolve("repository"));
	}

	@AfterEach
	void tearDown() {
		testHttpServer.close();
	}

	private GetMavenDependencyTask createTask(final String groupId, final String artifactId, final String version) {
		final GetMavenDependencyTask task = new GetMavenDependencyTask();
		task.setProject(AntTestUtilities.createProject(baseDir));
		task.setRepositoryRoot(repositoryRoot.toString());
		task.setRepositoryUrl(testHttpServer.getBaseUrl() + REPOSITORY_PATH);
		task.setGroupId(groupId);
		task.setArtifactId(artifactId);
		task.setVersion(version);
		return task;
	}

	@Test
	void missingArtifactIdFails() {
		final GetMavenDependencyTask task = createTask("de.soderer", null, "1.0.0");

		final BuildException buildException = assertThrows(BuildException.class, task::execute);
		assertTrue(buildException.getMessage().contains("artifactId"), buildException.getMessage());
	}

	@Test
	void downloadsArtifactIntoDefaultLibDir() throws Exception {
		final byte[] jarBytes = AntTestUtilities.createJarBytes("testlib 1.0.0");
		testHttpServer.addMavenArtifact(REPOSITORY_PATH, "de.soderer", "testlib", "1.0.0", null, jarBytes);

		createTask("de.soderer", "testlib", "1.0.0").execute();

		final Path libFile = baseDir.resolve("lib").resolve("testlib-1.0.0.jar");
		assertTrue(Files.isRegularFile(libFile), "Missing " + libFile);
		assertArrayEquals(jarBytes, Files.readAllBytes(libFile));
	}

	@Test
	void groupIdDefaultsToDeSoderer() throws Exception {
		final byte[] jarBytes = AntTestUtilities.createJarBytes("defaultgroup 1.0.0");
		testHttpServer.addMavenArtifact(REPOSITORY_PATH, "de.soderer", "defaultgroup", "1.0.0", null, jarBytes);

		createTask(null, "defaultgroup", "1.0.0").execute();

		assertArrayEquals(jarBytes, Files.readAllBytes(baseDir.resolve("lib").resolve("defaultgroup-1.0.0.jar")));
	}

	@Test
	void relativeLibDirIsResolvedAgainstProjectBaseDir() throws Exception {
		final byte[] jarBytes = AntTestUtilities.createJarBytes("testlib 1.0.0");
		testHttpServer.addMavenArtifact(REPOSITORY_PATH, "de.soderer", "testlib", "1.0.0", null, jarBytes);

		final GetMavenDependencyTask task = createTask("de.soderer", "testlib", "1.0.0");
		task.setLibDir("libs/custom");
		task.execute();

		assertArrayEquals(jarBytes, Files.readAllBytes(baseDir.resolve("libs").resolve("custom").resolve("testlib-1.0.0.jar")));
	}

	@Test
	void absoluteLibDirIsUsedAsIs() throws Exception {
		final byte[] jarBytes = AntTestUtilities.createJarBytes("testlib 1.0.0");
		testHttpServer.addMavenArtifact(REPOSITORY_PATH, "de.soderer", "testlib", "1.0.0", null, jarBytes);
		final Path absoluteLibDir = tempDir.resolve("elsewhere");

		final GetMavenDependencyTask task = createTask("de.soderer", "testlib", "1.0.0");
		task.setLibDir(absoluteLibDir.toString());
		task.execute();

		assertArrayEquals(jarBytes, Files.readAllBytes(absoluteLibDir.resolve("testlib-1.0.0.jar")));
		assertFalse(Files.exists(baseDir.resolve("lib").resolve("testlib-1.0.0.jar")));
	}

	@Test
	void classifierDownloadsClassifiedJar() throws Exception {
		final byte[] jarBytes = AntTestUtilities.createJarBytes("testlib 1.0.0");
		final byte[] sourcesJarBytes = AntTestUtilities.createJarBytes("testlib 1.0.0 sources");
		testHttpServer.addMavenArtifact(REPOSITORY_PATH, "de.soderer", "testlib", "1.0.0", null, jarBytes);
		testHttpServer.addMavenArtifact(REPOSITORY_PATH, "de.soderer", "testlib", "1.0.0", "sources", sourcesJarBytes);

		createTask("de.soderer", "testlib", "1.0.0").execute();
		final GetMavenDependencyTask sourcesTask = createTask("de.soderer", "testlib", "1.0.0");
		sourcesTask.setClassifier("sources");
		sourcesTask.execute();

		// Both must exist side by side, the classified one must not overwrite the regular one (shared cache)
		assertArrayEquals(jarBytes, Files.readAllBytes(baseDir.resolve("lib").resolve("testlib-1.0.0.jar")));
		assertArrayEquals(sourcesJarBytes, Files.readAllBytes(baseDir.resolve("lib").resolve("testlib-1.0.0-sources.jar")));
	}

	@Test
	void releaseVersionIsResolvedViaMavenMetadata() throws Exception {
		final byte[] oldJarBytes = AntTestUtilities.createJarBytes("testlib 1.0.0");
		final byte[] newJarBytes = AntTestUtilities.createJarBytes("testlib 1.2.0");
		testHttpServer.addMavenArtifact(REPOSITORY_PATH, "de.soderer", "testlib", "1.0.0", null, oldJarBytes);
		testHttpServer.addMavenArtifact(REPOSITORY_PATH, "de.soderer", "testlib", "1.2.0", null, newJarBytes);
		final String mavenMetadata = """
				<?xml version="1.0" encoding="UTF-8"?>
				<metadata>
					<groupId>de.soderer</groupId>
					<artifactId>testlib</artifactId>
					<versioning>
						<latest>1.2.0</latest>
						<release>1.2.0</release>
						<versions>
							<version>1.0.0</version>
							<version>1.2.0</version>
						</versions>
					</versioning>
				</metadata>
				""";
		testHttpServer.addText(REPOSITORY_PATH + "/de/soderer/testlib/maven-metadata.xml", mavenMetadata);

		createTask("de.soderer", "testlib", "RELEASE").execute();

		assertArrayEquals(newJarBytes, Files.readAllBytes(baseDir.resolve("lib").resolve("testlib-1.2.0.jar")));
	}

	@Test
	void checksumMismatchFailsAndLeavesNoLibFile() throws Exception {
		final byte[] jarBytes = AntTestUtilities.createJarBytes("testlib 1.0.0");
		final byte[] otherBytes = AntTestUtilities.createJarBytes("tampered");
		final String artifactPath = TestHttpServer.getMavenArtifactPath(REPOSITORY_PATH, "de.soderer", "testlib", "1.0.0", null);
		testHttpServer.addFile(artifactPath, jarBytes);
		// Checksums of different content, as if the jar was tampered with on the way
		for (final String algorithm : new String[] { "md5", "sha1", "sha256", "sha512" }) {
			testHttpServer.addText(artifactPath + "." + algorithm, AntTestUtilities.checksum(otherBytes, algorithm));
		}

		assertThrows(BuildException.class, () -> createTask("de.soderer", "testlib", "1.0.0").execute());

		assertFalse(Files.exists(baseDir.resolve("lib").resolve("testlib-1.0.0.jar")));
		assertFalse(AntTestUtilities.findFileWithContent(repositoryRoot, jarBytes).isPresent(), "Unverified jar must not be cached");
		assertFalse(AntTestUtilities.containsAnyFile(repositoryRoot), "No temporary files must be left in the local repository");
	}

	@Test
	void failedVerificationIsNotTrustedOnNextRun() throws Exception {
		final byte[] tamperedBytes = AntTestUtilities.createJarBytes("tampered");
		final byte[] jarBytes = AntTestUtilities.createJarBytes("testlib 1.0.0");
		final String artifactPath = TestHttpServer.getMavenArtifactPath(REPOSITORY_PATH, "de.soderer", "testlib", "1.0.0", null);

		// First run: tampered jar, checksums of the original jar
		testHttpServer.addFile(artifactPath, tamperedBytes);
		for (final String algorithm : new String[] { "md5", "sha1", "sha256", "sha512" }) {
			testHttpServer.addText(artifactPath + "." + algorithm, AntTestUtilities.checksum(jarBytes, algorithm));
		}
		assertThrows(BuildException.class, () -> createTask("de.soderer", "testlib", "1.0.0").execute());

		// Second run: the server delivers the original jar again, which must be downloaded and verified anew
		testHttpServer.addFile(artifactPath, jarBytes);
		createTask("de.soderer", "testlib", "1.0.0").execute();

		assertArrayEquals(jarBytes, Files.readAllBytes(baseDir.resolve("lib").resolve("testlib-1.0.0.jar")));
		assertEquals(2, testHttpServer.getGetRequestCount(artifactPath), "Jar must be downloaded again after the failed verification");
	}

	@Test
	void missingArtifactFailsWithDependencyName() {
		final BuildException buildException = assertThrows(BuildException.class, () -> createTask("de.soderer", "doesnotexist", "1.0.0").execute());

		assertTrue(buildException.getMessage().contains("doesnotexist"), buildException.getMessage());
	}

	@Test
	void secondResolutionIsServedFromLocalRepository() throws Exception {
		final byte[] jarBytes = AntTestUtilities.createJarBytes("testlib 1.0.0");
		testHttpServer.addMavenArtifact(REPOSITORY_PATH, "de.soderer", "testlib", "1.0.0", null, jarBytes);
		final String artifactPath = TestHttpServer.getMavenArtifactPath(REPOSITORY_PATH, "de.soderer", "testlib", "1.0.0", null);
		final Path libFile = baseDir.resolve("lib").resolve("testlib-1.0.0.jar");

		createTask("de.soderer", "testlib", "1.0.0").execute();
		// Remove the lib file, so the second run has to restore it from the local repository cache
		Files.delete(libFile);
		createTask("de.soderer", "testlib", "1.0.0").execute();

		assertArrayEquals(jarBytes, Files.readAllBytes(libFile));
		assertEquals(1, testHttpServer.getGetRequestCount(artifactPath), "Jar must be downloaded only once");
	}

	@Test
	void checksumFileWithTabSeparatedFileNameIsAccepted() throws Exception {
		final byte[] jarBytes = AntTestUtilities.createJarBytes("tablib 1.0.0");
		final String artifactPath = TestHttpServer.getMavenArtifactPath(REPOSITORY_PATH, "de.soderer", "tablib", "1.0.0", null);
		testHttpServer.addFile(artifactPath, jarBytes);
		testHttpServer.addText(artifactPath + ".sha512", AntTestUtilities.checksum(jarBytes, "sha512") + "\ttablib-1.0.0.jar\n");

		createTask("de.soderer", "tablib", "1.0.0").execute();

		assertArrayEquals(jarBytes, Files.readAllBytes(baseDir.resolve("lib").resolve("tablib-1.0.0.jar")));
	}

	@Test
	void checksumFileWithoutHashFails() {
		final byte[] jarBytes = AntTestUtilities.createJarBytes("nohashlib 1.0.0");
		final String artifactPath = TestHttpServer.getMavenArtifactPath(REPOSITORY_PATH, "de.soderer", "nohashlib", "1.0.0", null);
		testHttpServer.addFile(artifactPath, jarBytes);
		testHttpServer.addText(artifactPath + ".sha512", "\n");

		final BuildException buildException = assertThrows(BuildException.class, createTask("de.soderer", "nohashlib", "1.0.0")::execute);
		assertTrue(buildException.getMessage().contains("no hex hash"), buildException.getMessage());
		assertFalse(Files.exists(baseDir.resolve("lib").resolve("nohashlib-1.0.0.jar")));
	}

	@Test
	void unavailableMavenMetadataReportsHttpStatus() {
		testHttpServer.addStatus(REPOSITORY_PATH + "/de/soderer/busylib/maven-metadata.xml", 503);

		final BuildException buildException = assertThrows(BuildException.class, createTask("de.soderer", "busylib", "RELEASE")::execute);
		assertTrue(buildException.getMessage().contains("503"), buildException.getMessage());
	}
}
