package dev.anveshan.report.domain;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.commonmark.node.AbstractVisitor;
import org.commonmark.node.BlockQuote;
import org.commonmark.node.BulletList;
import org.commonmark.node.Code;
import org.commonmark.node.Emphasis;
import org.commonmark.node.FencedCodeBlock;
import org.commonmark.node.HardLineBreak;
import org.commonmark.node.Heading;
import org.commonmark.node.IndentedCodeBlock;
import org.commonmark.node.ListItem;
import org.commonmark.node.Node;
import org.commonmark.node.OrderedList;
import org.commonmark.node.Paragraph;
import org.commonmark.node.SoftLineBreak;
import org.commonmark.node.StrongEmphasis;
import org.commonmark.node.Text;
import org.commonmark.node.ThematicBreak;
import org.commonmark.parser.Parser;

/**
 * The writer's Markdown → TipTap JSON (TECHSPEC §12.9) with {@code citation} nodes. Only a safe subset survives:
 * paragraphs, headings, lists, quotes, code, bold/italic. Raw HTML, links and images from the model are dropped —
 * their text stays, never their markup or targets. Pure.
 */
public final class TipTapDoc {

	/** What a citation node shows: {@code [n]}; the claim id lets the Report Studio open the exact passage. */
	public record Citation(UUID claimId, UUID sourceId, int n, String label) {
	}

	/** Sentinels: commonmark would treat "[c:…]" as a possible link, so markers are swapped out before parsing. */
	private static final char OPEN = '⟦';
	private static final char CLOSE = '⟧';
	private static final Pattern SENTINEL = Pattern.compile(OPEN + "c:([0-9a-f-]{36})(?::([0-9a-f-]{36}))?" + CLOSE);

	private TipTapDoc() {
	}

	/**
	 * @param citation claim id + optional source id → its citation; null when the claim isn't citable (dropped)
	 */
	public static Map<String, Object> from(String markdown, java.util.function.BiFunction<UUID, UUID, Citation> citation) {
		String guarded = Markers.STORED.matcher(markdown == null ? "" : markdown)
				.replaceAll(m -> Matcher.quoteReplacement(OPEN + "c:" + m.group(1)
						+ (m.group(2) == null ? "" : ":" + m.group(2)) + CLOSE));
		Node root = Parser.builder().build().parse(guarded);
		List<Map<String, Object>> content = blocks(root, citation);
		Map<String, Object> doc = new LinkedHashMap<>();
		doc.put("type", "doc");
		doc.put("content", content.isEmpty() ? List.of(paragraph(List.of())) : content);
		return doc;
	}

	/** A doc of plain paragraphs (References). */
	public static Map<String, Object> paragraphs(List<String> lines) {
		List<Map<String, Object>> content = new ArrayList<>();
		for (String l : lines) {
			content.add(paragraph(List.of(text(l, List.of()))));
		}
		return Map.of("type", "doc", "content", content.isEmpty() ? List.of(paragraph(List.of())) : content);
	}

	/** Every citation node of a doc, in reading order. */
	@SuppressWarnings("unchecked")
	public static List<Map<String, Object>> citations(Object node) {
		List<Map<String, Object>> out = new ArrayList<>();
		if (node instanceof Map<?, ?> m) {
			if ("citation".equals(m.get("type"))) {
				out.add((Map<String, Object>) m.get("attrs"));
			}
			Object c = m.get("content");
			if (c instanceof List<?> l) {
				l.forEach(child -> out.addAll(citations(child)));
			}
		}
		return out;
	}

	/** Markdown for export/search: markers become [n] (one per source, consecutive repeats merged). */
	public static String markdownWithNumbers(String markdown, Function<UUID, Integer> numberOfClaim,
			java.util.function.BiFunction<UUID, UUID, Integer> numberOfPair) {
		Matcher m = Markers.STORED.matcher(markdown == null ? "" : markdown);
		StringBuilder out = new StringBuilder();
		Integer last = null;
		int lastEnd = -1;
		while (m.find()) {
			Integer n = m.group(2) == null ? numberOfClaim.apply(UUID.fromString(m.group(1)))
					: numberOfPair.apply(UUID.fromString(m.group(1)), UUID.fromString(m.group(2)));
			String rep = n == null || (n.equals(last) && m.start() == lastEnd) ? "" : "[" + n + "]";
			m.appendReplacement(out, Matcher.quoteReplacement(rep));
			last = n;
			lastEnd = m.end();
		}
		m.appendTail(out);
		return out.toString();
	}

	private static List<Map<String, Object>> blocks(Node parent, java.util.function.BiFunction<UUID, UUID, Citation> cite) {
		List<Map<String, Object>> out = new ArrayList<>();
		for (Node n = parent.getFirstChild(); n != null; n = n.getNext()) {
			Map<String, Object> b = block(n, cite);
			if (b != null) {
				out.add(b);
			}
		}
		return out;
	}

	private static Map<String, Object> block(Node n, java.util.function.BiFunction<UUID, UUID, Citation> cite) {
		if (n instanceof Paragraph) {
			return paragraph(inline(n, List.of(), cite));
		}
		if (n instanceof Heading h) {
			Map<String, Object> node = node("heading", inline(n, List.of(), cite));
			node.put("attrs", Map.of("level", Math.clamp(h.getLevel() + 1, 2, 4))); // the section title is the h1/h2
			return node;
		}
		if (n instanceof BulletList) {
			return node("bulletList", blocks(n, cite));
		}
		if (n instanceof OrderedList) {
			return node("orderedList", blocks(n, cite));
		}
		if (n instanceof ListItem) {
			return node("listItem", blocks(n, cite));
		}
		if (n instanceof BlockQuote) {
			return node("blockquote", blocks(n, cite));
		}
		if (n instanceof FencedCodeBlock f) {
			return node("codeBlock", List.of(text(f.getLiteral().stripTrailing(), List.of())));
		}
		if (n instanceof IndentedCodeBlock c) {
			return node("codeBlock", List.of(text(c.getLiteral().stripTrailing(), List.of())));
		}
		if (n instanceof ThematicBreak) {
			return new LinkedHashMap<>(Map.of("type", "horizontalRule"));
		}
		return null; // HTML blocks and anything else from the model: dropped
	}

	/** Inline content with marks; links and images keep only their text, inline HTML is dropped. */
	private static List<Map<String, Object>> inline(Node parent, List<String> marks,
			java.util.function.BiFunction<UUID, UUID, Citation> cite) {
		List<Map<String, Object>> out = new ArrayList<>();
		for (Node n = parent.getFirstChild(); n != null; n = n.getNext()) {
			if (n instanceof Text t) {
				splitCitations(t.getLiteral(), marks, cite, out);
			}
			else if (n instanceof Code c) {
				out.add(text(c.getLiteral(), with(marks, "code")));
			}
			else if (n instanceof Emphasis) {
				out.addAll(inline(n, with(marks, "italic"), cite));
			}
			else if (n instanceof StrongEmphasis) {
				out.addAll(inline(n, with(marks, "bold"), cite));
			}
			else if (n instanceof SoftLineBreak) {
				out.add(text(" ", marks));
			}
			else if (n instanceof HardLineBreak) {
				out.add(new LinkedHashMap<>(Map.of("type", "hardBreak")));
			}
			else if (n instanceof org.commonmark.node.Link || n instanceof org.commonmark.node.Image) {
				out.addAll(inline(n, marks, cite)); // the visible text only
			}
			// HtmlInline and anything unknown: dropped
		}
		return merge(out);
	}

	private static void splitCitations(String literal, List<String> marks,
			java.util.function.BiFunction<UUID, UUID, Citation> cite, List<Map<String, Object>> out) {
		Matcher m = SENTINEL.matcher(literal);
		int from = 0;
		while (m.find()) {
			if (m.start() > from) {
				out.add(text(literal.substring(from, m.start()), marks));
			}
			Citation c = cite.apply(UUID.fromString(m.group(1)), m.group(2) == null ? null : UUID.fromString(m.group(2)));
			if (c != null) {
				Map<String, Object> attrs = new LinkedHashMap<>();
				attrs.put("claimId", c.claimId().toString());
				attrs.put("sourceId", c.sourceId().toString());
				attrs.put("n", c.n());
				attrs.put("label", c.label());
				Map<String, Object> node = new LinkedHashMap<>();
				node.put("type", "citation");
				node.put("attrs", attrs);
				out.add(node);
			}
			from = m.end();
		}
		if (from < literal.length()) {
			out.add(text(literal.substring(from), marks));
		}
	}

	/** Adjacent text nodes with the same marks become one; a citation repeated right after itself is dropped. */
	@SuppressWarnings("unchecked")
	private static List<Map<String, Object>> merge(List<Map<String, Object>> nodes) {
		List<Map<String, Object>> out = new ArrayList<>();
		for (Map<String, Object> n : nodes) {
			if (!out.isEmpty()) {
				Map<String, Object> prev = out.get(out.size() - 1);
				if ("text".equals(n.get("type")) && "text".equals(prev.get("type"))
						&& java.util.Objects.equals(prev.get("marks"), n.get("marks"))) {
					prev.put("text", prev.get("text") + (String) n.get("text"));
					continue;
				}
				if ("citation".equals(n.get("type")) && "citation".equals(prev.get("type"))
						&& ((Map<String, Object>) prev.get("attrs")).get("n").equals(((Map<String, Object>) n.get("attrs")).get("n"))) {
					continue;
				}
			}
			if ("text".equals(n.get("type")) && ((String) n.get("text")).isEmpty()) {
				continue;
			}
			out.add(n);
		}
		return out;
	}

	private static Map<String, Object> paragraph(List<Map<String, Object>> content) {
		return node("paragraph", content);
	}

	private static Map<String, Object> node(String type, List<Map<String, Object>> content) {
		Map<String, Object> n = new LinkedHashMap<>();
		n.put("type", type);
		if (!content.isEmpty()) {
			n.put("content", content);
		}
		return n;
	}

	private static Map<String, Object> text(String s, List<String> marks) {
		Map<String, Object> n = new LinkedHashMap<>();
		n.put("type", "text");
		n.put("text", s);
		if (!marks.isEmpty()) {
			n.put("marks", marks.stream().map(m -> Map.of("type", m)).toList());
		}
		return n;
	}

	private static List<String> with(List<String> marks, String mark) {
		List<String> out = new ArrayList<>(marks);
		if (!out.contains(mark)) {
			out.add(mark);
		}
		return out;
	}

}
