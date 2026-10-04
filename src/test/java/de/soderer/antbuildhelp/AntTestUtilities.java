package de.soderer.antbuildhelp;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Optional;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.apache.tools.ant.Project;

/** Shared helpers for the Ant task tests */
final class AntTestUtilities {

	private AntTestUtilities() {
		// Utility class
	}

	/** Creates an initialized Ant project with the given base directory, as Ant would for a build.xml */
	static Project createProject(final Path baseDir) {
		final Project project = new Project();
		project.init();
		project.setBaseDir(baseDir.toFile());
		return project;
	}

	/** Creates a small, valid jar whose content is unique for the given marker text */
	static byte[] createJarBytes(final String marker) {
		try (ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream()) {
			try (JarOutputStream jarOutputStream = new JarOutputStream(byteArrayOutputStream)) {
				jarOutputStream.putNextEntry(new ZipEntry("marker.txt"));
				jarOutputStream.write(marker.getBytes(StandardCharsets.UTF_8));
				jarOutputStream.closeEntry();
			}
			return byteArrayOutputStream.toByteArray();
		} catch (final IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	/** Creates a zip archive containing one entry with the given name and content */
	static byte[] createZipBytes(final String entryName, final byte[] entryContent) {
		try (ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream()) {
			try (ZipOutputStream zipOutputStream = new ZipOutputStream(byteArrayOutputStream)) {
				zipOutputStream.putNextEntry(new ZipEntry("readme.txt"));
				zipOutputStream.write("Some other entry".getBytes(StandardCharsets.UTF_8));
				zipOutputStream.closeEntry();
				zipOutputStream.putNextEntry(new ZipEntry(entryName));
				zipOutputStream.write(entryContent);
				zipOutputStream.closeEntry();
			}
			return byteArrayOutputStream.toByteArray();
		} catch (final IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	/** Lowercase hex digest; algorithm is the checksum file extension: "md5", "sha1", "sha256" or "sha512" */
	static String checksum(final byte[] data, final String algorithm) {
		final String javaAlgorithmName;
		switch (algorithm) {
			case "md5":
				javaAlgorithmName = "MD5";
				break;
			case "sha1":
				javaAlgorithmName = "SHA-1";
				break;
			case "sha256":
				javaAlgorithmName = "SHA-256";
				break;
			case "sha512":
				javaAlgorithmName = "SHA-512";
				break;
			default:
				throw new IllegalArgumentException("Unknown checksum algorithm: " + algorithm);
		}
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance(javaAlgorithmName).digest(data));
		} catch (final NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

	/** True if there is any regular file below rootDirectory (directories alone do not count) */
	static boolean containsAnyFile(final Path rootDirectory) {
		if (!Files.isDirectory(rootDirectory)) {
			return false;
		}
		try (Stream<Path> paths = Files.walk(rootDirectory)) {
			return paths.anyMatch(Files::isRegularFile);
		} catch (final IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	/**
	 * Searches recursively for a file with exactly the given content, for checks where the exact
	 * target path is an implementation detail (e.g. the local repository layout)
	 */
	static Optional<Path> findFileWithContent(final Path rootDirectory, final byte[] content) {
		if (!Files.isDirectory(rootDirectory)) {
			return Optional.empty();
		}
		try (Stream<Path> paths = Files.walk(rootDirectory)) {
			return paths.filter(Files::isRegularFile).filter(path -> {
				try {
					return Files.size(path) == content.length && Arrays.equals(Files.readAllBytes(path), content);
				} catch (final IOException e) {
					throw new UncheckedIOException(e);
				}
			}).findFirst();
		} catch (final IOException e) {
			throw new UncheckedIOException(e);
		}
	}
}
