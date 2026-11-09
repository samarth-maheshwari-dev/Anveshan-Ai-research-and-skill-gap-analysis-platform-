package dev.anveshan.shared.security;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * SSRF guard for every user-influenced outbound URL (TECHSPEC §6.4).
 *
 * <p>Two layers: {@link #requireSafe} checks the URL text before any request, and
 * {@link SafeDnsResolver} checks the actual IPs the socket will connect to (no DNS-rebinding gap).
 */
public final class OutboundUrlGuard {

	/** What a caller allows. Provider base URLs: {@link #HTTPS_ONLY}; web pages may allow http. */
	public record Policy(boolean allowHttp, Set<Integer> extraPorts) {

		public Policy {
			extraPorts = Set.copyOf(extraPorts);
		}

		public Policy withExtraPort(int port) {
			var ports = new java.util.HashSet<>(extraPorts);
			ports.add(port);
			return new Policy(allowHttp, ports);
		}

	}

	public static final Policy HTTPS_ONLY = new Policy(false, Set.of());
	public static final Policy WEB_PAGE = new Policy(true, Set.of());

	/** Hosts made only of digits, dots and hex markers — any IPv4 spelling (decimal, octal, hex, short). */
	private static final Pattern NUMERIC_HOST = Pattern.compile("^(0x[0-9a-f]+|[0-9]+)(\\.(0x[0-9a-f]*|[0-9]+))*\\.?$",
			Pattern.CASE_INSENSITIVE);
	private static final Pattern CANONICAL_IPV4 = Pattern
			.compile("^(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)(\\.(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)){3}$");

	private final Predicate<InetAddress> blocked;

	/** Production guard: blocks every non-public address. */
	public OutboundUrlGuard() {
		this(OutboundUrlGuard::isBlocked);
	}

	/** Tests may pass a narrower predicate (e.g. to let a local fake server count as "public"). */
	public OutboundUrlGuard(Predicate<InetAddress> blocked) {
		this.blocked = blocked;
	}

	public boolean blocks(InetAddress address) {
		return blocked.test(address);
	}

	/** @return the normalized URI, safe to request; @throws UnsafeUrlException otherwise */
	public URI requireSafe(URI uri, Policy policy) {
		if (uri == null || !uri.isAbsolute() || uri.isOpaque()) {
			throw new UnsafeUrlException("URL must be absolute");
		}
		String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
		if (!scheme.equals("https") && !(policy.allowHttp() && scheme.equals("http"))) {
			throw new UnsafeUrlException(policy.allowHttp() ? "Only http and https URLs are allowed" : "Only https URLs are allowed");
		}
		if (uri.getRawUserInfo() != null || (uri.getRawAuthority() != null && uri.getRawAuthority().contains("@"))) {
			throw new UnsafeUrlException("URLs with user names or passwords are not allowed");
		}
		String host = uri.getHost();
		if (host == null || host.isBlank()) {
			throw new UnsafeUrlException("URL has no host");
		}
		host = host.toLowerCase(Locale.ROOT);
		int port = uri.getPort();
		if (port != -1 && port != 443 && !(scheme.equals("http") && port == 80) && !policy.extraPorts().contains(port)) {
			throw new UnsafeUrlException("Port " + port + " is not allowed");
		}
		if (host.equals("localhost") || host.endsWith(".localhost") || host.endsWith(".local")
				|| host.endsWith(".internal")) {
			throw new UnsafeUrlException("Local host names are not allowed");
		}
		checkLiteral(host);
		return uri.normalize();
	}

	/** IP literals are checked right here; unusual numeric spellings are refused outright. */
	private void checkLiteral(String host) {
		if (host.startsWith("[") && host.endsWith("]")) {
			InetAddress v6 = parseLiteral(host.substring(1, host.length() - 1));
			if (blocks(v6)) {
				throw new UnsafeUrlException("Private or reserved addresses are not allowed");
			}
			return;
		}
		if (NUMERIC_HOST.matcher(host).matches()) {
			if (!CANONICAL_IPV4.matcher(host).matches()) {
				// 2130706433, 0x7f000001, 0177.0.0.1, 127.1 … all mean 127.0.0.1 to some resolver
				throw new UnsafeUrlException("Unusual IP address formats are not allowed");
			}
			if (blocks(parseLiteral(host))) {
				throw new UnsafeUrlException("Private or reserved addresses are not allowed");
			}
		}
	}

	private static InetAddress parseLiteral(String literal) {
		try {
			return InetAddress.getByName(literal); // literal only — no DNS lookup for IP strings
		}
		catch (UnknownHostException e) {
			throw new UnsafeUrlException("Invalid IP address");
		}
	}

	/**
	 * True for every address that is not plainly public: loopback, any-local, link-local
	 * (incl. 169.254.169.254 cloud metadata), private (10/8, 172.16/12, 192.168/16), CGNAT 100.64/10,
	 * multicast, reserved/benchmark/documentation ranges, IPv6 ULA fc00::/7, and IPv6 forms that
	 * embed an IPv4 address (mapped, compatible, NAT64, 6to4) when that IPv4 is blocked.
	 */
	public static boolean isBlocked(InetAddress a) {
		if (a.isAnyLocalAddress() || a.isLoopbackAddress() || a.isLinkLocalAddress() || a.isSiteLocalAddress()
				|| a.isMulticastAddress()) {
			return true;
		}
		byte[] b = a.getAddress();
		if (a instanceof Inet4Address) {
			return isBlockedV4(b);
		}
		if (a instanceof Inet6Address) {
			return isBlockedV6(b);
		}
		return true;
	}

	private static boolean isBlockedV4(byte[] b) {
		int o0 = b[0] & 0xff;
		int o1 = b[1] & 0xff;
		int o2 = b[2] & 0xff;
		return o0 == 0 // 0.0.0.0/8 "this network"
				|| o0 == 10
				|| o0 == 127
				|| (o0 == 100 && o1 >= 64 && o1 <= 127) // CGNAT 100.64/10
				|| (o0 == 169 && o1 == 254)
				|| (o0 == 172 && o1 >= 16 && o1 <= 31)
				|| (o0 == 192 && o1 == 0 && o2 == 0) // IETF protocol assignments
				|| (o0 == 192 && o1 == 0 && o2 == 2) // TEST-NET-1
				|| (o0 == 192 && o1 == 168)
				|| (o0 == 198 && (o1 == 18 || o1 == 19)) // benchmarking
				|| (o0 == 198 && o1 == 51 && o2 == 100) // TEST-NET-2
				|| (o0 == 203 && o1 == 0 && o2 == 113) // TEST-NET-3
				|| o0 >= 224; // multicast, reserved, broadcast
	}

	private static boolean isBlockedV6(byte[] b) {
		if ((b[0] & 0xfe) == 0xfc) { // fc00::/7 unique local
			return true;
		}
		if ((b[0] & 0xff) == 0xfe && (b[1] & 0xc0) == 0xc0) { // fec0::/10 deprecated site-local
			return true;
		}
		if ((b[0] & 0xff) == 0x20 && (b[1] & 0xff) == 0x01 && b[2] == 0x0d && (b[3] & 0xff) == 0xb8) { // 2001:db8::/32 docs
			return true;
		}
		boolean first10Zero = true;
		for (int i = 0; i < 10; i++) {
			first10Zero &= b[i] == 0;
		}
		if (first10Zero && (((b[10] & 0xff) == 0xff && (b[11] & 0xff) == 0xff) || (b[10] == 0 && b[11] == 0))) {
			return isBlockedV4(new byte[] { b[12], b[13], b[14], b[15] }); // ::ffff:a.b.c.d and ::a.b.c.d
		}
		if ((b[0] & 0xff) == 0x00 && (b[1] & 0xff) == 0x64 && (b[2] & 0xff) == 0xff && (b[3] & 0xff) == 0x9b) {
			return isBlockedV4(new byte[] { b[12], b[13], b[14], b[15] }); // NAT64 64:ff9b::/96
		}
		if ((b[0] & 0xff) == 0x20 && (b[1] & 0xff) == 0x02) {
			return isBlockedV4(new byte[] { b[2], b[3], b[4], b[5] }); // 6to4 2002::/16
		}
		return false;
	}

}
