package dev.anveshan.byok.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

class ModelExcludeConfigTest {

	/** The real {@code anveshan.byok.model-ranking.exclude} list from application.yml. */
	static ModelRanking.Rules rulesFromApplicationYml() throws Exception {
		StandardEnvironment env = new StandardEnvironment();
		new YamlPropertySourceLoader().load("app", new ClassPathResource("application.yml"))
				.forEach(env.getPropertySources()::addLast);
		List<String> exclude = Binder.get(env).bind("anveshan.model-ranking.exclude", Bindable.listOf(String.class))
				.orElseGet(() -> Binder.get(env).bind("anveshan.byok.model-ranking.exclude", Bindable.listOf(String.class))
						.get());
		return ModelRanking.Rules.of(exclude, List.of(), List.of());
	}

	@Test
	@DisplayName("T-15-24 speech models are never offered for a chat slot (a real VERIFY chain had Groq's Orpheus TTS: 144 failed calls)")
	void speechModelsExcluded() throws Exception {
		ModelRanking.Rules rules = rulesFromApplicationYml();
		for (String speech : List.of("canopylabs/orpheus-arabic-saudi", "canopylabs/orpheus-v1-english", "playai-tts",
				"playai-tts-arabic", "whisper-large-v3-turbo", "gemini-2.5-flash-preview-tts")) {
			assertThat(ModelRanking.isExcluded(speech, rules)).as(speech).isTrue();
		}
		for (String chat : List.of("openai/gpt-oss-120b", "qwen/qwen3-32b", "llama-3.3-70b-versatile", "gemini-2.5-flash",
				"allam-2-7b", "moonshotai/kimi-k2-instruct")) {
			assertThat(ModelRanking.isExcluded(chat, rules)).as(chat).isFalse();
		}
	}

}
