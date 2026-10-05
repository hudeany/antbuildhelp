package de.soderer.antbuildhelp.download;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

/** Tests for {@link UrlTemplateResolver} */
@SuppressWarnings("static-method")
class UrlTemplateResolverTest {
	@Test
	void curlyAndAngleBracketPlaceholdersAreReplaced() {
		final Map<String, String> values = Map.of("name", "csv", "version", "1.2.3", "username", "me", "password", "secret");

		assertEquals("https://example.com/csv-1.2.3.jar?u=me&p=secret",
				UrlTemplateResolver.resolve("https://example.com/{name}-{version}.jar?u=<username>&p=<password>", values));
	}

	@Test
	void unknownPlaceholdersAreLeftUnchanged() {
		assertEquals("https://example.com/{other}/<other>/1.0",
				UrlTemplateResolver.resolve("https://example.com/{other}/<other>/{version}", Map.of("version", "1.0")));
	}

	@Test
	void substitutedValuesAreNotScannedForPlaceholdersAgain() {
		final Map<String, String> values = Map.of("version", "1.0", "password", "a{version}b<version>$1\\");

		assertEquals("https://example.com/1.0?p=a%7Bversion%7Db%3Cversion%3E%241%5C",
				UrlTemplateResolver.resolve("https://example.com/{version}?p=<password>", values));
	}

	@Test
	void nullValueIsSubstitutedAsEmptyString() {
		final Map<String, String> values = new HashMap<>();
		values.put("password", null);

		assertEquals("https://example.com/?p=", UrlTemplateResolver.resolve("https://example.com/?p=<password>", values));
	}

	@Test
	void nullTemplateFails() {
		assertThrows(IllegalArgumentException.class, () -> UrlTemplateResolver.resolve(null, Map.of()));
	}

	@Test
	void reservedCharactersInPasswordAreEncoded() {
		final Map<String, String> values = Map.of("username", "max@firma.de", "password", "p&ss=w#rd/1 %+?");

		assertEquals("https://example.com/download?user=max%40firma.de&pw=p%26ss%3Dw%23rd%2F1%20%25%2B%3F",
				UrlTemplateResolver.resolve("https://example.com/download?user=<username>&pw=<password>", values));
	}

	@Test
	void nonAsciiCharactersAreEncodedAsUtf8() {
		assertEquals("%C3%A4%C3%B6%C3%BC%C3%9F%E2%82%AC", UrlTemplateResolver.encodeUrlComponent("\u00e4\u00f6\u00fc\u00df\u20ac"));
	}

	@Test
	void unreservedCharactersStayUnchanged() {
		assertEquals("AZaz09-._~", UrlTemplateResolver.encodeUrlComponent("AZaz09-._~"));
		assertEquals("26.0.82", UrlTemplateResolver.encodeUrlComponent("26.0.82"));
	}
}
