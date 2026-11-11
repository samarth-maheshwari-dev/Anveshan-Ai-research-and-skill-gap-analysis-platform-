package dev.anveshan.research.domain;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Checks the New Research form's free-text options (owner decision D-97): custom objective / format, own report
 * sections, extra instructions and preferred / avoided websites. Pure; every message is shown to the user as is.
 * The text is user data: prompts wrap it in tags and never follow it (RULES §10).
 */
public final class CustomRules {

	public static final int LABEL_MAX = 120;
	public static final int INSTRUCTIONS_MAX = 500;
	public static final int SECTIONS_MAX = 12;
	public static final int SECTION_MIN_CHARS = 2;
	public static final int SECTION_MAX_CHARS = 80;
	public static final int SITES_MAX = 10;
	public static final int FIRST_YEAR = 1990;

	private static final Pattern SPACES = Pattern.compile("\\s+");
	/** A public host name: labels of letters/digits/hyphens, a letter TLD. No IPs, no ports, no paths. */
	private static final Pattern HOST = Pattern
			.compile("^(?=.{4,253}$)([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z]{2,63}$");

	private CustomRules() {
	}

	/** Trimmed, inner whitespace collapsed; blank → null; longer than {@code max} characters → error. */
	public static String text(String value, String field, int max) {
		if (value == null) {
			return null;
		}
		String v = SPACES.matcher(value.strip()).replaceAll(" ");
		if (v.isEmpty()) {
			return null;
		}
		if (v.codePointCount(0, v.length()) > max) {
			throw new IllegalArgumentException(field + " must be at most " + max + " characters.");
		}
		return v;
	}

	/** Own report sections in order: 1–12 distinct titles of 2–80 characters. Null or empty → null (not set). */
	public static List<String> sections(List<String> titles) {
		if (titles == null || titles.isEmpty()) {
			return null;
		}
		if (titles.size() > SECTIONS_MAX) {
			throw new IllegalArgumentException("A report can have at most " + SECTIONS_MAX + " sections.");
		}
		List<String> out = new ArrayList<>();
		Set<String> seen = new LinkedHashSet<>();
		for (String t : titles) {
			String v = text(t, "A section title", SECTION_MAX_CHARS);
			if (v == null || v.codePointCount(0, v.length()) < SECTION_MIN_CHARS) {
				throw new IllegalArgumentException("Each section needs a title of " + SECTION_MIN_CHARS + "–"
						+ SECTION_MAX_CHARS + " characters.");
			}
			if (!seen.add(v.toLowerCase(Locale.ROOT))) {
				throw new IllegalArgumentException("Two sections are called \"" + v + "\".");
			}
			out.add(v);
		}
		return List.copyOf(out);
	}

	/**
	 * Website names as host names: "https://www.IEEE.org/xplore" → "ieee.org". At most 10, de-duplicated. Null or
	 * empty → null. Only used as search filters (P11) — Anveshan never fetches these names as given.
	 */
	public static List<String> sites(List<String> values, String field) {
		if (values == null || values.isEmpty()) {
			return null;
		}
		Set<String> out = new LinkedHashSet<>();
		for (String raw : values) {
			if (raw == null || raw.isBlank()) {
				continue;
			}
			String h = raw.strip().toLowerCase(Locale.ROOT).replaceFirst("^[a-z][a-z0-9+.-]*://", "");
			int cut = indexOfAny(h, "/?#:");
			if (cut >= 0) {
				h = h.substring(0, cut);
			}
			if (h.startsWith("www.")) {
				h = h.substring(4);
			}
			if (h.endsWith(".")) {
				h = h.substring(0, h.length() - 1);
			}
			if (!HOST.matcher(h).matches()) {
				throw new IllegalArgumentException(field + ": \"" + raw.strip() + "\" isn't a website name (like ieee.org).");
			}
			out.add(h);
		}
		if (out.size() > SITES_MAX) {
			throw new IllegalArgumentException(field + ": at most " + SITES_MAX + " websites.");
		}
		return out.isEmpty() ? null : List.copyOf(out);
	}

	/** A site can't be both preferred and avoided. */
	public static void noOverlap(List<String> include, List<String> exclude) {
		if (include == null || exclude == null) {
			return;
		}
		for (String s : include) {
			if (exclude.contains(s)) {
				throw new IllegalArgumentException(s + " is in both \"prefer\" and \"avoid\".");
			}
		}
	}

	/** SINCE_YEAR needs a year between 1990 and this year. */
	public static int sinceYear(Integer year, int currentYear) {
		if (year == null) {
			throw new IllegalArgumentException("Choose the year to start from.");
		}
		if (year < FIRST_YEAR || year > currentYear) {
			throw new IllegalArgumentException("Choose a year between " + FIRST_YEAR + " and " + currentYear + ".");
		}
		return year;
	}

	private static int indexOfAny(String s, String chars) {
		for (int i = 0; i < s.length(); i++) {
			if (chars.indexOf(s.charAt(i)) >= 0) {
				return i;
			}
		}
		return -1;
	}

}
