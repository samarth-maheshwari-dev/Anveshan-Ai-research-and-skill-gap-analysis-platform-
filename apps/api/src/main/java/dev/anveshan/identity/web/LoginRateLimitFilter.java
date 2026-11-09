package dev.anveshan.identity.web;

import java.io.IOException;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;

import dev.anveshan.shared.web.ProblemResponses;
import io.github.bucket4j.Bucket;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * {@code /oauth2/**} and {@code /login/**}: N requests per minute per client IP (TECHSPEC §5.9).
 * In-memory buckets, capped at 10,000 IPs (least recently used are dropped).
 */
final class LoginRateLimitFilter extends OncePerRequestFilter {

	private static final int MAX_TRACKED_IPS = 10_000;

	private final int perMinute;
	private final Map<String, Bucket> buckets = Collections.synchronizedMap(new LinkedHashMap<>(256, 0.75f, true) {
		@Override
		protected boolean removeEldestEntry(Map.Entry<String, Bucket> eldest) {
			return size() > MAX_TRACKED_IPS;
		}
	});

	LoginRateLimitFilter(int perMinute) {
		this.perMinute = perMinute;
	}

	@Override
	protected boolean shouldNotFilter(HttpServletRequest request) {
		String path = request.getRequestURI().substring(request.getContextPath().length());
		return !(path.startsWith("/oauth2/") || path.startsWith("/login/"));
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		Bucket bucket = buckets.computeIfAbsent(request.getRemoteAddr(), ip -> Bucket.builder()
				.addLimit(limit -> limit.capacity(perMinute).refillGreedy(perMinute, Duration.ofMinutes(1)))
				.build());
		var probe = bucket.tryConsumeAndReturnRemaining(1);
		if (probe.isConsumed()) {
			chain.doFilter(request, response);
			return;
		}
		long retryAfter = Math.max(1, (probe.getNanosToWaitForRefill() + 999_999_999L) / 1_000_000_000L);
		response.setHeader("Retry-After", String.valueOf(retryAfter));
		ProblemResponses.write(request, response, HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED",
				"Too many login attempts", "Please wait " + retryAfter + " seconds and try again.");
	}

}
