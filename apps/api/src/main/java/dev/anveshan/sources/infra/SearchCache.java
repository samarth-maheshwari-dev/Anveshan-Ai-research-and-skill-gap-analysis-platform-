package dev.anveshan.sources.infra;

import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Component;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import dev.anveshan.sources.api.SourceSearch.Candidate;

/**
 * Search responses cached by (API, normalized query, filters) for {@code cache-ttl} (TECHSPEC §11.3): a re-run or a
 * similar plan doesn't spend the free APIs' limits again. In memory, bounded. BYOK web search is keyed per user.
 */
@Component
public class SearchCache {

	private final Cache<String, List<Candidate>> cache;

	SearchCache(SearchProperties props) {
		this.cache = Caffeine.newBuilder().maximumSize(5_000).expireAfterWrite(props.cacheTtl()).build();
	}

	public Optional<List<Candidate>> get(String key) {
		return Optional.ofNullable(cache.getIfPresent(key));
	}

	public void put(String key, List<Candidate> results) {
		cache.put(key, List.copyOf(results));
	}

}
