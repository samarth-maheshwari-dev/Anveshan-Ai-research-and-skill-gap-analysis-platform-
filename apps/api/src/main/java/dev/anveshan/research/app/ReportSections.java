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

	/** A section the writer fills (one WRITE step each). */
	record Spec(String key, String title, String instructions) {
	}

	/** The standard report when the user picked neither a template nor own sections. */
	static final List<Spec> DEFAULTS = List.of(
			new Spec("introduction", "Introduction", "What the question is, why it matters and what the report covers."),
			new Spec("background", "Background", "Key ideas and terms a reader needs first."),
			new Spec("key_findings", "Key findings", "The most important verified facts that answer the question."),
			new Spec("discussion", "Discussion", "How the findings compare, what they mean, and their limits."),
			new Spec("conclusion", "Conclusion", "A short answer to the question, and what to do next."));

	/** Sections in order: the user's own (no instructions), else the template's, else {@link #DEFAULTS}. */
	List<Spec> specs(RunConfig config) {
		if (config.sections() != null && !config.sections().isEmpty()) {
			List<Spec> out = new java.util.ArrayList<>();
			for (String title : config.sections()) {
				out.add(new Spec(slug(title, out.size()), title, ""));
			}
			return out;
		}
		if (config.templateCode() != null) {
			List<Spec> fromTemplate = templates.byCode(config.templateCode())
					.map(t -> json.readValue(t.sectionsJson(), SECTIONS).stream()
							.map(s -> new Spec(s.key(), s.title(), s.instructions() == null ? "" : s.instructions()))
							.toList())
					.orElse(List.of());
			if (!fromTemplate.isEmpty()) {
				return fromTemplate;
			}
		}
		return DEFAULTS;
	}

	/** The template's tone/style guidance for the writer ("Academic tone. Cite every factual sentence …"). */
	String writerInstructions(RunConfig config) {
		if (config.templateCode() == null) {
			return "";
		}
		return templates.byCode(config.templateCode()).map(t -> t.writerInstructions() == null ? "" : t.writerInstructions())
				.orElse("");
	}

	private static String slug(String title, int index) {
		String s = title.toLowerCase(java.util.Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", "_").replaceAll("^_|_$", "");
		return (s.isEmpty() ? "section" : s.substring(0, Math.min(40, s.length()))) + "_" + (index + 1);
	}

}
