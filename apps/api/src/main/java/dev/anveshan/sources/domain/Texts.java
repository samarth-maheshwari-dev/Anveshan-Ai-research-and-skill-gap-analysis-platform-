package dev.anveshan.sources.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.regex.Pattern;

/** Cleaned-text helpers (TECHSPEC §12.4). Pure. */
public final class Texts {

	private static final Pattern SPACES = Pattern.compile("[ \\t\\x0B\\f\\u00A0\\u2000-\\u200B\\u202F\\u205F\\u3000]+");
	private static final Pattern MANY_BREAKS = Pattern.compile("\\n{3,}");
	private static final Pattern ALL_WS = Pattern.compile("\\s+");

	private Texts() {
	}

	/**
	 * Stored document text: NFC, spaces collapsed inside lines, lines trimmed, at most one blank line between
	 * paragraphs. Paragraph breaks ({@code \n\n}) are kept — the chunker splits on them.
	 */
	public static String clean(String raw) {
		if (raw == null) {
			return "";
		}
		String t = Normalizer.normalize(raw, Normalizer.Form.NFC).replace("\r\n", "\n").replace('\r', '\n');
		StringBuilder out = new StringBuilder(t.length());
		for (String line : t.split("\n", -1)) {
			out.append(SPACES.matcher(line).replaceAll(" ").strip()).append('\n');
		}
		return MANY_BREAKS.matcher(out.toString()).replaceAll("\n\n").strip();
	}

	/** {@code content_hash}: SHA-256 of the text with all whitespace collapsed (layout changes don't count). */
	public static byte[] contentHash(String text) {
		String normalized = ALL_WS.matcher(text == null ? "" : text).replaceAll(" ").strip();
		try {
			return MessageDigest.getInstance("SHA-256").digest(normalized.getBytes(StandardCharsets.UTF_8));
		}
		catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

}
