package dev.anveshan.sources.domain;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;

/**
 * Credibility tier + reason (TECHSPEC §12.8): a pure function of the source's type, address and metadata. The lists
 * live in {@code credibility.yml}, never in code. Shown to the user next to every source; used by the Labeler.
 */
public final class Credibility {

	public enum Tier {
		HIGH, MEDIUM, LOW
	}

	/** What a source is known to be, before reading it. {@code type} is the search candidate's type name. */
	public record Signals(String type, String doi, String url, LocalDate published, Integer stars,
			LocalDate lastCommit) {
	}

	public record Rating(Tier tier, String reason) {
	}

	/**
	 * @param officialDocs           documentation hosts, e.g. docs.spring.io
	 * @param governmentSuffixes     host suffixes such as .gov, .gov.in, .nic.in
	 * @param standardsBodies        hosts such as w3.org, ietf.org, iso.org
	 * @param preprintHosts          hosts such as arxiv.org, biorxiv.org
	 * @param establishedPublications news / magazine hosts
	 * @param encyclopedias          hosts such as wikipedia.org
	 */
	public record Rules(List<String> officialDocs, List<String> governmentSuffixes, List<String> standardsBodies,
			List<String> preprintHosts, List<String> establishedPublications, List<String> encyclopedias,
			int repoMinStars, int repoMaxMonthsSinceCommit) {

		public Rules {
			officialDocs = List.copyOf(officialDocs);
			governmentSuffixes = List.copyOf(governmentSuffixes);
			standardsBodies = List.copyOf(standardsBodies);
			preprintHosts = List.copyOf(preprintHosts);
			establishedPublications = List.copyOf(establishedPublications);
			encyclopedias = List.copyOf(encyclopedias);
		}

	}

	private Credibility() {
	}

	public static Rating rate(Signals s, Rules r, LocalDate today) {
		String host = s.url() == null ? null : Urls.host(s.url());
		String year = s.published() == null ? "" : ", " + s.published().getYear();
		if ("PAPER".equals(s.type()) && s.doi() != null) {
			return new Rating(Tier.HIGH, "Peer-reviewed paper" + year);
		}
		if (host != null && onAny(host, r.officialDocs())) {
			return new Rating(Tier.HIGH, "Official documentation");
		}
		if (host != null && (endsWithAny(host, r.governmentSuffixes()) || onAny(host, r.standardsBodies()))) {
			return new Rating(Tier.HIGH, "Government / standards source");
		}
		if ("PREPRINT".equals(s.type()) || (host != null && onAny(host, r.preprintHosts()))) {
			return new Rating(Tier.MEDIUM, "Preprint (not peer-reviewed)" + year);
		}
		if ("REPOSITORY".equals(s.type()) && s.stars() != null && s.stars() >= r.repoMinStars() && s.lastCommit() != null
				&& !s.lastCommit().isBefore(today.minusMonths(r.repoMaxMonthsSinceCommit()))) {
			return new Rating(Tier.MEDIUM, "Maintained repository (" + s.stars() + "★)");
		}
		if (host != null && onAny(host, r.establishedPublications())) {
			return new Rating(Tier.MEDIUM, "Established publication");
		}
		if ("ENCYCLOPEDIA".equals(s.type()) || (host != null && onAny(host, r.encyclopedias()))) {
			return new Rating(Tier.MEDIUM, "Encyclopedia (community-edited)");
		}
		return new Rating(Tier.LOW, "Personal blog / unknown author");
	}

	/** The stored {@code source_document.type} for a candidate. */
	public static String documentType(String candidateType, String url, Rules r) {
		String host = url == null ? null : Urls.host(url);
		return switch (candidateType == null ? "" : candidateType) {
			case "PAPER" -> "PAPER";
			case "PREPRINT" -> "PREPRINT";
			case "ENCYCLOPEDIA" -> "WIKI";
			case "REPOSITORY" -> "REPO";
			default -> {
				if (host == null) {
					yield "OTHER";
				}
				if (onAny(host, r.officialDocs())) {
					yield "DOCS";
				}
				if (onAny(host, r.establishedPublications())) {
					yield "NEWS";
				}
				if (onAny(host, List.of("stackoverflow.com", "stackexchange.com", "reddit.com", "quora.com"))) {
					yield "FORUM";
				}
				String path = url.toLowerCase(Locale.ROOT);
				yield host.startsWith("blog.") || path.contains("/blog") || onAny(host, List.of("medium.com", "dev.to"))
						? "BLOG" : "OTHER";
			}
		};
	}

	private static boolean onAny(String host, List<String> sites) {
		return sites.stream().anyMatch(s -> host.equals(s) || host.endsWith("." + s));
	}

	private static boolean endsWithAny(String host, List<String> suffixes) {
		return suffixes.stream().anyMatch(host::endsWith);
	}

}
