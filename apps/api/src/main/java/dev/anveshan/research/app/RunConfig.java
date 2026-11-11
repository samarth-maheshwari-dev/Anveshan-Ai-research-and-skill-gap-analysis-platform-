package dev.anveshan.research.app;

import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import dev.anveshan.research.domain.RunOptions.Depth;
import dev.anveshan.research.domain.RunOptions.Language;
import dev.anveshan.research.domain.RunOptions.Objective;
import dev.anveshan.research.domain.RunOptions.OutputStyle;
import dev.anveshan.research.domain.RunOptions.SourceType;
import dev.anveshan.research.domain.RunOptions.TimeRange;

/**
 * A run's options (PRD Module C), as stored in {@code research_run.config}. Fields may be null while the form is
 * being resolved: the user's choices win, then the template's {@code form_defaults} ({@link #withDefaults}), then
 * the PRD defaults ({@link #resolved}: Project report, Standard, Web + Academic, any time, English).
 */
public record RunConfig(Objective objective, Depth depth, List<SourceType> sources, TimeRange timeRange,
		OutputStyle outputStyle, Language language, String templateCode) {

	/** Fills the gaps from a template's defaults (C-FR-5 "pre-fills the form"). */
	public RunConfig withDefaults(RunConfig template) {
		return new RunConfig(objective != null ? objective : template.objective(),
				depth != null ? depth : template.depth(),
				sources != null && !sources.isEmpty() ? sources : template.sources(),
				timeRange != null ? timeRange : template.timeRange(),
				outputStyle != null ? outputStyle : template.outputStyle(),
				language != null ? language : template.language(), templateCode);
	}

	/** Every field set: the remaining gaps get the PRD defaults; sources are de-duplicated in a stable order. */
	public RunConfig resolved() {
		return new RunConfig(objective != null ? objective : Objective.PROJECT_REPORT,
				depth != null ? depth : Depth.STANDARD,
				sources == null || sources.isEmpty() ? List.of(SourceType.WEB, SourceType.ACADEMIC)
						: List.copyOf(EnumSet.copyOf(sources)),
				timeRange != null ? timeRange : TimeRange.ANY,
				outputStyle != null ? outputStyle : OutputStyle.PROJECT_REPORT,
				language != null ? language : Language.en, templateCode);
	}

	/** Call on a {@link #resolved()} config. */
	public Set<SourceType> sourceSet() {
		return EnumSet.copyOf(sources);
	}

	/** What goes into {@code config} (a resolved config: names, no nulls except an absent template). */
	public Map<String, Object> toMap() {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("objective", objective.name());
		m.put("depth", depth.name());
		m.put("sources", sources.stream().map(Enum::name).toList());
		m.put("timeRange", timeRange.name());
		m.put("outputStyle", outputStyle.name());
		m.put("language", language.name());
		if (templateCode != null) {
			m.put("templateCode", templateCode);
		}
		return m;
	}

}
