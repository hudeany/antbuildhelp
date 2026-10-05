package de.soderer.antbuildhelp.download;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.InetSocketAddress;

import org.junit.jupiter.api.Test;

/** Tests for the proxy url parsing of {@link HttpClientFactory} */
@SuppressWarnings("static-method")
class HttpClientFactoryTest {
	@Test
	void proxyUrlWithScheme() {
		final InetSocketAddress address = HttpClientFactory.parseProxyAddress("http://proxy.invalid:8080");
		assertEquals("proxy.invalid", address.getHostString());
		assertEquals(8080, address.getPort());
	}

	@Test
	void proxyUrlWithoutScheme() {
		final InetSocketAddress address = HttpClientFactory.parseProxyAddress("proxy.invalid:3128");
		assertEquals("proxy.invalid", address.getHostString());
		assertEquals(3128, address.getPort());
	}

	@Test
	void proxyUrlWithoutPortDefaultsTo80() {
		assertEquals(80, HttpClientFactory.parseProxyAddress("http://proxy.invalid").getPort());
	}

	@Test
	void proxyUrlWithoutHostFails() {
		assertThrows(IllegalArgumentException.class, () -> HttpClientFactory.parseProxyAddress("http://:8080"));
	}
}
