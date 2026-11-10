package dev.anveshan.byok.domain;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Orders a provider's live model list for the connection test and the default task routing
 * (A-FR-4, A-FR-6). Pure function — no Spring, no IO; the rules come from {@code application.yml}.
 * <p>
 * Non-chat models (embeddings, speech, images …) are dropped. The rest are ordered by: the preset's
 * preferred patterns (config order) → stable before preview/experimental → newest version first →
 * shorter id first → alphabetical (so the result is deterministic).
 */
public final class ModelRanking {

	/** Compiled, case-insensitive rules. An invalid pattern fails at startup, not at test time. */
	public record Rules(List<Pattern> exclude, List<Pattern> unstable, List<Pattern> preferred) {

		public static Rules of(List<String> exclude, List<String> unstable, List<String> preferred) {
			return new Rules(compile(exclude), compile(unstable), compile(preferred));
		}

		private static List<Pattern> compile(List<String> regexes) {
			return regexes == null ? List.of()
					: regexes.stream().map(r -> Pattern.compile(r, Pattern.CASE_INSENSITIVE)).toList();
		}

	}

	/**
	 * First version-like token: "gemini-2.5-flash" → 2.5, "llama-3.3-70b" → 3.3; sizes like "7b" don't count,
	 * and a first token with a 3+ digit major part is a date or build stamp, not a version.
	 */
	private static final Pattern VERSION = Pattern.compile("(?:^|[-_/:])v?(\\d+(?:\\.\\d+)*)(?=$|[-_/:])");

	private ModelRanking() {
	}

	/** Chat-capable ids, best first. Blank ids and duplicates are dropped. */
	public static List<String> rank(Collection<String> modelIds, Rules rules) {
		LinkedHashSet<String> unique = new LinkedHashSet<>();
		for (String raw : modelIds) {
			String id = raw == null ? "" : raw.strip();
			if (!id.isEmpty() && !isExcluded(id, rules)) {
				unique.add(id);
			}
		}
		List<String> out = new ArrayList<>(unique);
		out.sort(Comparator.<String>comparingInt(id -> preferredIndex(id, rules.preferred()))
				.thenComparing(id -> matchesAny(id, rules.unstable()))
				.thenComparing(ModelRanking::version, ModelRanking::newestFirst)
				.thenComparingInt(String::length)
				.thenComparing(Comparator.naturalOrder()));
		return out;
	}

	/** True when the model can't answer a chat request (embedding, speech, image …). */
	public static boolean isExcluded(String id, Rules rules) {
		return matchesAny(id, rules.exclude());
	}

	private static int preferredIndex(String id, List<Pattern> preferred) {
		for (int i = 0; i < preferred.size(); i++) {
			if (preferred.get(i).matcher(id).find()) {
				return i;
			}
		}
		return preferred.size();
	}

	private static boolean matchesAny(String id, List<Pattern> patterns) {
		return patterns.stream().anyMatch(p -> p.matcher(id).find());
	}

	/** Version parts as digit strings (no overflow on long date-like numbers); empty when none. */
	static List<String> version(String id) {
		Matcher m = VERSION.matcher(id);
		if (!m.find() || m.group(1).split("\\.")[0].replaceFirst("^0+(?=\\d)", "").length() > 2) {
			return List.of(); // none, or a date/build stamp (gpt-4o-mini-2024-07-18, deepseek-r1-0528) — not a version
		}
		List<String> parts = new ArrayList<>();
		for (String p : m.group(1).split("\\.")) {
			String trimmed = p.replaceFirst("^0+(?=\\d)", "");
			parts.add(trimmed);
		}
		return parts;
	}

	/** Descending: higher versions first; a longer version wins when the shared prefix is equal (2.5 > 2). */
	static int newestFirst(List<String> a, List<String> b) {
		int n = Math.min(a.size(), b.size());
		for (int i = 0; i < n; i++) {
			int c = compareNumber(a.get(i), b.get(i));
			if (c != 0) {
				return -c;
			}
		}
		return Integer.compare(b.size(), a.size());
	}

	private static int compareNumber(String x, String y) {
		return x.length() != y.length() ? Integer.compare(x.length(), y.length()) : x.compareTo(y);
	}

}
