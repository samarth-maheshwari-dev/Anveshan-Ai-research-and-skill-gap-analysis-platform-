package dev.anveshan.sources.infra;

import java.io.IOException;
import java.net.URI;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import crawlercommons.robots.BaseRobotRules;
import crawlercommons.robots.SimpleRobotRulesParser;
import dev.anveshan.shared.security.OutboundUrlGuard;
import dev.anveshan.shared.security.SafeHttp;
import dev.anveshan.shared.security.UnsafeUrlException;

/**
 * robots.txt, fetched once per host and cached (TECHSPEC §12.4), parsed with crawler-commons for the
 * {@code anveshanbot} agent. A missing robots.txt allows everything; a server error disallows for now (the
 * crawler-commons convention); an unreachable host is left to the page fetch to report.
 */
@Component
public class RobotsCache {

	private static final Logger log = LoggerFactory.getLogger(RobotsCache.class);
	static final String AGENT = "anveshanbot";
	static final long MAX_ROBOTS_BYTES = 512 * 1024;

	private final SafeHttp http;
	private final FetchProperties props;
	private final SimpleRobotRulesParser parser = new SimpleRobotRulesParser();
	private final Cache<String, BaseRobotRules> cache;

	RobotsCache(SafeHttp http, FetchProperties props) {
		this.http = http;
		this.props = props;
		this.cache = Caffeine.newBuilder().maximumSize(10_000).expireAfterWrite(props.robotsTtl()).build();
	}

	/** True when the site allows Anveshan to read {@code url}. */
	public boolean allowed(String url) {
		URI u = URI.create(url);
		String origin = u.getScheme() + "://" + u.getRawAuthority();
		BaseRobotRules rules = cache.get(origin, this::load);
		return rules.isAllowed(url);
	}

	private BaseRobotRules load(String origin) {
		String robotsUrl = origin + "/robots.txt";
		try {
			var fetched = http.get(URI.create(robotsUrl), policy(), MAX_ROBOTS_BYTES);
			if (fetched.status() >= 200 && fetched.status() < 300) {
				return parser.parseContent(robotsUrl, fetched.body(),
						fetched.contentType() == null ? "text/plain" : fetched.contentType(), List.of(AGENT));
			}
			return parser.failedFetch(fetched.status());
		}
		catch (IOException | UnsafeUrlException e) {
			log.debug("robots.txt unreachable for {}: {}", origin, e.getClass().getSimpleName());
			return parser.failedFetch(404); // can't tell: the page fetch itself will report a dead host
		}
	}

	OutboundUrlGuard.Policy policy() {
		OutboundUrlGuard.Policy p = OutboundUrlGuard.WEB_PAGE;
		for (int port : props.extraPorts()) {
			p = p.withExtraPort(port);
		}
		return p;
	}

}
