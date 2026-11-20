package dev.anveshan.report.domain;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * What the Report Studio does to edited TipTap documents (TECHSPEC §9.7, F-FR-3/6), all by code and all pure:
 * <ul>
 * <li>{@link #sanitize}: a user's document → only the node types and marks the editor offers (no HTML, links,
 * images; size and depth limits). Citation nodes keep only their claim id and source id.</li>
 * <li>{@link #renumber}: citation numbers by first appearance per source across the whole report (L8), labels from
 * the run's claims; citations of claims that aren't the run's are removed.</li>
 * <li>{@link #markdown}: the derived Markdown ({@code content_md}) with {@code [n]} citations.</li>
 * <li>{@link #select}: the text between two editor positions, citations as {@code [c:<claimId>]} markers (Assist).</li>
 * </ul>
 */
public final class ReportDocs {

	public static final int MAX_DEPTH = 12;
	public static final int MAX_NODES = 20_000;
	public static final int MAX_TEXT = 200_000;

	private static final Set<String> BLOCKS = Set.of("paragraph", "heading", "bulletList", "orderedList", "listItem",
			"blockquote", "codeBlock", "horizontalRule");
	private static final Set<String> INLINE = Set.of("text", "hardBreak", "citation");
	private static final Set<String> MARKS = Set.of("bold", "italic", "code", "strike");
	private static final Set<String> LEAVES = Set.of("citation", "hardBreak", "horizontalRule");
	private static final Set<String> TEXTBLOCKS = Set.of("paragraph", "heading", "codeBlock");

	/** What a citation may show for one claim of the run. */
	public record ClaimInfo(UUID sourceId, Set<UUID> otherSources, String label) {
	}

	/** Every section's document renumbered, and the number of each cited source (reading order). */
	public record Renumbered(List<Map<String, Object>> docs, Map<UUID, Integer> numbers, int removed) {
	}

	/** Selected text with {@code [c:<claimId>]} markers, and the claims cited in it (first appearance order). */
	public record Selection(String text, List<UUID> claimIds) {
	}

	private ReportDocs() {
	}

	// ------------------------------------------------------------------ sanitize

	/**
	 * @throws IllegalArgumentException with a message for the user when the input isn't a document or is too big
	 */
	public static Map<String, Object> sanitize(Object input) {
		if (!(input instanceof Map<?, ?> m) || !"doc".equals(m.get("type"))) {
			throw new IllegalArgumentException("The section content must be a document.");
		}
		int[] budget = { MAX_NODES, MAX_TEXT };
		List<Map<String, Object>> content = children(m.get("content"), true, 1, budget);
		Map<String, Object> doc = new LinkedHashMap<>();
		doc.put("type", "doc");
		doc.put("content", content.isEmpty() ? List.of(node("paragraph")) : content);
		return doc;
	}

	private static List<Map<String, Object>> children(Object raw, boolean blockLevel, int depth, int[] budget) {
		List<Map<String, Object>> out = new ArrayList<>();
		if (!(raw instanceof List<?> list)) {
			return out;
		}
		if (depth > MAX_DEPTH) {
			throw new IllegalArgumentException("The section is nested too deeply.");
		}
		for (Object child : list) {
			if (!(child instanceof Map<?, ?> c) || !(c.get("type") instanceof String type)) {
				continue;
			}
			if (--budget[0] < 0) {
				throw new IllegalArgumentException("The section is too long.");
			}
			Map<String, Object> clean = blockLevel ? block(type, c, depth, budget) : inline(type, c, budget);
			if (clean != null) {
				out.add(clean);
			}
		}
		return out;
	}

	private static Map<String, Object> block(String type, Map<?, ?> in, int depth, int[] budget) {
		if (!BLOCKS.contains(type)) {
			return null; // tables, images, embeds, raw HTML … are not part of a report section
		}
		Map<String, Object> out = node(type);
		switch (type) {
			case "heading" -> {
				int level = in.get("attrs") instanceof Map<?, ?> a && a.get("level") instanceof Number n ? n.intValue() : 2;
				out.put("attrs", Map.of("level", Math.clamp(level, 2, 4)));
				putContent(out, children(in.get("content"), false, depth + 1, budget));
			}
			case "paragraph" -> putContent(out, children(in.get("content"), false, depth + 1, budget));
			case "codeBlock" -> putContent(out, children(in.get("content"), false, depth + 1, budget).stream()
					.filter(n -> "text".equals(n.get("type"))).map(n -> text((String) n.get("text"), List.of())).toList());
			case "horizontalRule" -> {
				// a leaf
			}
			default -> { // lists, list items and quotes hold blocks
				List<Map<String, Object>> kids = children(in.get("content"), true, depth + 1, budget);
				if ("bulletList".equals(type) || "orderedList".equals(type)) {
					kids = kids.stream().filter(k -> "listItem".equals(k.get("type"))).toList();
				}
				else {
					kids = kids.stream().filter(k -> !"listItem".equals(k.get("type"))).toList();
				}
				if (kids.isEmpty()) {
					kids = "bulletList".equals(type) || "orderedList".equals(type)
							? List.of(withContent(node("listItem"), List.of(node("paragraph"))))
							: List.of(node("paragraph"));
				}
				putContent(out, kids);
			}
		}
		return out;
	}

	private static Map<String, Object> inline(String type, Map<?, ?> in, int[] budget) {
		if (!INLINE.contains(type)) {
			return null;
		}
		if ("hardBreak".equals(type)) {
			return node("hardBreak");
		}
		if ("citation".equals(type)) {
			if (!(in.get("attrs") instanceof Map<?, ?> a)) {
				return null;
			}
			UUID claim = uuid(a.get("claimId"));
			if (claim == null) {
				return null;
			}
			Map<String, Object> attrs = new LinkedHashMap<>();
			attrs.put("claimId", claim.toString());
			UUID source = uuid(a.get("sourceId"));
			attrs.put("sourceId", source == null ? null : source.toString());
			return withAttrs(node("citation"), attrs);
		}
		if (!(in.get("text") instanceof String s) || s.isEmpty()) {
			return null;
		}
		budget[1] -= s.length();
		if (budget[1] < 0) {
			throw new IllegalArgumentException("The section is too long.");
		}
		List<String> marks = new ArrayList<>();
		if (in.get("marks") instanceof List<?> ms) {
			for (Object mk : ms) {
				if (mk instanceof Map<?, ?> mm && mm.get("type") instanceof String t && MARKS.contains(t) && !marks.contains(t)) {
					marks.add(t);
				}
			}
		}
		return text(s, marks);
	}

	// ------------------------------------------------------------------ renumber

	/**
	 * Numbers every citation by the first appearance of its source across {@code docs} (in report order), sets its
	 * label from the claim, and removes citations of claims that aren't in {@code claims}. A citation keeps its own
	 * source only when that source judged the claim (conflict lines cite both sides); otherwise it cites the claim's
	 * source. The same number twice in a row is kept once.
	 */
	public static Renumbered renumber(List<Map<String, Object>> docs, Map<UUID, ClaimInfo> claims) {
		Map<UUID, Integer> numbers = new LinkedHashMap<>();
		int[] removed = { 0 };
		List<Map<String, Object>> out = new ArrayList<>();
		for (Map<String, Object> doc : docs) {
			out.add(renumberNode(doc, claims, numbers, removed));
		}
		return new Renumbered(out, numbers, removed[0]);
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> renumberNode(Map<String, Object> n, Map<UUID, ClaimInfo> claims,
			Map<UUID, Integer> numbers, int[] removed) {
		Map<String, Object> copy = new LinkedHashMap<>(n);
		if (!(n.get("content") instanceof List<?> kids)) {
			return copy;
		}
		List<Map<String, Object>> content = new ArrayList<>();
		Integer last = null;
		for (Object k : kids) {
			Map<String, Object> child = (Map<String, Object>) k;
			if (!"citation".equals(child.get("type"))) {
				content.add(renumberNode(child, claims, numbers, removed));
				if (!(child.get("text") instanceof String s && s.isBlank())) {
					last = null;
				}
				continue;
			}
			Map<String, Object> attrs = child.get("attrs") instanceof Map<?, ?> a ? (Map<String, Object>) a : Map.of();
			UUID claimId = uuid(attrs.get("claimId"));
			ClaimInfo info = claimId == null ? null : claims.get(claimId);
			if (info == null) {
				removed[0]++;
				continue;
			}
			UUID source = uuid(attrs.get("sourceId"));
			if (source == null || (!source.equals(info.sourceId()) && !info.otherSources().contains(source))) {
				source = info.sourceId();
			}
			Integer number = numbers.computeIfAbsent(source, s -> numbers.size() + 1);
			if (number.equals(last)) {
				continue; // "[2][2]" reads as "[2]"
			}
			last = number;
			Map<String, Object> a = new LinkedHashMap<>();
			a.put("claimId", claimId.toString());
			a.put("sourceId", source.toString());
			a.put("n", number);
			a.put("label", info.label());
			content.add(withAttrs(node("citation"), a));
		}
		copy.put("content", content);
		return copy;
	}

	// ------------------------------------------------------------------ markdown

	/** {@code content_md}: Markdown for export and search, citations as {@code [n]}. */
	public static String markdown(Map<String, Object> doc) {
		StringBuilder out = new StringBuilder();
		blocksMd(list(doc.get("content")), "", out);
		return out.toString().strip();
	}

	private static void blocksMd(List<Map<String, Object>> blocks, String indent, StringBuilder out) {
		for (Map<String, Object> b : blocks) {
			String type = String.valueOf(b.get("type"));
			switch (type) {
				case "heading" -> {
					int level = b.get("attrs") instanceof Map<?, ?> a && a.get("level") instanceof Number n ? n.intValue() : 2;
					out.append(indent).append("#".repeat(level)).append(' ').append(inlineMd(list(b.get("content"))))
							.append("\n\n");
				}
				case "bulletList", "orderedList" -> {
					int i = 1;
					for (Map<String, Object> item : list(b.get("content"))) {
						String bullet = "bulletList".equals(type) ? "- " : (i++) + ". ";
						StringBuilder inner = new StringBuilder();
						blocksMd(list(item.get("content")), "", inner);
						String[] lines = inner.toString().strip().split("\n");
						for (int l = 0; l < lines.length; l++) {
							if (l == 0) {
								out.append(indent).append(bullet).append(lines[l]).append('\n');
							}
							else if (!lines[l].isBlank()) {
								out.append(indent).append(" ".repeat(bullet.length())).append(lines[l]).append('\n');
							}
						}
					}
					out.append('\n');
				}
				case "blockquote" -> {
					StringBuilder inner = new StringBuilder();
					blocksMd(list(b.get("content")), "", inner);
					for (String line : inner.toString().strip().split("\n")) {
						out.append(indent).append(line.isBlank() ? ">" : "> " + line).append('\n');
					}
					out.append('\n');
				}
				case "codeBlock" -> out.append(indent).append("```\n").append(plain(list(b.get("content"))))
						.append("\n```\n\n");
				case "horizontalRule" -> out.append(indent).append("---\n\n");
				default -> out.append(indent).append(inlineMd(list(b.get("content")))).append("\n\n"); // paragraph
			}
		}
	}

	private static String inlineMd(List<Map<String, Object>> nodes) {
		StringBuilder out = new StringBuilder();
		Object last = null;
		for (Map<String, Object> n : nodes) {
			switch (String.valueOf(n.get("type"))) {
				case "citation" -> {
					Object num = n.get("attrs") instanceof Map<?, ?> a ? a.get("n") : null;
					if (num != null && !num.equals(last)) {
						out.append('[').append(num).append(']');
					}
					last = num;
					continue;
				}
				case "hardBreak" -> out.append("  \n");
				case "text" -> {
					String t = (String) n.get("text");
					List<String> marks = list(n.get("marks")).stream().map(m -> String.valueOf(m.get("type"))).toList();
					if (marks.contains("code")) {
						t = "`" + t + "`";
					}
					if (marks.contains("strike")) {
						t = "~~" + t + "~~";
					}
					if (marks.contains("italic")) {
						t = "*" + t + "*";
					}
					if (marks.contains("bold")) {
						t = "**" + t + "**";
					}
					out.append(t);
				}
				default -> {
					// nothing else is inline
				}
			}
			last = null;
		}
		return out.toString();
	}

	private static String plain(List<Map<String, Object>> nodes) {
		StringBuilder out = new StringBuilder();
		nodes.forEach(n -> out.append(n.get("text") instanceof String s ? s : ""));
		return out.toString();
	}

	// ------------------------------------------------------------------ select (editor positions)

	/**
	 * The text between two ProseMirror positions ({@code [from, to)}, as the editor reports a selection): text
	 * counts its UTF-16 length, a leaf node (citation, line break, rule) counts 1, any other node 2 plus its content.
	 * Text blocks are separated by a blank line; a citation inside the range becomes {@code [c:<claimId>]}.
	 */
	public static Selection select(Map<String, Object> doc, int from, int to) {
		if (from < 0 || to <= from) {
			throw new IllegalArgumentException("Select some text first.");
		}
		StringBuilder text = new StringBuilder();
		List<UUID> claims = new ArrayList<>();
		walk(list(doc.get("content")), 0, from, to, text, claims);
		String t = text.toString().replaceAll("\n{3,}", "\n\n").strip();
		if (t.isEmpty()) {
			throw new IllegalArgumentException("Select some text first.");
		}
		return new Selection(t, claims);
	}

	/** @return the position after these nodes */
	private static int walk(List<Map<String, Object>> nodes, int pos, int from, int to, StringBuilder text,
			List<UUID> claims) {
		for (Map<String, Object> n : nodes) {
			String type = String.valueOf(n.get("type"));
			if ("text".equals(type)) {
				String s = (String) n.get("text");
				int start = Math.max(from, pos);
				int end = Math.min(to, pos + s.length());
				if (start < end) {
					text.append(s, start - pos, end - pos);
				}
				pos += s.length();
			}
			else if (LEAVES.contains(type)) {
				if (pos >= from && pos < to) {
					if ("citation".equals(type) && n.get("attrs") instanceof Map<?, ?> a && uuid(a.get("claimId")) instanceof UUID id) {
						text.append("[c:").append(id).append(']');
						if (!claims.contains(id)) {
							claims.add(id);
						}
					}
					else if ("hardBreak".equals(type)) {
						text.append('\n');
					}
				}
				pos += 1;
			}
			else {
				int inner = pos + 1;
				int end = walk(list(n.get("content")), inner, from, to, text, claims);
				if (TEXTBLOCKS.contains(type) && end > from && inner < to && !text.isEmpty()) {
					text.append("\n\n");
				}
				pos = end + 1;
			}
		}
		return pos;
	}

	/** The ProseMirror size of a document's content (the last valid position). */
	public static int size(Map<String, Object> doc) {
		return walk(list(doc.get("content")), 0, Integer.MAX_VALUE, Integer.MAX_VALUE, new StringBuilder(),
				new ArrayList<>());
	}

	// ------------------------------------------------------------------ helpers

	@SuppressWarnings("unchecked")
	private static List<Map<String, Object>> list(Object o) {
		if (!(o instanceof List<?> l)) {
			return List.of();
		}
		List<Map<String, Object>> out = new ArrayList<>();
		for (Object x : l) {
			if (x instanceof Map<?, ?> m) {
				out.add((Map<String, Object>) m);
			}
		}
		return out;
	}

	private static UUID uuid(Object o) {
		if (!(o instanceof String s)) {
			return null;
		}
		try {
			return s.length() == 36 ? UUID.fromString(s) : null;
		}
		catch (IllegalArgumentException e) {
			return null;
		}
	}

	private static Map<String, Object> node(String type) {
		Map<String, Object> n = new LinkedHashMap<>();
		n.put("type", type);
		return n;
	}

	private static Map<String, Object> withAttrs(Map<String, Object> n, Map<String, Object> attrs) {
		n.put("attrs", attrs);
		return n;
	}

	private static Map<String, Object> withContent(Map<String, Object> n, List<Map<String, Object>> content) {
		n.put("content", content);
		return n;
	}

	private static void putContent(Map<String, Object> n, List<Map<String, Object>> content) {
		if (!content.isEmpty()) {
			n.put("content", content);
		}
	}

	private static Map<String, Object> text(String s, List<String> marks) {
		Map<String, Object> n = node("text");
		n.put("text", s);
		if (!marks.isEmpty()) {
			n.put("marks", marks.stream().map(m -> Map.of("type", m)).toList());
		}
		return n;
	}

}
