package dev.anveshan.sources.infra;

import java.net.URI;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.regex.Pattern;

import org.springframework.web.util.UriComponentsBuilder;

import tools.jackson.databind.JsonNode;

/** Small helpers shared by the search adapters. */
final class AdapterSupport {

	static final int SNIPPET_MAX = 600;

	private static final Pattern TAGS = Pattern.compile("<[^>]{0,500}>");
	private static final Pattern SPACES = Pattern.compile("\\s+");

	private AdapterSupport() {
	}

	/** {@code base} + {@code path} + encoded query parameters (null/blank values are left out). */
	static URI uri(String base, String path, Map<String, String> params) {
		URI root = URI.create(base.endsWith("/") ? base : base + "/").resolve(path);
		UriComponentsBuilder b = UriComponentsBuilder.fromUri(root);
		params.forEach((k, v) -> {
			if (v != null && !v.isBlank()) {
				b.queryParam(k, v);
			}
		});
		return b.encode().build().toUri();
	}

	/** Tags removed, entities for the common cases decoded, whitespace collapsed, at most 600 characters. */
	static String text(String html) {
		if (html == null) {
			return null;
		}
		String t = TAGS.matcher(html).replaceAll(" ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
				.replace("&quot;", "\"").replace("&#39;", "'").replace("&nbsp;", " ");
		t = SPACES.matcher(t).replaceAll(" ").strip();
		if (t.isEmpty()) {
			return null;
		}
		return t.length() <= SNIPPET_MAX ? t : t.substring(0, SNIPPET_MAX - 1) + "…";
	}

	/** "2024-05-01", "2024-05-01T10:00:00Z", "2024" → a date; anything else → null. */
	static LocalDate date(String s) {
		if (s == null || s.isBlank()) {
			return null;
		}
		String v = s.strip();
		try {
			if (v.length() >= 10) {
				return LocalDate.parse(v.substring(0, 10));
			}
			if (v.matches("\\d{4}")) {
				return LocalDate.of(Integer.parseInt(v), 1, 1);
			}
		}
		catch (DateTimeParseException | NumberFormatException e) {
			return null;
		}
		return null;
	}

	/** Text of a JSON field, or null when missing/blank. */
	static String str(JsonNode node, String field) {
		JsonNode v = node.path(field);
		if (v.isMissingNode() || v.isNull()) {
			return null;
		}
		String s = v.asString("");
		return s.isBlank() ? null : s;
	}

}
