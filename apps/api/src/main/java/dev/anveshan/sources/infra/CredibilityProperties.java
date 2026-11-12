package dev.anveshan.sources.infra;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

import dev.anveshan.sources.domain.Credibility;

/** The lists in {@code credibility.yml} ({@code anveshan.credibility.*}), bound into the pure rules. */
@ConfigurationProperties("anveshan.credibility")
public record CredibilityProperties(List<String> officialDocs, List<String> governmentSuffixes,
		List<String> standardsBodies, List<String> preprintHosts, List<String> establishedPublications,
		List<String> encyclopedias, int repoMinStars, int repoMaxMonthsSinceCommit) {

	public Credibility.Rules rules() {
		return new Credibility.Rules(orEmpty(officialDocs), orEmpty(governmentSuffixes), orEmpty(standardsBodies),
				orEmpty(preprintHosts), orEmpty(establishedPublications), orEmpty(encyclopedias), repoMinStars,
				repoMaxMonthsSinceCommit);
	}

	private static List<String> orEmpty(List<String> l) {
		return l == null ? List.of() : l;
	}

}
