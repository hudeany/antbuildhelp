package de.soderer.antbuildhelp;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import org.apache.tools.ant.BuildException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests the tlsCertificateFile handling of all three Ant tasks against an HTTPS server with a
 * self-signed certificate: without the certificate the connection must be rejected (certificate
 * validation can never be switched off), with it the download must succeed.
 */
class TlsCertificateTest {

	@TempDir
	Path tempDir;

	private TestHttpServer testHttpsServer;
	private Path baseDir;
	private Path repositoryRoot;
	private Path certificateFile;
	/** Jar content served by the test server; created once, because jar bytes contain timestamps */
	private byte[] jarBytes;

	@BeforeEach
	void setUp() throws Exception {
		final TestCertificate testCertificate = TestCertificate.get();
		testHttpsServer = new TestHttpServer(testCertificate.createServerSslContext());
		baseDir = Files.createDirectories(tempDir.resolve("project"));
		repositoryRoot = Files.createDirectories(tempDir.resolve("repository"));
		// Copy into the project, so relative paths can be tested too
		certificateFile = Files.copy(testCertificate.getCertificateFile(), baseDir.resolve("test-root.pem"), StandardCopyOption.REPLACE_EXISTING);
	}

	@AfterEach
	void tearDown() {
		testHttpsServer.close();
	}

	private GetDependencyTask createGetDependencyTask() {
		jarBytes = AntTestUtilities.createJarBytes("securelib 1.0.0");
		testHttpsServer.addFile("/files/securelib-1.0.0.jar", jarBytes);
		final GetDependencyTask task = new GetDependencyTask();
		task.setProject(AntTestUtilities.createProject(baseDir));
		task.setRepositoryRoot(repositoryRoot.toString());
		task.setName("securelib");
		task.setVersion("1.0.0");
		task.setUrl(testHttpsServer.getBaseUrl() + "/files/securelib-1.0.0.jar");
		return task;
	}

	@Test
	void getDependencyRejectsUntrustedCertificate() {
		assertThrows(BuildException.class, () -> createGetDependencyTask().execute());
		assertFalse(AntTestUtilities.containsAnyFile(repositoryRoot), "Nothing must be cached");
	}

	@Test
	void getDependencyTrustsAbsoluteCertificateFile() throws Exception {
		final GetDependencyTask task = createGetDependencyTask();
		task.setTlsCertificateFile(certificateFile.toString());
		task.execute();

		assertArrayEquals(jarBytes, Files.readAllBytes(baseDir.resolve("lib").resolve("securelib-1.0.0.jar")));
	}

	@Test
	void getDependencyResolvesRelativeCertificateFileAgainstBaseDir() throws Exception {
		final GetDependencyTask task = createGetDependencyTask();
		task.setTlsCertificateFile("test-root.pem");
		task.execute();

		assertTrue(Files.isRegularFile(baseDir.resolve("lib").resolve("securelib-1.0.0.jar")));
	}

	@Test
	void getMavenDependencyTrustsCertificateFile() throws Exception {
		jarBytes = AntTestUtilities.createJarBytes("testlib 1.0.0");
		testHttpsServer.addMavenArtifact("/maven2", "de.soderer", "testlib", "1.0.0", null, jarBytes);

		final GetMavenDependencyTask task = new GetMavenDependencyTask();
		task.setProject(AntTestUtilities.createProject(baseDir));
		task.setRepositoryRoot(repositoryRoot.toString());
		task.setRepositoryUrl(testHttpsServer.getBaseUrl() + "/maven2");
		task.setArtifactId("testlib");
		task.setVersion("1.0.0");
		task.setTlsCertificateFile("test-root.pem");
		task.execute();

		assertArrayEquals(jarBytes, Files.readAllBytes(baseDir.resolve("lib").resolve("testlib-1.0.0.jar")));
	}

	private ResolveDependenciesTask createResolveDependenciesTask() {
		jarBytes = AntTestUtilities.createJarBytes("versionlib 4.5.6");
		testHttpsServer.addFile("/files/versionlib-4.5.6.jar", jarBytes);
		testHttpsServer.addText("/Versions.json", "{\n"
				+ "\t\"versionlib\": {\n"
				+ "\t\t\"version\": \"4.5.6\",\n"
				+ "\t\t\"downloadUrl\": \"" + testHttpsServer.getBaseUrl() + "/files/versionlib-4.5.6.jar\"\n"
				+ "\t}\n"
				+ "}\n");

		final ResolveDependenciesTask task = new ResolveDependenciesTask();
		task.setProject(AntTestUtilities.createProject(baseDir));
		task.setRepositoryRoot(repositoryRoot.toString());
		task.setVersionsJsonUrl(testHttpsServer.getBaseUrl() + "/Versions.json");
		task.createDependency().setName("versionlib");
		return task;
	}

	@Test
	void resolveDependenciesRejectsUntrustedCertificate() {
		assertThrows(BuildException.class, () -> createResolveDependenciesTask().execute());
		assertFalse(AntTestUtilities.containsAnyFile(repositoryRoot), "Nothing must be cached");
	}

	@Test
	void resolveDependenciesTrustsTaskCertificateFileForVersionsJsonAndDownload() throws Exception {
		final ResolveDependenciesTask task = createResolveDependenciesTask();
		task.setTlsCertificateFile("test-root.pem");
		task.execute();

		assertTrue(AntTestUtilities.findFileWithContent(repositoryRoot, jarBytes).isPresent());
	}

	@Test
	void resolveDependenciesTrustsDependencyCertificateFileForDownload() throws Exception {
		jarBytes = AntTestUtilities.createJarBytes("templatelib 1.2.3");
		testHttpsServer.addFile("/files/templatelib-1.2.3.jar", jarBytes);

		final ResolveDependenciesTask task = new ResolveDependenciesTask();
		task.setProject(AntTestUtilities.createProject(baseDir));
		task.setRepositoryRoot(repositoryRoot.toString());
		final ResolveDependenciesTask.DependencyElement dependencyElement = task.createDependency();
		dependencyElement.setName("templatelib");
		dependencyElement.setVersion("1.2.3");
		dependencyElement.setUrl(testHttpsServer.getBaseUrl() + "/files/{name}-{version}.jar");
		dependencyElement.setTlsCertificateFile("test-root.pem");
		task.execute();

		assertTrue(AntTestUtilities.findFileWithContent(repositoryRoot, jarBytes).isPresent());
	}
}
