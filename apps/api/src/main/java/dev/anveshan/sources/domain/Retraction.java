package dev.anveshan.sources.domain;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Was a paper taken back by its journal? A retracted paper is never evidence, so search drops it before anything
 * reads it. The APIs say so directly (OpenAlex {@code is_retracted}, Crossref {@code updated-by} with Retraction Watch
 * data); a title the publisher prefixed with "RETRACTED:" catches the rest (arXiv, Semantic Scholar). Retraction
 * notices are about another paper, not evidence either. Pure.
 */
public final class Retraction {

	/** "RETRACTED: …", "[Retracted] …", "(WITHDRAWN) …", "Retracted article: …" — a marker, then a separator. */
	private static final Pattern MARKED = Pattern.compile(
			"^\\s*[\\[(]?\\s*(retracted|withdrawn)(\\s+(article|paper|preprint))?(\\s*[:\\])]|\\s+[-–—]\\s)",
			Pattern.CASE_INSENSITIVE); // a dash needs spaces: "Withdrawn-hand reflex" is a real title

	/**
	 * "Retraction notice: …", "Retraction: …", "Notice of Retraction: …", "Retraction of: …", Springer's "Retraction
	 * Note to: …", Elsevier's "Retraction notice to "…"" and "Withdrawal notice: …". A bare "Withdrawal: …" is a topic.
	 */
	private static final Pattern NOTICE = Pattern.compile("^\\s*(notice\\s+of\\s+retraction"
			+ "|retraction(\\s+(notice|note|statement))?(\\s+(of|to))?|withdrawal\\s+notice(\\s+(of|to))?)\\s*(:|[\"“])",
			Pattern.CASE_INSENSITIVE);

	/** Crossref update types that take a paper back (corrections and expressions of concern don't). */
	private static final Set<String> TAKES_BACK = Set.of("retraction", "withdrawal", "removal");

	private Retraction() {
	}

	public static boolean titleSaysRetracted(String title) {
		return title != null && MARKED.matcher(title).find();
	}

	public static boolean isNotice(String title) {
		return title != null && NOTICE.matcher(title).find();
	}

	public static boolean takesBack(String crossrefUpdateType) {
		return crossrefUpdateType != null && TAKES_BACK.contains(crossrefUpdateType.strip().toLowerCase(Locale.ROOT));
	}

}
