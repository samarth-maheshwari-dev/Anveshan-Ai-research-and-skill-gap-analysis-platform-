package dev.anveshan.sources.domain;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Splits a document into passages (TECHSPEC §12.5): by paragraph, merged up to ~1,200 characters, overlapping by
 * the previous chunk's last sentence. Every chunk is an exact slice of the document —
 * {@code text.substring(start, end)} — so claims can be anchored to it later (L1). Pure.
 */
public final class Chunker {

	public static final int MAX_CHARS = 1200;

	/** A passage: {@code [start, end)} into the document text; {@code page} (1-based, PDFs only) is where its new text starts. */
	public record Chunk(int position, int start, int end, Integer page) {

		public String text(String document) {
			return document.substring(start, end);
		}

	}

	private record Span(int start, int end) {

		int length() {
			return end - start;
		}

	}

	private static final Pattern PARAGRAPH_BREAK = Pattern.compile("\\n\\s*\\n");
	private static final Pattern SENTENCE_END = Pattern.compile("[.!?…।](?=[\"')\\]]*\\s)");

	private Chunker() {
	}

	public static List<Chunk> chunk(String text) {
		return chunk(text, MAX_CHARS, null);
	}

	/**
	 * @param pageStarts for PDFs: the offset where each page begins (ascending, first = 0); null for web pages
	 */
	public static List<Chunk> chunk(String text, int maxChars, int[] pageStarts) {
		List<Span> units = units(text, maxChars);
		List<Chunk> out = new ArrayList<>();
		int i = 0;
		while (i < units.size()) {
			int start = units.get(i).start();
			if (!out.isEmpty()) {
				Chunk prev = out.get(out.size() - 1);
				int overlap = lastSentenceStart(text, prev.start(), prev.end());
				// overlap only if the first new unit still fits after it
				if (overlap > prev.start() && units.get(i).end() - overlap <= maxChars) {
					start = overlap;
				}
			}
			int end = units.get(i).end();
			int j = i + 1;
			while (j < units.size() && units.get(j).end() - start <= maxChars) {
				end = units.get(j).end();
				j++;
			}
			// the page where the chunk's own text begins (the overlap sentence may sit on the page before)
			out.add(new Chunk(out.size(), start, end, page(pageStarts, units.get(i).start())));
			i = j;
		}
		return out;
	}

	/** Paragraphs; a paragraph longer than the limit becomes sentences; a sentence longer than it, word-split slices. */
	private static List<Span> units(String text, int max) {
		List<Span> out = new ArrayList<>();
		if (text == null || text.isBlank()) {
			return out;
		}
		int from = 0;
		Matcher m = PARAGRAPH_BREAK.matcher(text);
		while (m.find()) {
			addParagraph(text, from, m.start(), max, out);
			from = m.end();
		}
		addParagraph(text, from, text.length(), max, out);
		return out;
	}

	private static void addParagraph(String text, int from, int to, int max, List<Span> out) {
		Span p = trim(text, from, to);
		if (p == null) {
			return;
		}
		if (p.length() <= max) {
			out.add(p);
			return;
		}
		int s = p.start();
		Matcher m = SENTENCE_END.matcher(text).region(p.start(), p.end());
		while (m.find()) {
			addSentence(text, s, m.end(), max, out);
			s = m.end();
		}
		addSentence(text, s, p.end(), max, out);
	}

	private static void addSentence(String text, int from, int to, int max, List<Span> out) {
		Span s = trim(text, from, to);
		while (s != null && s.length() > max) {
			int cut = text.lastIndexOf(' ', s.start() + max);
			if (cut <= s.start()) {
				cut = s.start() + max; // one very long "word" (a URL, a table row): hard cut
			}
			out.add(trim(text, s.start(), cut));
			s = trim(text, cut, s.end());
		}
		if (s != null) {
			out.add(s);
		}
	}

	/** Start of the last sentence inside [start, end), or -1 when the chunk is a single sentence. */
	private static int lastSentenceStart(String text, int start, int end) {
		Matcher m = SENTENCE_END.matcher(text).region(start, end);
		int last = -1;
		while (m.find()) {
			if (m.end() < end - 1) {
				last = m.end();
			}
		}
		if (last < 0) {
			return -1;
		}
		Span s = trim(text, last, end);
		return s == null ? -1 : s.start();
	}

	private static Span trim(String text, int from, int to) {
		while (from < to && Character.isWhitespace(text.charAt(from))) {
			from++;
		}
		while (to > from && Character.isWhitespace(text.charAt(to - 1))) {
			to--;
		}
		return to > from ? new Span(from, to) : null;
	}

	private static Integer page(int[] pageStarts, int offset) {
		if (pageStarts == null || pageStarts.length == 0) {
			return null;
		}
		int i = Arrays.binarySearch(pageStarts, offset);
		return (i >= 0 ? i : -i - 2) + 1;
	}

}
