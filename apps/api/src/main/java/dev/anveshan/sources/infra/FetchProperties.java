package dev.anveshan.sources.infra;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Fetch and clean ({@code anveshan.fetch.*}, TECHSPEC §12.4).
 *
 * @param minTextChars  a page with less readable text than this counts as unavailable (paywall stub, empty shell)
 * @param maxPdfPages   only the first pages of a long PDF are read (CPU on a laptop / small VM)
 * @param parallel      pages fetched at once for one sub-question
 * @param robotsTtl     robots.txt is cached per host this long
 * @param extraPorts    ports allowed besides 80/443 — tests only (a local fake site); empty in production
 */
@ConfigurationProperties("anveshan.fetch")
public record FetchProperties(int minTextChars, int maxPdfPages, int parallel, Duration robotsTtl,
		List<Integer> extraPorts) {

	public FetchProperties {
		if (minTextChars < 1 || maxPdfPages < 1 || parallel < 1 || robotsTtl == null) {
			throw new IllegalStateException("anveshan.fetch.* (min-text-chars, max-pdf-pages, parallel, robots-ttl) are required");
		}
		extraPorts = extraPorts == null ? List.of() : List.copyOf(extraPorts);
	}

}
