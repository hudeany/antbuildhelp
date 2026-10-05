package de.soderer.antbuildhelp;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.apache.tools.ant.BuildException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for the getDependency Ant task (plain url downloads, file naming, zip extraction and
 * Maven artifact mode) against a local {@link TestHttpServer}. The local repository cache is
 * redirected into a temporary directory, so the user's real ~/.m2/repository is never touched.
 */
class GetDependencyTaskTest {

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

	private GetDependencyTask createTask(final String name, final String version, final String url) {
		final GetDependencyTask task = new GetDependencyTask();
		task.setProject(AntTestUtilities.createProject(baseDir));
		task.setRepositoryRoot(repositoryRoot.toString());
		task.setName(name);
		task.setVersion(version);
		task.setUrl(url);
		return task;
	}

	private Path libFile(final String fileName) {
		return baseDir.resolve("lib").resolve(fileName);
	}

	@Test
	void versionPlaceholderIsSubstitutedInUrl() throws Exception {
		final byte[] jarBytes = AntTestUtilities.createJarBytes("plainlib 2.0.0");
		testHttpServer.addFile("/files/plainlib-2.0.0.jar", jarBytes);

		final GetDependencyTask task = createTask("plainlib", "2.0.0", testHttpServer.getBaseUrl() + "/files/plainlib-{version}.jar");
		task.setUseDownloadFileName(false);
		task.execute();

		assertArrayEquals(jarBytes, Files.readAllBytes(libFile("plainlib-2.0.0.jar")));
	}

	@Test
	void useDownloadFileNameFalseAlwaysUsesNameAndVersion() throws Exception {
		final byte[] jarBytes = AntTestUtilities.createJarBytes("plainlib 2.0.0");
		testHttpServer.addFile("/files/completely-different-name.jar", jarBytes);

		final GetDependencyTask task = createTask("plainlib", "2.0.0", testHttpServer.getBaseUrl() + "/files/completely-different-name.jar");
		task.setUseDownloadFileName(false);
		task.execute();

		assertArrayEquals(jarBytes, Files.readAllBytes(libFile("plainlib-2.0.0.jar")));
	}

	@Test
	void fileNameIsTakenFromUrlPathByDefault() throws Exception {
		final byte[] jarBytes = AntTestUtilities.createJarBytes("plainlib 2.0.0");
		testHttpServer.addFile("/files/plainlib-release.jar", jarBytes);

		createTask("plainlib", "2.0.0", testHttpServer.getBaseUrl() + "/files/plainlib-release.jar").execute();

		assertArrayEquals(jarBytes, Files.readAllBytes(libFile("plainlib-release.jar")));
	}

	@Test
	void fileNameIsTakenFromContentDispositionByDefault() throws Exception {
		final byte[] jarBytes = AntTestUtilities.createJarBytes("csv 26.1.1");
		testHttpServer.addFile("/index.php?download=csv.jar", jarBytes, Map.of("Content-Disposition", "attachment; filename=\"csv-from-header.jar\""));

		createTask("csv", "26.1.1", testHttpServer.getBaseUrl() + "/index.php?download=csv.jar").execute();

		assertArrayEquals(jarBytes, Files.readAllBytes(libFile("csv-from-header.jar")));
	}

	@Test
	void fileNameFallsBackToNameAndVersionWithoutUsableDownloadName() throws Exception {
		final byte[] jarBytes = AntTestUtilities.createJarBytes("plainlib 3.0.0");
		testHttpServer.addFile("/download?id=42", jarBytes);

		createTask("plainlib", "3.0.0", testHttpServer.getBaseUrl() + "/download?id=42").execute();

		assertArrayEquals(jarBytes, Files.readAllBytes(libFile("plainlib-3.0.0.jar")));
	}

	@Test
	void zipEntryIsExtractedAndNamedAfterZipByDefault() throws Exception {
		final byte[] innerJarBytes = AntTestUtilities.createJarBytes("swt windows");
		testHttpServer.addFile("/files/swtbundle-4.38-win32.zip", AntTestUtilities.createZipBytes("swt.jar", innerJarBytes));

		final GetDependencyTask task = createTask("swt-win", "4.38", testHttpServer.getBaseUrl() + "/files/swtbundle-4.38-win32.zip");
		task.setZipEntry("swt.jar");
		task.execute();

		// Named after the zip (".zip" -> ".jar"), not after the entry "swt.jar"
		assertArrayEquals(innerJarBytes, Files.readAllBytes(libFile("swtbundle-4.38-win32.jar")));
		assertFalse(Files.exists(libFile("swt.jar")), "Entry name must not be used as file name");
	}

	@Test
	void zipEntryWithUseDownloadFileNameFalseUsesNameAndVersion() throws Exception {
		final byte[] innerJarBytes = AntTestUtilities.createJarBytes("swt linux");
		testHttpServer.addFile("/files/swtbundle-4.38-gtk.zip", AntTestUtilities.createZipBytes("swt.jar", innerJarBytes));

		final GetDependencyTask task = createTask("swt-linux", "4.38", testHttpServer.getBaseUrl() + "/files/swtbundle-4.38-gtk.zip");
		task.setZipEntry("swt.jar");
		task.setUseDownloadFileName(false);
		task.execute();

		assertArrayEquals(innerJarBytes, Files.readAllBytes(libFile("swt-linux-4.38.jar")));
	}

	@Test
	void artifactIdSwitchesToMavenModeWithUrlAsRepositoryBase() throws Exception {
		final byte[] jarBytes = AntTestUtilities.createJarBytes("testlib 1.0.0");
		testHttpServer.addMavenArtifact("/maven2", "de.soderer", "testlib", "1.0.0", null, jarBytes);

		final GetDependencyTask task = createTask(null, "1.0.0", testHttpServer.getBaseUrl() + "/maven2");
		task.setGroupId("de.soderer");
		task.setArtifactId("testlib");
		task.execute();

		assertArrayEquals(jarBytes, Files.readAllBytes(libFile("testlib-1.0.0.jar")));
	}

	@Test
	void relativeLibDirIsResolvedAgainstProjectBaseDir() throws Exception {
		final byte[] jarBytes = AntTestUtilities.createJarBytes("plainlib 2.0.0");
		testHttpServer.addFile("/files/plainlib-2.0.0.jar", jarBytes);

		final GetDependencyTask task = createTask("plainlib", "2.0.0", testHttpServer.getBaseUrl() + "/files/plainlib-2.0.0.jar");
		task.setLibDir("lib_build");
		task.execute();

		assertArrayEquals(jarBytes, Files.readAllBytes(baseDir.resolve("lib_build").resolve("plainlib-2.0.0.jar")));
	}

	@Test
	void missingFileFailsWithDependencyName() {
		final GetDependencyTask task = createTask("plainlib", "9.9.9", testHttpServer.getBaseUrl() + "/files/plainlib-9.9.9.jar");

		final BuildException buildException = assertThrows(BuildException.class, task::execute);
		assertTrue(buildException.getMessage().contains("'plainlib'"), buildException.getMessage());
	}

	@Test
	void dotDotFileNameFromContentDispositionIsIgnored() throws Exception {
		final byte[] jarBytes = AntTestUtilities.createJarBytes("dotlib 1.0.0");
		testHttpServer.addFile("/index.php?download=dotlib.jar", jarBytes, Map.of("Content-Disposition", "attachment; filename=\"..\""));

		createTask("dotlib", "1.0.0", testHttpServer.getBaseUrl() + "/index.php?download=dotlib.jar").execute();

		assertArrayEquals(jarBytes, Files.readAllBytes(libFile("dotlib-1.0.0.jar")));
	}
}
