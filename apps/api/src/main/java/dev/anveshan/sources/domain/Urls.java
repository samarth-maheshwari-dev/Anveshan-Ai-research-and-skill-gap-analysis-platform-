package dev.anveshan.sources.domain;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * URL canonicalization for de-duplication (TECHSPEC §11.2 step 1): lower-case scheme and host, default port and
 * fragment dropped, tracking parameters ({@code utm_*}, {@code fbclid}, …) removed. Pure.
 */
public final class Urls {

	private static final Set<String> TRACKING = Set.of("fbclid", "gclid", "msclkid", "mc_cid", "mc_eid", "igshid",
			"ref_src", "_hsenc", "_hsmsd", "yclid");

	private Urls() {
	}

	/** {@code HTTPS://Example.com/a?utm_source=x#top} → {@code https://example.com/a}; not http(s) → null. */
	public static String canonical(String url) {
		if (url == null || url.isBlank()) {
			return null;
		}
		URI u;
		try {
			u = new URI(url.strip());
		}
		catch (URISyntaxException e) {
			return null;
		}
		if (u.getScheme() == null || u.getHost() == null) {
			return null;
		}
		String scheme = u.getScheme().toLowerCase(Locale.ROOT);
		if (!scheme.equals("http") && !scheme.equals("https")) {
			return null;
		}
		String host = u.getHost().toLowerCase(Locale.ROOT);
		if (host.endsWith(".")) {
			host = host.substring(0, host.length() - 1);
		}
		int port = u.getPort();
		boolean defaultPort = port == -1 || (scheme.equals("https") && port == 443) || (scheme.equals("http") && port == 80);
		String path = u.getRawPath() == null || u.getRawPath().isEmpty() ? "/" : u.getRawPath();
		String query = cleanQuery(u.getRawQuery());
		StringBuilder out = new StringBuilder(scheme).append("://").append(host);
		if (!defaultPort) {
			out.append(':').append(port);
		}
		out.append(path.equals("/") ? "" : path);
		if (query != null) {
			out.append('?').append(query);
		}
		return out.toString();
	}

	/** Host of a URL, lower-case, without {@code www.}; null if none. */
	public static String host(String url) {
		try {
			String h = new URI(url).getHost();
			if (h == null) {
				return null;
			}
			h = h.toLowerCase(Locale.ROOT);
			return h.startsWith("www.") ? h.substring(4) : h;
		}
		catch (URISyntaxException | NullPointerException e) {
			return null;
		}
	}

	/** True when {@code url}'s host is {@code site} or one of its sub-domains. */
	public static boolean onSite(String url, String site) {
		String h = host(url);
		return h != null && (h.equals(site) || h.endsWith("." + site));
	}

	private static String cleanQuery(String raw) {
		if (raw == null || raw.isEmpty()) {
			return null;
		}
		List<String> kept = new ArrayList<>();
		for (String part : raw.split("&")) {
			if (part.isEmpty()) {
				continue;
			}
			String name = part.split("=", 2)[0].toLowerCase(Locale.ROOT);
			if (name.startsWith("utm_") || TRACKING.contains(name)) {
				continue;
			}
			kept.add(part);
		}
		return kept.isEmpty() ? null : String.join("&", kept);
	}

}
