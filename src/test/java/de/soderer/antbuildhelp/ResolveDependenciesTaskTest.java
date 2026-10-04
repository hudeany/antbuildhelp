package de.soderer.antbuildhelp;

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
 * Tests for the resolvedeps Ant task (Versions.json based resolution into the local repository)
 * against a local {@link TestHttpServer}. The exact local repository layout is treated as an
 * implementation detail: the tests only check that the downloaded jar ends up below repositoryRoot.
 */
class ResolveDependenciesTaskTest {

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

	private ResolveDependenciesTask createTask() {
		final ResolveDependenciesTask task = new ResolveDependenciesTask();
		task.setProject(AntTestUtilities.createProject(baseDir));
		task.setRepositoryRoot(repositoryRoot.toString());
		return task;
	}

	@Test
	void latestVersionIsResolvedViaVersionsJson() {
		final byte[] jarBytes = AntTestUtilities.createJarBytes("versionlib 4.5.6");
		testHttpServer.addFile("/files/versionlib-4.5.6.jar", jarBytes);
		testHttpServer.addText("/Versions.json", "{\n"
				+ "\t\"versionlib\": {\n"
				+ "\t\t\"version\": \"4.5.6\",\n"
				+ "\t\t\"downloadUrl\": \"" + testHttpServer.getBaseUrl() + "/files/versionlib-4.5.6.jar\"\n"
				+ "\t}\n"
				+ "}\n");

		final ResolveDependenciesTask task = createTask();
		task.setVersionsJsonUrl(testHttpServer.getBaseUrl() + "/Versions.json");
		task.createDependency().setName("versionlib"); // version defaults to "latest"
		task.execute();

		assertTrue(AntTestUtilities.findFileWithContent(repositoryRoot, jarBytes).isPresent(), "Jar not found below " + repositoryRoot);
	}

	@Test
	void explicitVersionIsSubstitutedInUrlTemplate() {
		final byte[] jarBytes = AntTestUtilities.createJarBytes("templatelib 1.2.3");
		testHttpServer.addFile("/files/templatelib-1.2.3.jar", jarBytes);

		final ResolveDependenciesTask task = createTask();
		final ResolveDependenciesTask.DependencyElement dependencyElement = task.createDependency();
		dependencyElement.setName("templatelib");
		dependencyElement.setVersion("1.2.3");
		dependencyElement.setUrl(testHttpServer.getBaseUrl() + "/files/{name}-{version}.jar");
		task.execute();

		assertTrue(AntTestUtilities.findFileWithContent(repositoryRoot, jarBytes).isPresent(), "Jar not found below " + repositoryRoot);
	}

	@Test
	void unreachableVersionsJsonFails() {
		final ResolveDependenciesTask task = createTask();
		task.setVersionsJsonUrl(testHttpServer.getBaseUrl() + "/doesnotexist/Versions.json");
		task.createDependency().setName("versionlib");

		assertThrows(BuildException.class, task::execute);
	}

	@Test
	void missingDownloadFails() {
		final ResolveDependenciesTask task = createTask();
		final ResolveDependenciesTask.DependencyElement dependencyElement = task.createDependency();
		dependencyElement.setName("templatelib");
		dependencyElement.setVersion("9.9.9");
		dependencyElement.setUrl(testHttpServer.getBaseUrl() + "/files/{name}-{version}.jar");

		assertThrows(BuildException.class, task::execute);
	}
}
