package dev.anveshan.report.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import dev.anveshan.report.domain.References.Ref;
import dev.anveshan.report.domain.TipTapDoc.Citation;

class ReportDomainTest {

	static final UUID A = UUID.fromString("00000000-0000-0000-0000-00000000000a");
	static final UUID B = UUID.fromString("00000000-0000-0000-0000-00000000000b");
	static final UUID C = UUID.fromString("00000000-0000-0000-0000-00000000000c");

	@Test
	@DisplayName("T-14-01 markers: known aliases → claim ids, unknown ones removed, list form split, case ignored")
	void markers() {
		var r = Markers.resolve("Apps help [c:C1]. Offices keep items 30 days [c:C2, C9]. Unknown [c:C7]. Lower [C:c1].",
				Map.of("C1", A, "C2", B));
		assertThat(r.markdown()).isEqualTo("Apps help[c:" + A + "]. Offices keep items 30 days[c:" + B + "]. Unknown. Lower[c:" + A + "].");
		assertThat(r.removed()).isEqualTo(2);
		assertThat(Markers.citedClaims(r.markdown())).containsExactly(A, B, A);
		assertThat(Markers.resolve("Over 40 % of students lose an item every year.", Map.of()).unmarked())
				.as("a factual sentence with no marker is counted").isEqualTo(1);
		assertThat(Markers.resolve("# Heading\n\nIn short:", Map.of()).unmarked()).isZero();
	}

	@Test
	@DisplayName("T-14-02 numbering: first appearance across sections; removing a paragraph leaves no gaps")
	void numbering() {
		UUID s1 = UUID.randomUUID();
		UUID s2 = UUID.randomUUID();
		UUID s3 = UUID.randomUUID();
		List<UUID> reading = List.of(s2, s1, s2, s3, s1);
		assertThat(CitationNumbering.number(reading)).containsExactly(Map.entry(s2, 1), Map.entry(s1, 2), Map.entry(s3, 3));
		// the paragraph citing s1 first is removed: renumbering from the current text has no gap
		List<UUID> afterDelete = new ArrayList<>(List.of(s2, s2, s3, s1));
		assertThat(CitationNumbering.number(afterDelete).values()).containsExactly(1, 2, 3);
	}

	static final Ref PAPER = new Ref("Lost and found systems for campuses", List.of("Rahul Sharma", "Anita Gupta"),
			"Journal of Campus Tech", LocalDate.of(2023, 4, 1), "https://doi.org/10.1000/xyz", "https://doi.org/10.1000/xyz");
	static final Ref WEB = new Ref("Campus lost and found guide", List.of(), "university.example", LocalDate.of(2025, 2, 3),
			"https://university.example/lost-found", null);
	static final Ref PREPRINT = new Ref("QR codes for item recovery?", List.of("A. B. Kumar", "Priya Nair", "Li Wei"),
			"arxiv.org", null, "https://arxiv.org/abs/2201.00001", null);

	@Test
	@DisplayName("T-14-03 references: IEEE and APA for a paper, a web page and an undated preprint")
	void references() {
		assertThat(References.ieee(1, PAPER))
				.isEqualTo("[1] R. Sharma and A. Gupta, \"Lost and found systems for campuses,\" Journal of Campus Tech, 2023, doi: 10.1000/xyz.");
		assertThat(References.ieee(2, WEB))
				.isEqualTo("[2] \"Campus lost and found guide,\" university.example, 2025. [Online]. Available: https://university.example/lost-found");
		assertThat(References.ieee(3, PREPRINT))
				.isEqualTo("[3] A. B. Kumar, P. Nair, and L. Wei, \"QR codes for item recovery?,\" arxiv.org, n.d. [Online]. Available: https://arxiv.org/abs/2201.00001");
		assertThat(References.apa(PAPER))
				.isEqualTo("Sharma, R., & Gupta, A. (2023). Lost and found systems for campuses. Journal of Campus Tech. https://doi.org/10.1000/xyz");
		assertThat(References.apa(WEB))
				.isEqualTo("Campus lost and found guide. (2025). university.example. https://university.example/lost-found");
		assertThat(References.apa(PREPRINT))
				.isEqualTo("Kumar, A. B., Nair, P., & Wei, L. (n.d.). QR codes for item recovery? arxiv.org. https://arxiv.org/abs/2201.00001");
	}

	@Test
	@DisplayName("T-14-05 Markdown → TipTap: citation nodes with numbers, safe subset only (no HTML, links keep text)")
	@SuppressWarnings("unchecked")
	void tiptap() {
		UUID src = UUID.randomUUID();
		String md = "## Findings\n\nStudents report items **online**[c:" + A + "][c:" + A + "]. See [the site](javascript:alert(1)).\n\n"
				+ "<script>alert(1)</script>\n\n- One [c:" + B + "]\n- Two [c:" + C + "]";
		Map<String, Object> doc = TipTapDoc.from(md, (claim, source) -> claim.equals(C) ? null
				: new Citation(claim, src, claim.equals(A) ? 1 : 2, "SUPPORTED"));
		String json = doc.toString();
		assertThat(json).doesNotContain("script", "javascript", "alert");
		assertThat(json).contains("the site");
		List<Map<String, Object>> cites = TipTapDoc.citations(doc);
		assertThat(cites).extracting(c -> c.get("n")).containsExactly(1, 2); // repeated [1] merged, unknown C dropped
		assertThat(cites.get(0).get("claimId")).isEqualTo(A.toString());
		List<Map<String, Object>> content = (List<Map<String, Object>>) doc.get("content");
		assertThat(content.get(0).get("type")).isEqualTo("heading");
		assertThat(content).extracting(m -> m.get("type")).contains("paragraph", "bulletList").doesNotContain("html");
		assertThat(TipTapDoc.markdownWithNumbers("A[c:" + A + "][c:" + A + "] b[c:" + B + "]", c -> c.equals(A) ? 1 : 2, (c, s) -> 9))
				.isEqualTo("A[1] b[2]");
	}

}
