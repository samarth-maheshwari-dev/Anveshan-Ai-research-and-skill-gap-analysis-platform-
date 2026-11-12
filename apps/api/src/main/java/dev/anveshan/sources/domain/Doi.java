package dev.anveshan.sources.domain;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** DOI normalization (TECHSPEC §11.2): every spelling → {@code https://doi.org/<lower-case doi>}. Pure. */
public final class Doi {

	public static final String PREFIX = "https://doi.org/";

	/** A DOI: "10." + 4–9 digit registrant + "/" + suffix (no spaces). */
	private static final Pattern DOI = Pattern.compile("(10\\.\\d{4,9}/[^\\s\"<>]+)");

	private Doi() {
	}

	/**
	 * {@code 10.1000/ABC}, {@code doi:10.1000/abc}, {@code https://dx.doi.org/10.1000/ABC.} … →
	 * {@code https://doi.org/10.1000/abc}; no DOI inside → null.
	 */
	public static String normalize(String value) {
		if (value == null) {
			return null;
		}
		String v = value.strip();
		try {
			v = java.net.URLDecoder.decode(v.replace("+", "%2B"), java.nio.charset.StandardCharsets.UTF_8);
		}
		catch (IllegalArgumentException e) {
			// a stray "%" isn't an escape: keep the text as it is
		}
		Matcher m = DOI.matcher(v);
		if (!m.find()) {
			return null;
		}
		String doi = m.group(1).toLowerCase(Locale.ROOT);
		while (!doi.isEmpty() && ".,;:)]}'".indexOf(doi.charAt(doi.length() - 1)) >= 0) {
			doi = doi.substring(0, doi.length() - 1); // sentence punctuation after a DOI is not part of it
		}
		return PREFIX + doi;
	}

}
