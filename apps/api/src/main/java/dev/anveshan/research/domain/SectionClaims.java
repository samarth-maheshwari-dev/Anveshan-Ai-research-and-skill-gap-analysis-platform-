package dev.anveshan.research.domain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * Which claims each report section is written from (TECHSPEC §12.9), by meaning only — no LLM. Pure.
 * <p>
 * Every claim goes to the ONE section it is closest to, so sections don't all repeat the same facts (the first real
 * reports did: with 13 claims and a 15-per-section budget, all six sections got all 13). A section with fewer than
 * {@code minPerSection} claims borrows its closest others, so an Introduction or Conclusion still has material; that
 * is the only overlap. A section never gets more than {@code maxPerSection}; the overflow moves to the claim's next
 * closest section that has room.
 */
public final class SectionClaims {

	/** A claim and how close it is to a section (cosine similarity). */
	public record Scored(String claimId, double similarity) {
	}

	private SectionClaims() {
	}

	/**
	 * @param scores one list per section (in section order), each with every usable claim and its similarity
	 * @return one ordered set of claim ids per section, closest first
	 */
	public static List<LinkedHashSet<String>> assign(List<List<Scored>> scores, int minPerSection, int maxPerSection) {
		int n = scores.size();
		List<Map<String, Double>> sim = new ArrayList<>();
		for (List<Scored> s : scores) {
			Map<String, Double> m = new HashMap<>();
			s.forEach(x -> m.merge(x.claimId(), x.similarity(), Math::max));
			sim.add(m);
		}
		// every claim once, in a stable order (first appearance)
		LinkedHashSet<String> all = new LinkedHashSet<>();
		scores.forEach(s -> s.forEach(x -> all.add(x.claimId())));

		List<List<String>> chosen = new ArrayList<>();
		for (int i = 0; i < n; i++) {
			chosen.add(new ArrayList<>());
		}
		// strongest matches first, so a full section keeps its closest claims
		List<String> byStrength = new ArrayList<>(all);
		byStrength.sort(Comparator.comparingDouble((String c) -> -best(c, sim)));
		for (String claim : byStrength) {
			for (int section : ranked(claim, sim)) {
				if (chosen.get(section).size() < maxPerSection) {
					chosen.get(section).add(claim);
					break;
				}
			}
		}
		// thin sections borrow their closest claims (the only overlap)
		int floor = Math.min(minPerSection, all.size());
		for (int i = 0; i < n; i++) {
			List<String> mine = chosen.get(i);
			List<Scored> closest = new ArrayList<>(scores.get(i));
			closest.sort(Comparator.comparingDouble(x -> -x.similarity()));
			for (Scored x : closest) {
				if (mine.size() >= Math.min(floor, maxPerSection)) {
					break;
				}
				if (!mine.contains(x.claimId())) {
					mine.add(x.claimId());
				}
			}
		}
		List<LinkedHashSet<String>> out = new ArrayList<>();
		for (int i = 0; i < n; i++) {
			Map<String, Double> m = sim.get(i);
			List<String> mine = new ArrayList<>(chosen.get(i));
			mine.sort(Comparator.comparingDouble((String c) -> -m.getOrDefault(c, -1.0)));
			out.add(new LinkedHashSet<>(mine));
		}
		return out;
	}

	private static double best(String claim, List<Map<String, Double>> sim) {
		return sim.stream().mapToDouble(m -> m.getOrDefault(claim, -1.0)).max().orElse(-1.0);
	}

	/** Section indexes, closest to the claim first (ties: earlier section first). */
	private static List<Integer> ranked(String claim, List<Map<String, Double>> sim) {
		List<Integer> idx = new ArrayList<>();
		for (int i = 0; i < sim.size(); i++) {
			if (sim.get(i).containsKey(claim)) {
				idx.add(i);
			}
		}
		idx.sort(Comparator.comparingDouble((Integer i) -> -sim.get(i).get(claim)).thenComparingInt(i -> i));
		return idx;
	}

}
