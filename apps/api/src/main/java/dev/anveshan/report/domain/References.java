package dev.anveshan.report.domain;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Reference list entries in IEEE and APA (7th) style, from the fields Anveshan knows about a source. A small, exact
 * formatter instead of citeproc + a 10 MB style bundle (decision D-079): our sources carry only authors, title,
 * site/venue, date, DOI and URL, and the output must be testable string for string (T-14-03). Pure.
 */
public final class References {

	public record Ref(String title, List<String> authors, String site, LocalDate published, String url, String doi) {

		/** Cleaned for print (T-16-14): only real person names, a readable title, never "doi.org" as the site. */
		public Ref {
			authors = personNames(authors);
			title = titleCase(title);
			site = site == null || site.isBlank() || site.strip().matches("(?i)(dx\\.)?doi\\.org") ? null : site.strip();
		}
	}

	/** Words that mark an organisation, journal or scraped page text, not a person. */
	private static final java.util.regex.Pattern NOT_A_PERSON = java.util.regex.Pattern.compile(
			"(?i)\\b(journal|international|invention|university|research|review|institute|institution|college|"
					+ "society|association|department|academy|publisher|publishing|press|editor|editorial|centre|center|"
					+ "council|ministry|foundation|organi[sz]ation|school|faculty|board|inc|ltd|llc)\\b");

	/** Keeps author strings that look like a person's name: no '@', no digits, no institution or journal words. */
	public static List<String> personNames(List<String> authors) {
		List<String> out = new ArrayList<>();
		for (String a : authors == null ? List.<String>of() : authors) {
			String n = clean(a).replaceFirst("(?i)^(dr|prof|mr|mrs|ms)\\.?\\s+", "");
			if (n.isEmpty() || n.indexOf('@') >= 0 || n.matches(".*\\d.*") || NOT_A_PERSON.matcher(n).find()
					|| n.split(" ").length > 6) {
				continue;
			}
			out.add(n);
		}
		return List.copyOf(out);
	}

	private static final java.util.Set<String> SMALL = java.util.Set.of("a", "an", "and", "as", "at", "but", "by",
			"for", "from", "in", "into", "nor", "of", "on", "or", "the", "to", "vs", "via", "with");

	/** An ALL-CAPS title in title case ("THE ROLE OF AI IN EDUCATION" → "The Role of Ai in Education"); others unchanged. */
	public static String titleCase(String title) {
		String t = clean(title);
		boolean hasLower = t.chars().anyMatch(Character::isLowerCase);
		if (t.isEmpty() || hasLower || t.chars().noneMatch(Character::isUpperCase)) {
			return t;
		}
		String[] words = t.toLowerCase(java.util.Locale.ROOT).split(" ");
		StringBuilder b = new StringBuilder();
		for (int i = 0; i < words.length; i++) {
			String w = words[i];
			boolean small = i > 0 && i < words.length - 1 && SMALL.contains(w) && !words[i - 1].endsWith(":");
			b.append(i > 0 ? " " : "").append(small || w.isEmpty() ? w : Character.toUpperCase(w.charAt(0)) + w.substring(1));
		}
		return b.toString();
	}

	private References() {
	}

	/** {@code [3] R. Sharma and A. Gupta, "Title," Site, 2024, doi: 10.1000/x.} — web sources end with the URL. */
	public static String ieee(int n, Ref r) {
		StringBuilder b = new StringBuilder("[").append(n).append("] ");
		String authors = ieeeAuthors(r.authors());
		if (!authors.isEmpty()) {
			b.append(authors).append(", ");
		}
		b.append('"').append(clean(r.title())).append(",\"");
		if (notBlank(r.site())) {
			b.append(' ').append(r.site()).append(',');
		}
		b.append(' ').append(r.published() == null ? "n.d." : Integer.toString(r.published().getYear()));
		String end = b.charAt(b.length() - 1) == '.' ? "" : "."; // "n.d." already ends with a period
		String doi = doiOnly(r.doi());
		if (doi != null) {
			b.append(", doi: ").append(doi).append('.');
		}
		else if (notBlank(r.url())) {
			b.append(end).append(" [Online]. Available: ").append(r.url());
		}
		else {
			b.append(end);
		}
		return b.toString();
	}

	/** {@code Sharma, R., & Gupta, A. (2024). Title. Site. https://doi.org/10.1000/x} */
	public static String apa(Ref r) {
		StringBuilder b = new StringBuilder();
		String authors = apaAuthors(r.authors());
		String year = "(" + (r.published() == null ? "n.d." : Integer.toString(r.published().getYear())) + ").";
		String title = clean(r.title());
		if (authors.isEmpty()) {
			b.append(title).append(endWithPeriod(title) ? " " : ". ").append(year);
		}
		else {
			b.append(authors).append(' ').append(year).append(' ').append(title).append(endWithPeriod(title) ? "" : ".");
		}
		if (notBlank(r.site())) {
			b.append(' ').append(r.site()).append('.');
		}
		String doi = doiOnly(r.doi());
		if (doi != null) {
			b.append(" https://doi.org/").append(doi);
		}
		else if (notBlank(r.url())) {
			b.append(' ').append(r.url());
		}
		return b.toString();
	}

	/** "Rahul Kumar Sharma" → "R. K. Sharma"; already-initialled names are kept. 1: A; 2: A and B; 3–6: A, B, and C; 7+: A et al. */
	static String ieeeAuthors(List<String> authors) {
		List<String> names = new ArrayList<>();
		for (String a : authors == null ? List.<String>of() : authors) {
			String[] parts = clean(a).split("\\s+");
			if (parts.length == 0 || parts[0].isEmpty()) {
				continue;
			}
			StringBuilder n = new StringBuilder();
			for (int i = 0; i < parts.length - 1; i++) {
				n.append(Character.toUpperCase(parts[i].charAt(0))).append(". ");
			}
			names.add(n.append(parts[parts.length - 1]).toString());
		}
		if (names.isEmpty()) {
			return "";
		}
		if (names.size() > 6) {
			return names.get(0) + " et al.";
		}
		if (names.size() == 1) {
			return names.get(0);
		}
		if (names.size() == 2) {
			return names.get(0) + " and " + names.get(1);
		}
		return String.join(", ", names.subList(0, names.size() - 1)) + ", and " + names.get(names.size() - 1);
	}

	/** "Rahul Sharma" → "Sharma, R."; 2: A, & B; 3–20: A, B, & C; 21+: first 19, …, last. */
	static String apaAuthors(List<String> authors) {
		List<String> names = new ArrayList<>();
		for (String a : authors == null ? List.<String>of() : authors) {
			String[] parts = clean(a).split("\\s+");
			if (parts.length == 0 || parts[0].isEmpty()) {
				continue;
			}
			StringBuilder n = new StringBuilder(parts[parts.length - 1]);
			if (parts.length > 1) {
				n.append(",");
				for (int i = 0; i < parts.length - 1; i++) {
					n.append(' ').append(Character.toUpperCase(parts[i].charAt(0))).append('.');
				}
			}
			names.add(n.toString());
		}
		if (names.isEmpty()) {
			return "";
		}
		if (names.size() == 1) {
			return names.get(0);
		}
		if (names.size() > 20) {
			return String.join(", ", names.subList(0, 19)) + ", … " + names.get(names.size() - 1);
		}
		return String.join(", ", names.subList(0, names.size() - 1)) + ", & " + names.get(names.size() - 1);
	}

	private static String doiOnly(String doi) {
		if (!notBlank(doi)) {
			return null;
		}
		return doi.replaceFirst("(?i)^https?://(dx\\.)?doi\\.org/", "");
	}

	private static String clean(String s) {
		return s == null ? "" : s.strip().replaceAll("\\s+", " ");
	}

	private static boolean endWithPeriod(String s) {
		return s.endsWith(".") || s.endsWith("?") || s.endsWith("!");
	}

	private static boolean notBlank(String s) {
		return s != null && !s.isBlank();
	}

}
