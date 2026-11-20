package dev.anveshan.sources.infra;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Fetch and clean ({@code anveshan.fetch.*}, TECHSPEC §12.4).
 *
 * @param minTextChars  a page with less readable text than this counts as unavailable (paywall stub, empty shell)
 * @param maxPdfPages   only the first pages of a long PDF are read (CPU on a laptop / small VM)
 * @param parallel      pages fetched at once for one sub-question
 * @param robotsTtl     robots.txt is cached per host this long
 * @param extraPorts    ports allowed besides 80/443 — tests only (a local fake site); empty in production
 * @param maxChunks     only the first chunks of a long document are embedded and stored (speed, D-107)
 */
@ConfigurationProperties("anveshan.fetch")
public record FetchProperties(int minTextChars, int maxPdfPages, int parallel, Duration robotsTtl,
		List<Integer> extraPorts, @DefaultValue("120") int maxChunks) {

	public FetchProperties {
		if (minTextChars < 1 || maxPdfPages < 1 || parallel < 1 || robotsTtl == null || maxChunks < 1) {
			throw new IllegalStateException("anveshan.fetch.* (min-text-chars, max-pdf-pages, parallel, robots-ttl) are required");
		}
		extraPorts = extraPorts == null ? List.of() : List.copyOf(extraPorts);
	}

}
