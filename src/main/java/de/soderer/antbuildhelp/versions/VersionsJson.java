package de.soderer.antbuildhelp.versions;

import java.util.LinkedHashMap;
import java.util.Map;

import de.soderer.json.JsonNode;
import de.soderer.json.JsonObject;
import de.soderer.json.JsonReader;

/**
 * Parsed representation of the central Versions.json file, a flat JSON object mapping each
 * artifact name to an object with "version" and "downloadUrl":
 *
 * <pre>{@code
 * {
 *     "RestClient": { "version": "26.0.82", "downloadUrl": "https://example.com/index.php?download=RestClient.jar" }
 * }
 * }</pre>
 *
 * Uses the existing de.soderer.json library for parsing.
 */
public class VersionsJson {

	/** Entries by artifact name, in file order */
	private final Map<String, VersionsJsonEntry> entriesByName = new LinkedHashMap<>();

	/**
	 * Creates an empty instance; use {@link #parse(String)} to read a Versions.json file.
	 */
	public VersionsJson() {
		// Nothing to initialize
	}

	/**
	 * Parses the content of a Versions.json file.
	 *
	 * @param jsonContent the JSON text
	 * @return the parsed entries
	 * @throws Exception if the text is no valid JSON, or does not have the expected structure
	 */
	public static VersionsJson parse(final String jsonContent) throws Exception {
		final VersionsJson result = new VersionsJson();
		final JsonNode rootNode = JsonReader.readJsonItemString(jsonContent);
		if (!(rootNode instanceof JsonObject)) {
			throw new IllegalArgumentException("Invalid Versions.json: root element must be a JSON object");
		}
		final JsonObject rootObject = (JsonObject) rootNode;
		for (final String artifactName : rootObject.keySet()) {
			final Object entryValue = rootObject.getSimpleValue(artifactName);
			if (!(entryValue instanceof JsonObject)) {
				throw new IllegalArgumentException("Invalid Versions.json: entry '" + artifactName + "' must be a JSON object");
			}
			final JsonObject entryObject = (JsonObject) entryValue;
			final VersionsJsonEntry entry = new VersionsJsonEntry()
					.withVersion(getOptionalString(entryObject, artifactName, "version"))
					.withDownloadUrl(getOptionalString(entryObject, artifactName, "downloadUrl"));
			result.entriesByName.put(artifactName, entry);
		}
		return result;
	}

	/**
	 * Reads an optional string property of an entry.
	 *
	 * @param entryObject  the entry object
	 * @param artifactName the entry's artifact name, for error messages
	 * @param propertyName the property to read
	 * @return the string value, or null if the property is missing or JSON null
	 * @throws IllegalArgumentException if the property has a non-string value
	 */
	private static String getOptionalString(final JsonObject entryObject, final String artifactName, final String propertyName) {
		final Object value = entryObject.getSimpleValue(propertyName);
		if (value != null && !(value instanceof String)) {
			throw new IllegalArgumentException("Invalid Versions.json: property '" + propertyName + "' of entry '" + artifactName + "' must be a string");
		}
		return (String) value;
	}

	/**
	 * Returns the entry of an artifact.
	 *
	 * @param artifactName the artifact name (case-sensitive)
	 * @return the entry, or null if the file has no entry for this name
	 */
	public VersionsJsonEntry getEntry(final String artifactName) {
		return entriesByName.get(artifactName);
	}

	/**
	 * Checks whether the file has an entry for an artifact.
	 *
	 * @param artifactName the artifact name (case-sensitive)
	 * @return true if an entry exists
	 */
	public boolean containsEntry(final String artifactName) {
		return entriesByName.containsKey(artifactName);
	}
}
