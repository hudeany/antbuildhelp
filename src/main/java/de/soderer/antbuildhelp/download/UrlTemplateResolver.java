package de.soderer.antbuildhelp.download;

import java.util.Map;

/**
 * Replaces placeholders in a download URL template.
 * Supports both {curly} template placeholders (name/version/baseUrl/...) and
 * legacy <angle> placeholders (username/password), as used in the existing Versions.json format.
 */
public class UrlTemplateResolver {

	public static String resolve(final String urlTemplate, final Map<String, String> values) {
		String result = urlTemplate;
		for (final Map.Entry<String, String> entry : values.entrySet()) {
			result = result.replace("{" + entry.getKey() + "}", entry.getValue());
			result = result.replace("<" + entry.getKey() + ">", entry.getValue());
		}
		return result;
	}
}
