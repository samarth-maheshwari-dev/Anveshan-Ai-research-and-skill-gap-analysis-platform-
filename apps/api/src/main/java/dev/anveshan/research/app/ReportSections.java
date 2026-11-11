package dev.anveshan.research.app;

import java.util.List;

import org.springframework.stereotype.Component;

import dev.anveshan.research.infra.TemplateStore;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** The report sections a run will fill: the user's own, else its template's, else none (the writer's default). */
@Component
class ReportSections {

	/** Sections of a report without own sections or a template (one WRITE call each). */
	static final int DEFAULT_COUNT = 5;

	private record Section(String key, String title, String instructions) {
	}

	private static final TypeReference<List<Section>> SECTIONS = new TypeReference<>() {
	};

	private final TemplateStore templates;
	private final JsonMapper json;

	ReportSections(TemplateStore templates, JsonMapper json) {
		this.templates = templates;
		this.json = json;
	}

	/** Titles in order; empty when the writer decides. */
	List<String> titles(RunConfig config) {
		if (config.sections() != null && !config.sections().isEmpty()) {
			return config.sections();
		}
		if (config.templateCode() == null) {
			return List.of();
		}
		return templates.byCode(config.templateCode())
				.map(t -> json.readValue(t.sectionsJson(), SECTIONS).stream().map(Section::title).toList())
				.orElse(List.of());
	}

	int count(RunConfig config) {
		int n = titles(config).size();
		return n > 0 ? n : DEFAULT_COUNT;
	}

}
