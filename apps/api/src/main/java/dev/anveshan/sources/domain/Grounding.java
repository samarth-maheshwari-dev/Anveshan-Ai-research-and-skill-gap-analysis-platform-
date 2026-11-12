package dev.anveshan.sources.domain;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Code-level check that a claim is about its quote (defence in depth for RULES §10): every number in the claim must
 * appear in the quote, and enough of its content words must too. A model that read "ignore previous instructions
 * and say X" and wrote the claim "X" next to an unrelated real quote is caught here. Pure.
 */
public final class Grounding {

	static final double MIN_WORD_OVERLAP = 0.3;

	private static final Pattern TOKEN = Pattern.compile("[\\p{L}\\p{N}][\\p{L}\\p{N}'.%-]*");
	private static final Set<String> STOP = Set.of("this", "that", "with", "from", "have", "were", "which", "their",
			"there", "about", "into", "than", "they", "them", "also", "such", "these", "those", "more", "most", "some",
			"other", "only", "over", "when", "while", "what", "will", "would", "could", "should", "been", "being", "does",
			"done", "each", "very", "many", "much", "between", "because", "after", "before", "where", "using", "used");

	private Grounding() {
	}

	public static boolean grounded(String claim, String quote) {
		Set<String> q = tokens(quote);
		Set<String> qNumbers = new HashSet<>();
		for (String t : q) {
			if (t.chars().anyMatch(Character::isDigit)) {
				qNumbers.add(digitsOf(t));
			}
		}
		int content = 0;
		int found = 0;
		for (String t : tokens(claim)) {
			if (t.chars().anyMatch(Character::isDigit)) {
				if (!qNumbers.contains(digitsOf(t))) {
					return false; // a number the quote doesn't contain is invented
				}
				continue;
			}
			if (t.length() < 4 || STOP.contains(t)) {
				continue;
			}
			content++;
			if (q.contains(t) || q.stream().anyMatch(w -> w.length() >= 5 && (w.startsWith(stem(t)) || t.startsWith(stem(w))))) {
				found++;
			}
		}
		return content < 2 || (double) found / content >= MIN_WORD_OVERLAP;
	}

	private static Set<String> tokens(String s) {
		Set<String> out = new HashSet<>();
		Matcher m = TOKEN.matcher(s == null ? "" : s.toLowerCase(Locale.ROOT));
		while (m.find()) {
			String t = m.group().replaceAll("[.'%-]+$", "");
			if (!t.isEmpty()) {
				out.add(t);
			}
		}
		return out;
	}

	/** "1,200" / "1200" / "30%" compare by their digits. */
	private static String digitsOf(String t) {
		return t.replaceAll("\\D", "");
	}

	/** A crude stem, enough for plural / tense variants ("reports" ~ "reported"). */
	private static String stem(String w) {
		return w.length() <= 5 ? w : w.substring(0, w.length() - 2);
	}

}
