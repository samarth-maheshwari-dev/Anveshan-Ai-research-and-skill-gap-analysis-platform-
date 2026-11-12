package dev.anveshan.report.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The writer's citation markers (TECHSPEC §12.9): the model writes {@code [c:C3]} (or {@code [c:C3, C5]}) after a
 * sentence; code turns known aliases into {@code [c:<claimId>]} and removes everything else — a marker can only ever
 * point at a claim that was given to that call (T-14-01). Pure.
 */
public final class Markers {

	/** A citation marker as the model writes it (case and spaces don't matter). */
	private static final Pattern MODEL_MARKER = Pattern.compile("\\s?\\[\\s*c\\s*:\\s*([^\\]]{1,200})\\]", Pattern.CASE_INSENSITIVE);
	/** The stored form: one claim, optionally with a specific source (conflict lines). */
	public static final Pattern STORED = Pattern.compile("\\[c:([0-9a-f-]{36})(?::([0-9a-f-]{36}))?\\]");
	private static final Pattern SENTENCE_END = Pattern.compile("(?<=[.!?])\\s+");

	/**
	 * @param markdown      claims cited as {@code [c:<uuid>]}
	 * @param removed       markers (or aliases inside them) that pointed at nothing given to the call
	 * @param unmarked      factual-looking sentences without any marker (a quality metric, TECHSPEC §12.9)
	 */
	public record Resolved(String markdown, int removed, int unmarked) {
	}

	private Markers() {
	}

	public static Resolved resolve(String markdown, Map<String, UUID> aliases) {
		Matcher m = MODEL_MARKER.matcher(markdown == null ? "" : markdown);
		StringBuilder out = new StringBuilder();
		int removed = 0;
		while (m.find()) {
			List<String> known = new ArrayList<>();
			for (String part : m.group(1).split("[,;\\s]+")) {
				String alias = part.strip().toUpperCase(Locale.ROOT);
				if (alias.isEmpty()) {
					continue;
				}
				UUID id = aliases.get(alias);
				if (id == null) {
					removed++;
				}
				else if (!known.contains(id.toString())) {
					known.add(id.toString());
				}
			}
			StringBuilder replacement = new StringBuilder();
			for (String id : known) {
				replacement.append("[c:").append(id).append(']');
			}
			m.appendReplacement(out, Matcher.quoteReplacement(replacement.toString()));
		}
		m.appendTail(out);
		String result = out.toString();
		return new Resolved(result, removed, unmarked(result));
	}

	/** Sentences with a number or ≥ 12 words and no marker, outside headings. */
	static int unmarked(String markdown) {
		int count = 0;
		for (String line : markdown.split("\n")) {
			String l = line.strip();
			if (l.isEmpty() || l.startsWith("#")) {
				continue;
			}
			for (String sentence : SENTENCE_END.split(l)) {
				if (STORED.matcher(sentence).find()) {
					continue;
				}
				boolean factual = sentence.chars().anyMatch(Character::isDigit) || sentence.split("\\s+").length >= 12;
				if (factual) {
					count++;
				}
			}
		}
		return count;
	}

	/** Claim ids cited in a stored markdown text, in order of appearance (repeats kept). */
	public static List<UUID> citedClaims(String markdown) {
		List<UUID> out = new ArrayList<>();
		Matcher m = STORED.matcher(markdown);
		while (m.find()) {
			out.add(UUID.fromString(m.group(1)));
		}
		return out;
	}

}
