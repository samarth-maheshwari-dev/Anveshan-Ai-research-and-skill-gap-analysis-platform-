package dev.anveshan.shared.logging;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Replaces anything that looks like a credential with its prefix + {@code ****} (TECHSPEC §6.3).
 * Pure function; used by the Logback converters for messages and stack traces.
 */
public final class SecretMasker {

	private record Rule(Pattern pattern, String replacement) {
	}

	private static final List<Rule> RULES = List.of(
			new Rule(Pattern.compile("(?i)\\b(Bearer|Basic)\\s+[A-Za-z0-9._~+/=-]{4,}"), "$1 ****"),
			new Rule(Pattern.compile("\\banv_pat_[A-Za-z0-9]{4,}"), "anv_pat_****"),
			new Rule(Pattern.compile("\\b(sk-(?:ant-|proj-|or-v1-)?)[A-Za-z0-9_-]{8,}"), "$1****"),
			new Rule(Pattern.compile("\\bgsk_[A-Za-z0-9]{8,}"), "gsk_****"),
			new Rule(Pattern.compile("\\bAIza[0-9A-Za-z_-]{20,}"), "AIza****"),
			new Rule(Pattern.compile("\\btvly-[A-Za-z0-9_-]{8,}"), "tvly-****"),
			new Rule(Pattern.compile("\\b(gh[pousr]_|github_pat_)[A-Za-z0-9_]{8,}"), "$1****"),
			new Rule(Pattern.compile("\\bGOCSPX-[A-Za-z0-9_-]{8,}"), "GOCSPX-****"),
			new Rule(Pattern.compile("(?i)(\"?(?:api[_-]?key|client[_-]?secret|password|secret|token)\"?\\s*[:=]\\s*\"?)[^\\s\",&}]{4,}"),
					"$1****"));

	private SecretMasker() {
	}

	public static String mask(String text) {
		if (text == null || text.isEmpty()) {
			return text;
		}
		String out = text;
		for (Rule rule : RULES) {
			Matcher m = rule.pattern().matcher(out);
			if (m.find()) {
				out = m.replaceAll(rule.replacement());
			}
		}
		return out;
	}

}
