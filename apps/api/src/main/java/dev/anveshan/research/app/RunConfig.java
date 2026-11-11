package dev.anveshan.research.app;

import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import dev.anveshan.research.domain.CustomRules;
import dev.anveshan.research.domain.ResearchSize;
import dev.anveshan.research.domain.RunOptions.Depth;
import dev.anveshan.research.domain.RunOptions.Language;
import dev.anveshan.research.domain.RunOptions.Objective;
import dev.anveshan.research.domain.RunOptions.OutputStyle;
import dev.anveshan.research.domain.RunOptions.SourceType;
import dev.anveshan.research.domain.RunOptions.TimeRange;

/**
 * A run's options (PRD Module C + the owner's custom options, D-097), as stored in {@code research_run.config}.
 * Fields may be null while the form is being resolved: the user's choices win, then the template's
 * {@code form_defaults} ({@link #withDefaults}), then the PRD defaults ({@link #resolved}). {@link #validated}
 * checks and normalizes the free-text parts; the "custom" fields are kept only with the choice that uses them.
 *
 * @param objectiveText      with objective OTHER: the user's own objective
 * @param subQuestions       with depth CUSTOM: 1–8
 * @param sourcesPerQuestion with depth CUSTOM: 2–10
 * @param sinceYear          with time range SINCE_YEAR
 * @param outputStyleText    with output style CUSTOM: the user's own format
 * @param sections           the user's own report sections (override the template's)
 * @param instructions       extra guidance for the planner and writer ("focus on India")
 * @param includeSites       websites to prefer (host names)
 * @param excludeSites       websites to avoid (host names)
 */
public record RunConfig(Objective objective, String objectiveText, Depth depth, Integer subQuestions,
		Integer sourcesPerQuestion, List<SourceType> sources, TimeRange timeRange, Integer sinceYear,
		OutputStyle outputStyle, String outputStyleText, Language language, String templateCode,
		List<String> sections, String instructions, List<String> includeSites, List<String> excludeSites) {

	public static RunConfig empty() {
		return new RunConfig(null, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
				null);
	}

	/** Fills the gaps from a template's defaults (C-FR-5 "pre-fills the form"). */
	public RunConfig withDefaults(RunConfig template) {
		return new RunConfig(objective != null ? objective : template.objective(), objectiveText,
				depth != null ? depth : template.depth(), subQuestions, sourcesPerQuestion,
				sources != null && !sources.isEmpty() ? sources : template.sources(),
				timeRange != null ? timeRange : template.timeRange(), sinceYear,
				outputStyle != null ? outputStyle : template.outputStyle(), outputStyleText,
				language != null ? language : template.language(), templateCode, sections, instructions, includeSites,
				excludeSites);
	}

	/** Every choice set: the remaining gaps get the PRD defaults; sources are de-duplicated in a stable order. */
	public RunConfig resolved() {
		return new RunConfig(objective != null ? objective : Objective.PROJECT_REPORT, objectiveText,
				depth != null ? depth : Depth.STANDARD, subQuestions, sourcesPerQuestion,
				sources == null || sources.isEmpty() ? List.of(SourceType.WEB, SourceType.ACADEMIC)
						: List.copyOf(EnumSet.copyOf(sources)),
				timeRange != null ? timeRange : TimeRange.ANY, sinceYear,
				outputStyle != null ? outputStyle : OutputStyle.PROJECT_REPORT, outputStyleText,
				language != null ? language : Language.en, templateCode, sections, instructions, includeSites,
				excludeSites);
	}

	/**
	 * Call on a {@link #resolved()} config. Checks the custom parts and drops the ones the choices don't use.
	 *
	 * @throws IllegalArgumentException with a message for the user
	 */
	public RunConfig validated(int currentYear) {
		String objText = objective == Objective.OTHER
				? required(CustomRules.text(objectiveText, "The objective", CustomRules.LABEL_MAX),
						"Describe your objective in a few words.")
				: null;
		String styleText = outputStyle == OutputStyle.CUSTOM
				? required(CustomRules.text(outputStyleText, "The report format", CustomRules.LABEL_MAX),
						"Describe the report format you want.")
				: null;
		Integer subQ = null;
		Integer perQ = null;
		if (depth == Depth.CUSTOM) {
			ResearchSize size = ResearchSize.of(depth, subQuestions, sourcesPerQuestion);
			subQ = size.subQuestions();
			perQ = size.sourcesPerQuestion();
		}
		Integer since = timeRange == TimeRange.SINCE_YEAR ? CustomRules.sinceYear(sinceYear, currentYear) : null;
		List<String> include = CustomRules.sites(includeSites, "Prefer websites");
		List<String> exclude = CustomRules.sites(excludeSites, "Avoid websites");
		CustomRules.noOverlap(include, exclude);
		return new RunConfig(objective, objText, depth, subQ, perQ, sources, timeRange, since, outputStyle, styleText,
				language, templateCode, CustomRules.sections(sections),
				CustomRules.text(instructions, "Extra instructions", CustomRules.INSTRUCTIONS_MAX), include, exclude);
	}

	/** Call on a {@link #resolved()} config. */
	public ResearchSize size() {
		return ResearchSize.of(depth, subQuestions, sourcesPerQuestion);
	}

	/** Call on a {@link #resolved()} config. */
	public Set<SourceType> sourceSet() {
		return EnumSet.copyOf(sources);
	}

	/** What goes into {@code config} (a resolved, validated config: names; unset custom parts left out). */
	public Map<String, Object> toMap() {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("objective", objective.name());
		putIf(m, "objectiveText", objectiveText);
		m.put("depth", depth.name());
		putIf(m, "subQuestions", subQuestions);
		putIf(m, "sourcesPerQuestion", sourcesPerQuestion);
		m.put("sources", sources.stream().map(Enum::name).toList());
		m.put("timeRange", timeRange.name());
		putIf(m, "sinceYear", sinceYear);
		m.put("outputStyle", outputStyle.name());
		putIf(m, "outputStyleText", outputStyleText);
		m.put("language", language.name());
		putIf(m, "templateCode", templateCode);
		putIf(m, "sections", sections);
		putIf(m, "instructions", instructions);
		putIf(m, "includeSites", includeSites);
		putIf(m, "excludeSites", excludeSites);
		return m;
	}

	private static void putIf(Map<String, Object> m, String key, Object value) {
		if (value != null) {
			m.put(key, value);
		}
	}

	private static String required(String value, String message) {
		if (value == null) {
			throw new IllegalArgumentException(message);
		}
		return value;
	}

}
