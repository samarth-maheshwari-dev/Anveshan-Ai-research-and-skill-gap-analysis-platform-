package dev.anveshan.research.infra;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import dev.anveshan.byok.api.Prompt;

/**
 * Loads versioned prompt files {@code prompts/<name>/v<N>.st} (TECHSPEC §10.5). A file has a {@code === system ===}
 * and a {@code === user ===} part with {@code {{placeholders}}}. A released version is never edited — a change is a
 * new file. Values are inserted as plain text; callers wrap user-written text in tags the prompt calls data.
 */
@Component
public class PromptFiles {

	private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{([a-zA-Z]+)}}");

	private record Parts(String system, String user) {
	}

	private final Map<String, Parts> cache = new ConcurrentHashMap<>();

	public Prompt load(String name, int version, Map<String, String> values, double temperature, int maxTokens) {
		Parts parts = cache.computeIfAbsent(name + "/v" + version, PromptFiles::read);
		return new Prompt(name, version, fill(parts.system(), values), fill(parts.user(), values), temperature,
				maxTokens);
	}

	private static Parts read(String path) {
		String resource = "prompts/" + path + ".st";
		try (InputStream in = PromptFiles.class.getClassLoader().getResourceAsStream(resource)) {
			if (in == null) {
				throw new IllegalStateException("Missing prompt file " + resource);
			}
			String text = new String(in.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
			int s = text.indexOf("=== system ===\n");
			int u = text.indexOf("=== user ===\n");
			if (s < 0 || u < s) {
				throw new IllegalStateException("Prompt " + resource + " needs '=== system ===' then '=== user ==='");
			}
			return new Parts(text.substring(s + 15, u).strip(), text.substring(u + 13).strip());
		}
		catch (IOException e) {
			throw new IllegalStateException("Can't read prompt " + resource, e);
		}
	}

	/** Every placeholder must have a value — a typo fails loudly instead of sending "{{topik}}" to a model. */
	static String fill(String template, Map<String, String> values) {
		Matcher m = PLACEHOLDER.matcher(template);
		StringBuilder out = new StringBuilder();
		while (m.find()) {
			String value = values.get(m.group(1));
			if (value == null) {
				throw new IllegalArgumentException("No value for prompt placeholder {{" + m.group(1) + "}}");
			}
			m.appendReplacement(out, Matcher.quoteReplacement(value));
		}
		m.appendTail(out);
		return out.toString();
	}

}
