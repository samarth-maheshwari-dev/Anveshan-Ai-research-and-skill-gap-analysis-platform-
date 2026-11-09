package dev.anveshan.shared.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetAddress;
import java.net.URI;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class OutboundUrlGuardTest {

	OutboundUrlGuard guard = new OutboundUrlGuard();

	@ParameterizedTest(name = "{0}")
	@ValueSource(strings = { "127.0.0.1", "127.255.255.254", "0.0.0.0", "10.0.0.1", "10.255.255.255", "172.16.0.1",
			"172.31.255.255", "192.168.1.1", "169.254.169.254", "169.254.0.1", "100.64.0.1", "100.127.255.255",
			"224.0.0.1", "255.255.255.255", "198.18.0.1", "192.0.2.1", "::1", "::", "fc00::1", "fd12:3456::1",
			"fe80::1", "::ffff:127.0.0.1", "::ffff:10.0.0.1", "::ffff:169.254.169.254", "64:ff9b::a9fe:a9fe",
			"2002:a9fe:a9fe::1", "ff02::1" })
	@DisplayName("T-06-01 private, loopback, link-local, CGNAT, ULA and IPv4-in-IPv6 addresses are blocked")
	void blocksNonPublic(String ip) throws Exception {
		assertThat(OutboundUrlGuard.isBlocked(InetAddress.getByName(ip))).isTrue();
		String host = ip.contains(":") ? "[" + ip + "]" : ip;
		assertThatThrownBy(() -> guard.requireSafe(URI.create("https://" + host + "/x"), OutboundUrlGuard.HTTPS_ONLY))
				.isInstanceOf(UnsafeUrlException.class);
	}

	@ParameterizedTest(name = "{0}")
	@ValueSource(strings = { "8.8.8.8", "1.1.1.1", "172.15.255.255", "172.32.0.1", "100.63.255.255", "100.128.0.1",
			"2606:4700:4700::1111", "2001:4860:4860::8888" })
	@DisplayName("T-06-01 public addresses are allowed (no false positives at range edges)")
	void allowsPublic(String ip) throws Exception {
		assertThat(OutboundUrlGuard.isBlocked(InetAddress.getByName(ip))).isFalse();
	}

	@ParameterizedTest(name = "{0}")
	@ValueSource(strings = { "http://2130706433/", "http://0x7f000001/", "http://0177.0.0.1/", "http://127.1/",
			"http://0x7f.0.0.1/", "http://017700000001/", "http://0/", "http://127.0.0.1./" })
	@DisplayName("T-06-02 decimal / hex / octal / short IPv4 spellings are rejected")
	void blocksNumericTricks(String url) {
		assertThatThrownBy(() -> guard.requireSafe(URI.create(url), OutboundUrlGuard.WEB_PAGE))
				.isInstanceOf(UnsafeUrlException.class);
	}

	@Test
	@DisplayName("T-06-03 user-info URLs and non-https provider URLs are rejected with UNSAFE_URL")
	void rejectsUserInfoAndHttp() {
		assertThatThrownBy(() -> guard.requireSafe(URI.create("https://user@api.groq.com/v1"), OutboundUrlGuard.HTTPS_ONLY))
				.isInstanceOf(UnsafeUrlException.class).hasMessageContaining("user names");
		assertThatThrownBy(() -> guard.requireSafe(URI.create("https://user:pw@api.groq.com/v1"), OutboundUrlGuard.HTTPS_ONLY))
				.isInstanceOf(UnsafeUrlException.class);
		assertThatThrownBy(() -> guard.requireSafe(URI.create("http://api.groq.com/v1"), OutboundUrlGuard.HTTPS_ONLY))
				.isInstanceOf(UnsafeUrlException.class).hasMessageContaining("https");
		assertThatThrownBy(() -> guard.requireSafe(URI.create("ftp://example.com/x"), OutboundUrlGuard.WEB_PAGE))
				.isInstanceOf(UnsafeUrlException.class);
		assertThatThrownBy(() -> guard.requireSafe(URI.create("file:///etc/passwd"), OutboundUrlGuard.WEB_PAGE))
				.isInstanceOf(UnsafeUrlException.class);
		assertThat(UnsafeUrlException.CODE).isEqualTo("UNSAFE_URL");
	}

	@Test
	@DisplayName("T-06-03 ports other than 443 (or 80 for http) need an explicit allow-list; local names refused")
	void portsAndLocalNames() {
		assertThatThrownBy(() -> guard.requireSafe(URI.create("https://example.com:8081/"), OutboundUrlGuard.HTTPS_ONLY))
				.isInstanceOf(UnsafeUrlException.class).hasMessageContaining("Port");
		assertThat(guard.requireSafe(URI.create("https://example.com:8443/"), OutboundUrlGuard.HTTPS_ONLY.withExtraPort(8443)))
				.hasPort(8443);
		for (String url : new String[] { "https://localhost/", "https://foo.localhost/", "https://printer.local/",
				"https://metadata.google.internal/" }) {
			assertThatThrownBy(() -> guard.requireSafe(URI.create(url), OutboundUrlGuard.HTTPS_ONLY))
					.as(url).isInstanceOf(UnsafeUrlException.class);
		}
	}

	@Test
	@DisplayName("T-06-03 normal provider and web URLs pass and are normalized")
	void allowsNormalUrls() {
		assertThat(guard.requireSafe(URI.create("https://api.groq.com/openai/v1"), OutboundUrlGuard.HTTPS_ONLY))
				.hasHost("api.groq.com");
		assertThat(guard.requireSafe(URI.create("https://8.8.8.8/x"), OutboundUrlGuard.HTTPS_ONLY)).hasHost("8.8.8.8");
		assertThat(guard.requireSafe(URI.create("http://example.org/a/../b"), OutboundUrlGuard.WEB_PAGE))
				.hasPath("/b");
	}

}
