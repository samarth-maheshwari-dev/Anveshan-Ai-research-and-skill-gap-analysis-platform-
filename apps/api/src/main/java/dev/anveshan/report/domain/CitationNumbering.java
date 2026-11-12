package dev.anveshan.report.domain;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Citation numbers (invariant L8): one number per source, in order of first appearance across the whole report.
 * Recomputed from the current text every time, so removing a paragraph never leaves a gap (T-14-02). Pure.
 */
public final class CitationNumbering {

	private CitationNumbering() {
	}

	/** @param sourcesInOrder the source of every citation, in reading order (repeats allowed) */
	public static Map<UUID, Integer> number(List<UUID> sourcesInOrder) {
		Map<UUID, Integer> n = new LinkedHashMap<>();
		for (UUID s : sourcesInOrder) {
			n.putIfAbsent(s, n.size() + 1);
		}
		return n;
	}

}
