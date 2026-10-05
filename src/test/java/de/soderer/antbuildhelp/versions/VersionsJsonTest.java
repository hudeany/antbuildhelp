package de.soderer.antbuildhelp.versions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Tests for {@link VersionsJson} */
@SuppressWarnings("static-method")
class VersionsJsonTest {
	@Test
	void entriesAreParsed() throws Exception {
		final VersionsJson versionsJson = VersionsJson.parse("{ \"csv\": { \"version\": \"1.2.3\", \"downloadUrl\": \"https://example.com/csv.jar\" },"
				+ " \"json\": { \"version\": \"4.5.6\" } }");

		assertTrue(versionsJson.containsEntry("csv"));
		assertEquals("1.2.3", versionsJson.getEntry("csv").getVersion());
		assertEquals("https://example.com/csv.jar", versionsJson.getEntry("csv").getDownloadUrl());
		assertNull(versionsJson.getEntry("json").getDownloadUrl());
		assertFalse(versionsJson.containsEntry("yaml"));
	}

	@Test
	void nonObjectRootFails() {
		final IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> VersionsJson.parse("[]"));
		assertTrue(exception.getMessage().contains("root element"), exception.getMessage());
	}

	@Test
	void nonObjectEntryFails() {
		final IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> VersionsJson.parse("{ \"csv\": \"1.2.3\" }"));
		assertTrue(exception.getMessage().contains("'csv'"), exception.getMessage());
	}

	@Test
	void nonStringVersionFails() {
		final IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> VersionsJson.parse("{ \"csv\": { \"version\": 1 } }"));
		assertTrue(exception.getMessage().contains("'version'"), exception.getMessage());
	}
}
