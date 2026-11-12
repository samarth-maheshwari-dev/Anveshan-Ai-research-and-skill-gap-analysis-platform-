package dev.anveshan.sources.domain;

import java.util.Optional;

/**
 * Finds where a quote sits in a chunk (TECHSPEC §12.6, TS-D11): LLMs miscount characters, so the model returns the
 * quote text and code computes the offsets. 1. exact; 2. normalized (whitespace, curly quotes, dashes, case) with an
 * index map back to the original; 3. fuzzy window, similarity ≥ 0.95. No span → the claim is dropped (L1). Pure.
 */
public final class Locator {

	public static final double MIN_SIMILARITY = 0.95;
	/** Below this length a fuzzy match is too likely to be a coincidence. */
	static final int FUZZY_MIN_LENGTH = 20;

	/** {@code [start, end)} into the original chunk text. */
	public record Span(int start, int end) {
	}

	/** Normalized text plus, for each of its characters, the index of the original character it came from. */
	private record Normalized(String text, int[] origin) {

		static Normalized of(String s) {
			StringBuilder out = new StringBuilder(s.length());
			int[] map = new int[s.length() * 3 + 1];
			int n = 0;
			for (int i = 0; i < s.length(); i++) {
				char c = s.charAt(i);
				String r = replacement(c);
				if (r.equals(" ")) {
					if (out.isEmpty() || out.charAt(out.length() - 1) == ' ') {
						continue; // collapse runs, drop leading
					}
				}
				for (int k = 0; k < r.length(); k++) {
					out.append(r.charAt(k));
					map[n++] = i;
				}
			}
			while (!out.isEmpty() && out.charAt(out.length() - 1) == ' ') {
				out.setLength(out.length() - 1);
				n--;
			}
			return new Normalized(out.toString(), java.util.Arrays.copyOf(map, n));
		}

		private static String replacement(char c) {
			if (Character.isWhitespace(c) || c == ' ' || c == ' ' || c == ' ') {
				return " ";
			}
			return switch (c) {
				case '‘', '’', '‚', '‛', '′', '`' -> "'";
				case '“', '”', '„', '‟', '″' -> "\"";
				case '‐', '‑', '‒', '–', '—', '―', '−' -> "-";
				case '…' -> "...";
				case '­', '​', '‌', '‍', '﻿' -> ""; // soft hyphen, zero-width
				default -> String.valueOf(Character.toLowerCase(c));
			};
		}

		Span toOriginal(int from, int to) {
			return new Span(origin[from], origin[to - 1] + 1);
		}

	}

	private Locator() {
	}

	public static Optional<Span> locate(String chunk, String quote) {
		if (chunk == null || quote == null || quote.isBlank()) {
			return Optional.empty();
		}
		int i = chunk.indexOf(quote);
		if (i >= 0) {
			return Optional.of(new Span(i, i + quote.length()));
		}
		Normalized c = Normalized.of(chunk);
		Normalized q = Normalized.of(quote);
		if (q.text().isEmpty()) {
			return Optional.empty();
		}
		int j = c.text().indexOf(q.text());
		if (j >= 0) {
			return Optional.of(c.toOriginal(j, j + q.text().length()));
		}
		return fuzzy(c, q.text());
	}

	/**
	 * Sliding window, anchored where the quote's first or last few characters occur (one typo rarely hits both
	 * ends), at most 5 % edits. Returns the closest window.
	 */
	private static Optional<Span> fuzzy(Normalized chunk, String q) {
		int len = q.length();
		if (len < FUZZY_MIN_LENGTH) {
			return Optional.empty();
		}
		String c = chunk.text();
		int maxDist = (int) Math.floor(len * (1 - MIN_SIMILARITY));
		int anchor = Math.min(8, len / 4);
		int bestDist = maxDist + 1;
		int bestStart = -1;
		int bestEnd = -1;
		String head = q.substring(0, anchor);
		for (int p = c.indexOf(head); p >= 0; p = c.indexOf(head, p + 1)) {
			int[] r = bestPrefix(c, p, q, maxDist);
			if (r != null && r[0] < bestDist) {
				bestDist = r[0];
				bestStart = p;
				bestEnd = p + r[1];
			}
		}
		String tail = q.substring(len - anchor);
		String reversedChunk = new StringBuilder(c).reverse().toString();
		String reversedQuote = new StringBuilder(q).reverse().toString();
		for (int e = c.indexOf(tail); e >= 0; e = c.indexOf(tail, e + 1)) {
			int end = e + anchor;
			int[] r = bestPrefix(reversedChunk, c.length() - end, reversedQuote, maxDist);
			if (r != null && r[0] < bestDist) {
				bestDist = r[0];
				bestStart = end - r[1];
				bestEnd = end;
			}
		}
		if (bestStart < 0 || bestEnd <= bestStart) {
			return Optional.empty();
		}
		return Optional.of(chunk.toOriginal(bestStart, bestEnd));
	}

	/**
	 * Edit distance between {@code q} and the best prefix of {@code text[from…]} whose length is within
	 * {@code maxDist} of {@code q}'s: {distance, prefix length}, or null if none is close enough.
	 */
	private static int[] bestPrefix(String text, int from, String q, int maxDist) {
		int m = q.length();
		int maxLen = Math.min(text.length() - from, m + maxDist);
		if (maxLen < m - maxDist) {
			return null;
		}
		// rows over text characters, columns over quote characters
		int[] prev = new int[m + 1];
		int[] cur = new int[m + 1];
		for (int j = 0; j <= m; j++) {
			prev[j] = j;
		}
		int bestDist = Integer.MAX_VALUE;
		int bestLen = -1;
		for (int i = 1; i <= maxLen; i++) {
			cur[0] = i;
			char t = text.charAt(from + i - 1);
			for (int j = 1; j <= m; j++) {
				int cost = t == q.charAt(j - 1) ? 0 : 1;
				cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
			}
			// equal distance: prefer the window whose length is closest to the quote's (a substitution, not a deletion)
			if (i >= m - maxDist && (cur[m] < bestDist
					|| (cur[m] == bestDist && Math.abs(i - m) < Math.abs(bestLen - m)))) {
				bestDist = cur[m];
				bestLen = i;
			}
			int[] swap = prev;
			prev = cur;
			cur = swap;
		}
		return bestDist <= maxDist ? new int[] { bestDist, bestLen } : null;
	}

}
