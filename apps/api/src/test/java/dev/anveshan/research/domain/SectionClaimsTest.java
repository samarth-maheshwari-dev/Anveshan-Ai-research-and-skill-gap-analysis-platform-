package dev.anveshan.research.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import dev.anveshan.research.domain.SectionClaims.Scored;

class SectionClaimsTest {

	@Test
	@DisplayName("T-15-22 sections: every claim goes to its closest section once; thin sections borrow at most up to 3")
	void eachClaimOnce() {
		// 13 claims, 6 sections — the real report that repeated all 13 facts in every section
		List<List<Scored>> scores = new ArrayList<>();
		for (int s = 0; s < 6; s++) {
			List<Scored> list = new ArrayList<>();
			for (int c = 0; c < 13; c++) {
				// claim c is closest to section c % 6; a little noise elsewhere
				double sim = (c % 6 == s) ? 0.9 : 0.5 + (c * 7 + s * 3) % 10 / 100.0;
				list.add(new Scored("c" + c, sim));
			}
			scores.add(list);
		}
		List<LinkedHashSet<String>> out = SectionClaims.assign(scores, 3, 25);

		assertThat(out).hasSize(6);
		Set<String> union = new HashSet<>();
		out.forEach(union::addAll);
		assertThat(union).as("every usable claim is used").hasSize(13);
		for (int s = 0; s < 6; s++) {
			for (int c = s; c < 13; c += 6) {
				assertThat(out.get(s)).as("claim c" + c + " is in its closest section").contains("c" + c);
			}
			assertThat(out.get(s).size()).as("no section gets all 13").isBetween(3, 3);
		}
		int total = out.stream().mapToInt(Set::size).sum();
		assertThat(total).as("overlap only to fill thin sections").isLessThanOrEqualTo(13 + 6);
	}

	@Test
	@DisplayName("T-15-22 sections: a full section keeps its closest claims, the rest move to their next closest; order is closest first")
	void capAndOrder() {
		List<Scored> intro = List.of(new Scored("a", 0.95), new Scored("b", 0.90), new Scored("c", 0.85));
		List<Scored> body = List.of(new Scored("a", 0.30), new Scored("b", 0.40), new Scored("c", 0.80));
		List<LinkedHashSet<String>> out = SectionClaims.assign(List.of(intro, body), 0, 2);
		assertThat(new ArrayList<>(out.get(0))).containsExactly("a", "b");
		assertThat(new ArrayList<>(out.get(1))).containsExactly("c");
	}

	@Test
	@DisplayName("T-15-22 sections: fewer claims than the floor → each section gets what exists; no claims → empty sections")
	void fewClaims() {
		List<Scored> one = List.of(new Scored("x", 0.7));
		List<LinkedHashSet<String>> out = SectionClaims.assign(List.of(one, one, one), 3, 25);
		out.forEach(s -> assertThat(s).containsExactly("x"));
		assertThat(SectionClaims.assign(List.of(List.of(), List.of()), 3, 25)).allMatch(Set::isEmpty);
	}

}
