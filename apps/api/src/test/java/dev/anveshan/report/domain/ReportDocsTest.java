package dev.anveshan.report.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import dev.anveshan.report.domain.ReportDocs.ClaimInfo;
import dev.anveshan.report.domain.ReportDocs.Renumbered;
import dev.anveshan.report.domain.ReportDocs.Selection;

/** The Report Studio's pure document functions: sanitize, renumber (L8), Markdown, selection by editor position. */
class ReportDocsTest {

	static final UUID C1 = UUID.fromString("00000000-0000-0000-0000-0000000000c1");
	static final UUID C2 = UUID.fromString("00000000-0000-0000-0000-0000000000c2");
	static final UUID C3 = UUID.fromString("00000000-0000-0000-0000-0000000000c3");
	static final UUID S1 = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
	static final UUID S2 = UUID.fromString("00000000-0000-0000-0000-0000000000a2");
	static final UUID S3 = UUID.fromString("00000000-0000-0000-0000-0000000000a3");

	static Map<String, Object> doc(Object... blocks) {
		return new LinkedHashMap<>(Map.of("type", "doc", "content", List.of(blocks)));
	}

	static Map<String, Object> p(Object... inline) {
		return Map.of("type", "paragraph", "content", List.of(inline));
	}

	static Map<String, Object> t(String s, String... marks) {
		Map<String, Object> n = new LinkedHashMap<>();
		n.put("type", "text");
		n.put("text", s);
		if (marks.length > 0) {
			n.put("marks", java.util.Arrays.stream(marks).map(m -> Map.of("type", m)).toList());
		}
		return n;
	}

	static Map<String, Object> cite(UUID claim, UUID source, int n) {
		Map<String, Object> a = new LinkedHashMap<>();
		a.put("claimId", claim.toString());
		a.put("sourceId", source == null ? null : source.toString());
		a.put("n", n);
		return Map.of("type", "citation", "attrs", a);
	}

	@SuppressWarnings("unchecked")
	static List<Map<String, Object>> citations(Map<String, Object> doc) {
		return (List<Map<String, Object>>) (List<?>) TipTapDoc.citations(doc);
	}

	// ------------------------------------------------------------------ sanitize

	@Test
	@DisplayName("T-15-09 sanitize(): only the editor's nodes and marks survive; links, images, HTML, tables and stray attrs are dropped")
	void sanitizeDropsUnsafe() {
		Map<String, Object> in = doc(
				Map.of("type", "paragraph", "content", List.of(
						t("Hello ", "bold", "link", "bold"),
						Map.of("type", "image", "attrs", Map.of("src", "https://evil.example/x.png")),
						Map.of("type", "citation", "attrs", Map.of("claimId", C1.toString(), "n", 99, "label", "SUPPORTED",
								"onclick", "alert(1)")),
						Map.of("type", "citation", "attrs", Map.of("claimId", "not-a-uuid")),
						Map.of("type", "citation"),
						Map.of("type", "text", "text", ""),
						Map.of("type", "hardBreak"),
						"garbage")),
				Map.of("type", "table", "content", List.of(p(t("cell")))),
				Map.of("type", "heading", "attrs", Map.of("level", 1), "content", List.of(t("Title"))),
				Map.of("type", "heading", "content", List.of(t("No level"))),
				Map.of("type", "codeBlock", "attrs", Map.of("language", "js"), "content", List.of(t("x()"), cite(C1, S1, 1))),
				Map.of("type", "horizontalRule", "content", List.of(t("ignored"))),
				Map.of("type", "bulletList", "content", List.of(p(t("not an item")))),
				Map.of("type", "blockquote", "content", List.of(Map.of("type", "listItem"), p(t("quoted")))),
				Map.of("type", "blockquote"),
				Map.of("noType", true));
		Map<String, Object> out = ReportDocs.sanitize(in);
		String s = out.toString();
		assertThat(s).doesNotContain("image", "evil", "link", "table", "cell", "onclick", "not-a-uuid", "language",
				"ignored", "label=SUPPORTED", "n=99");
		List<?> content = (List<?>) out.get("content");
		assertThat(content).hasSize(8);
		assertThat(s).contains("marks=[{type=bold}]", "claimId=" + C1, "sourceId=null", "{type=hardBreak}");
		assertThat(content.get(1).toString()).contains("level=2"); // h1 is the section title → clamped to 2
		assertThat(content.get(2).toString()).contains("level=2");
		assertThat(content.get(3).toString()).isEqualTo("{type=codeBlock, content=[{type=text, text=x()}]}");
		assertThat(content.get(4)).isEqualTo(Map.of("type", "horizontalRule"));
		assertThat(content.get(5).toString()).contains("listItem").doesNotContain("not an item");
		assertThat(content.get(6).toString()).contains("quoted").doesNotContain("listItem");
		assertThat(content.get(7).toString()).isEqualTo("{type=blockquote, content=[{type=paragraph}]}");
	}

	@Test
	@DisplayName("T-15-10 sanitize(): not a document, too deep or too long → refused with a message; an empty doc gets a paragraph")
	void sanitizeLimits() {
		assertThatThrownBy(() -> ReportDocs.sanitize(Map.of("type", "paragraph"))).hasMessageContaining("document");
		assertThatThrownBy(() -> ReportDocs.sanitize("doc")).isInstanceOf(IllegalArgumentException.class);
		Map<String, Object> deep = p(t("x"));
		for (int i = 0; i < ReportDocs.MAX_DEPTH + 2; i++) {
			deep = Map.of("type", "blockquote", "content", List.of(deep));
		}
		Map<String, Object> deepDoc = doc(deep);
		assertThatThrownBy(() -> ReportDocs.sanitize(deepDoc)).hasMessageContaining("nested");
		Map<String, Object> longDoc = doc(p(t("x".repeat(ReportDocs.MAX_TEXT + 1))));
		assertThatThrownBy(() -> ReportDocs.sanitize(longDoc)).hasMessageContaining("too long");
		List<Object> many = new ArrayList<>();
		for (int i = 0; i <= ReportDocs.MAX_NODES; i++) {
			many.add(Map.of("type", "horizontalRule"));
		}
		Map<String, Object> manyDoc = doc(many.toArray());
		assertThatThrownBy(() -> ReportDocs.sanitize(manyDoc)).hasMessageContaining("too long");
		assertThat(ReportDocs.sanitize(Map.of("type", "doc")).toString()).isEqualTo("{type=doc, content=[{type=paragraph}]}");
		assertThat((List<?>) ReportDocs.sanitize(Map.of("type", "doc", "content", "nope")).get("content")).hasSize(1);
	}

	// ------------------------------------------------------------------ renumber

	@Test
	@DisplayName("T-15-11 renumber(): first appearance per source across sections, labels from the claims, unknown claims removed, no gaps")
	void renumberAcrossSections() {
		Map<UUID, ClaimInfo> claims = Map.of(C1, new ClaimInfo(S1, Set.of(), "SUPPORTED"), C2,
				new ClaimInfo(S2, Set.of(S3), "WEAK"), C3, new ClaimInfo(S1, Set.of(), "WEAK"));
		UUID unknown = UUID.randomUUID();
		// section A cites C2 (stale number 7) and an unknown claim; section B cites C1, C3 (same source) and C2 via S3
		Map<String, Object> a = doc(p(t("First "), cite(C2, null, 7), t(" and "), cite(unknown, S1, 1)));
		Map<String, Object> b = doc(Map.of("type", "bulletList", "content", List.of(Map.of("type", "listItem", "content",
				List.of(p(t("x"), cite(C1, S2, 3), cite(C3, S1, 4), t(" "), cite(C3, null, 4), t(" y"), cite(C2, S3, 1)))))));
		Renumbered r = ReportDocs.renumber(List.of(a, b), claims);
		assertThat(r.removed()).isEqualTo(1);
		assertThat(r.numbers()).containsExactly(Map.entry(S2, 1), Map.entry(S1, 2), Map.entry(S3, 3));
		List<Map<String, Object>> ca = citations(r.docs().get(0));
		assertThat(ca).hasSize(1);
		assertThat(ca.get(0)).containsEntry("n", 1).containsEntry("sourceId", S2.toString()).containsEntry("label", "WEAK");
		List<Map<String, Object>> cb = citations(r.docs().get(1));
		// C1 asked for S2 (not a source that judged it) → its own S1; C3 right after it is the same [2] → kept once,
		// even with only a space in between; C2 via S3 (a source that judged it) keeps S3 → [3]
		assertThat(cb).extracting(c -> c.get("n")).containsExactly(2, 3);
		assertThat(cb.get(0)).containsEntry("claimId", C1.toString()).containsEntry("label", "SUPPORTED");
		assertThat(cb.get(1)).containsEntry("sourceId", S3.toString());
		assertThat(r.docs().get(1).toString()).contains("text=x", "text= y");
		// deleting section A's paragraph renumbers without a gap
		Renumbered after = ReportDocs.renumber(List.of(doc(p(t("gone"))), b), claims);
		assertThat(after.numbers()).containsExactly(Map.entry(S1, 1), Map.entry(S3, 2));
	}

	// ------------------------------------------------------------------ markdown

	@Test
	@DisplayName("T-15-12 markdown(): content_md re-derived from the document with [n] citations, marks, lists, quotes, code")
	void markdown() {
		Map<String, Object> d = doc(
				Map.of("type", "heading", "attrs", Map.of("level", 3), "content", List.of(t("Findings"))),
				p(t("Bold", "bold"), t(" and "), t("it", "italic"), t(" "), t("c", "code"), t(" "), t("gone", "strike"),
						cite(C1, S1, 1), cite(C2, S1, 1), cite(C3, S2, 2), Map.of("type", "hardBreak"), t("next")),
				Map.of("type", "bulletList", "content", List.of(
						Map.of("type", "listItem", "content", List.of(p(t("one")), p(t("more")))),
						Map.of("type", "listItem", "content", List.of(p(t("two")))))),
				Map.of("type", "orderedList", "content", List.of(Map.of("type", "listItem", "content", List.of(p(t("first")))))),
				Map.of("type", "blockquote", "content", List.of(p(t("q1")), p(t("q2")))),
				Map.of("type", "codeBlock", "content", List.of(t("a < b"))),
				Map.of("type", "horizontalRule"),
				Map.of("type", "paragraph"));
		assertThat(ReportDocs.markdown(d)).isEqualTo("""
				### Findings

				**Bold** and *it* `c` ~~gone~~[1][2]\s\s
				next

				- one
				  more
				- two

				1. first

				> q1
				>
				> q2

				```
				a < b
				```

				---""");
		assertThat(ReportDocs.markdown(doc(p(Map.of("type", "citation", "attrs", Map.of()), Map.of("type", "x"), t("ok")))))
				.isEqualTo("ok");
		assertThat(ReportDocs.markdown(doc(Map.of("type", "heading", "content", List.of(t("h")))))).isEqualTo("## h");
	}

	// ------------------------------------------------------------------ select

	@Test
	@DisplayName("T-15-13 select(): editor positions → the selected text with [c:<claim>] markers; blocks separated; bad ranges refused")
	void select() {
		// positions: <p>0 "Alpha "1..7 [C1]7 " beta"8..13 </p>13; <ul>14 <li>15 <p>16 "Gamma"17..22 [C2]22 br23 "d"24..25, <hr>28
		Map<String, Object> d = doc(p(t("Alpha "), cite(C1, S1, 1), t(" beta")),
				Map.of("type", "bulletList", "content", List.of(Map.of("type", "listItem", "content",
						List.of(p(t("Gamma"), cite(C2, S2, 2), Map.of("type", "hardBreak"), t("d")))))),
				Map.of("type", "horizontalRule"));
		assertThat(ReportDocs.size(d)).isEqualTo(29);
		Selection all = ReportDocs.select(d, 0, 29);
		assertThat(all.text()).isEqualTo("Alpha [c:" + C1 + "] beta\n\nGamma[c:" + C2 + "]\nd");
		assertThat(all.claimIds()).containsExactly(C1, C2);
		Selection part = ReportDocs.select(d, 3, 8);
		assertThat(part.text()).isEqualTo("pha [c:" + C1 + "]");
		assertThat(ReportDocs.select(d, 9, 19).text()).isEqualTo("beta\n\nGa");
		assertThat(ReportDocs.select(d, 9, 19).claimIds()).isEmpty();
		assertThatThrownBy(() -> ReportDocs.select(d, 5, 5)).hasMessageContaining("Select");
		assertThatThrownBy(() -> ReportDocs.select(d, -1, 4)).hasMessageContaining("Select");
		assertThatThrownBy(() -> ReportDocs.select(d, 13, 15)).hasMessageContaining("Select"); // only boundaries
	}

}
