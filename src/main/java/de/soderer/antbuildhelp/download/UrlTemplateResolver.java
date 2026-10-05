package de.soderer.antbuildhelp.download;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Replaces placeholders in a download URL template.
 * Supports both {curly} template placeholders (name/version/username/password) and
 * legacy &lt;angle&gt; placeholders (username/password), as used in the existing Versions.json format.
 *
 * Substituted values are percent-encoded (RFC 3986), so values containing reserved characters
 * such as "&amp;", "#", "/", "@", "%" or spaces (typically passwords) cannot break the url
 * structure. Values must therefore be given raw, not already url-encoded.
 */
public final class UrlTemplateResolver {

	/** Matches a "{key}" or "&lt;key&gt;" placeholder, capturing the key in group 1 or 2 */
	private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile("\\{([^{}<>]+)\\}|<([^{}<>]+)>");

	/** Hex digits for percent-encoding */
	private static final char[] HEX_DIGITS = "0123456789ABCDEF".toCharArray();

	/**
	 * Utility class, not instantiable.
	 */
	private UrlTemplateResolver() {
		// Utility class
	}

	/**
	 * Replaces all "{key}" and "&lt;key&gt;" placeholders whose key is contained in values with
	 * the percent-encoded value (see {@link #encodeUrlComponent(String)}). Placeholders with
	 * unknown keys are left unchanged.
	 *
	 * The template is processed in a single pass, so a substituted value is never scanned for
	 * placeholders again (e.g. a password containing "{version}" stays as it is). A null value
	 * is substituted as empty string.
	 *
	 * @param urlTemplate the url template
	 * @param values      the raw (not url-encoded) placeholder values by key
	 * @return the url with all known placeholders replaced
	 * @throws IllegalArgumentException if urlTemplate is null
	 */
	public static String resolve(final String urlTemplate, final Map<String, String> values) {
		if (urlTemplate == null) {
			throw new IllegalArgumentException("Url template must not be null");
		}
		final Matcher matcher = PLACEHOLDER_PATTERN.matcher(urlTemplate);
		final StringBuilder result = new StringBuilder();
		while (matcher.find()) {
			final String key = matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
			final String replacement;
			if (values.containsKey(key)) {
				replacement = encodeUrlComponent(values.get(key));
			} else {
				replacement = matcher.group();
			}
			matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
		}
		matcher.appendTail(result);
		return result.toString();
	}

	/**
	 * Percent-encodes a value for use inside any url component (user info, path segment, query
	 * parameter or fragment): all UTF-8 bytes except the RFC 3986 unreserved characters
	 * (A-Z, a-z, 0-9, "-", ".", "_", "~") are encoded as "%XX". A space becomes "%20", not "+",
	 * because "+" is only decoded as space in form-encoded query strings, but not in paths.
	 *
	 * @param value the raw value, may be null
	 * @return the encoded value, empty string for null
	 */
	public static String encodeUrlComponent(final String value) {
		if (value == null) {
			return "";
		}
		final StringBuilder encoded = new StringBuilder(value.length());
		for (final byte valueByte : value.getBytes(StandardCharsets.UTF_8)) {
			final int unsignedByte = valueByte & 0xFF;
			if (isUnreserved(unsignedByte)) {
				encoded.append((char) unsignedByte);
			} else {
				encoded.append('%').append(HEX_DIGITS[unsignedByte >> 4]).append(HEX_DIGITS[unsignedByte & 0x0F]);
			}
		}
		return encoded.toString();
	}

	/**
	 * Checks for an RFC 3986 unreserved character, which never needs encoding.
	 *
	 * @param unsignedByte the byte value (0-255)
	 * @return true for A-Z, a-z, 0-9, "-", ".", "_" and "~"
	 */
	private static boolean isUnreserved(final int unsignedByte) {
		return (unsignedByte >= 'A' && unsignedByte <= 'Z')
				|| (unsignedByte >= 'a' && unsignedByte <= 'z')
				|| (unsignedByte >= '0' && unsignedByte <= '9')
				|| unsignedByte == '-' || unsignedByte == '.' || unsignedByte == '_' || unsignedByte == '~';
	}
}
