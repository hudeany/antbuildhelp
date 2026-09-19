package de.soderer.antbuildhelp.versions;

import java.util.LinkedHashMap;
import java.util.Map;

import de.soderer.json.JsonNode;
import de.soderer.json.JsonObject;
import de.soderer.json.JsonReader;

/**
 * Parsed representation of the central Versions.json file.
 * Uses the existing de.soderer.json library for parsing.
 */
public class VersionsJson {

	private final Map<String, VersionsJsonEntry> entriesByName = new LinkedHashMap<>();

	public static VersionsJson parse(final String jsonContent) throws Exception {
		final VersionsJson result = new VersionsJson();
			final JsonNode rootNode = JsonReader.readJsonItemString(jsonContent);
			final JsonObject rootObject = (JsonObject) rootNode;
			for (final String artifactName : rootObject.keySet()) {
				final JsonObject entryObject = (JsonObject) rootObject.getSimpleValue(artifactName);
				final VersionsJsonEntry entry = new VersionsJsonEntry()
						.withVersion((String) entryObject.getSimpleValue("version"))
						.withDownloadUrl((String) entryObject.getSimpleValue("downloadUrl"));
				result.entriesByName.put(artifactName, entry);
			}
		return result;
	}

	public VersionsJsonEntry getEntry(final String artifactName) {
		return entriesByName.get(artifactName);
	}

	public boolean containsEntry(final String artifactName) {
		return entriesByName.containsKey(artifactName);
	}
}
