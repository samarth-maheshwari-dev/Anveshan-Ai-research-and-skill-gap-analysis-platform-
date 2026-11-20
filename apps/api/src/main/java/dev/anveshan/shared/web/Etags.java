package dev.anveshan.shared.web;

/** {@code ETag} / {@code If-Match} for editable resources (TECHSPEC §8.1): the value is the row's version. */
public final class Etags {

	private Etags() {
	}

	public static String of(int version) {
		return "\"" + version + "\"";
	}

	/** {@code "3"} or {@code W/"3"} → 3; missing or unreadable → null (the service answers 400). */
	public static Integer version(String ifMatch) {
		if (ifMatch == null) {
			return null;
		}
		String v = ifMatch.strip();
		if (v.startsWith("W/")) {
			v = v.substring(2);
		}
		v = v.replace("\"", "");
		try {
			return Integer.valueOf(v);
		}
		catch (NumberFormatException e) {
			return null;
		}
	}

}
