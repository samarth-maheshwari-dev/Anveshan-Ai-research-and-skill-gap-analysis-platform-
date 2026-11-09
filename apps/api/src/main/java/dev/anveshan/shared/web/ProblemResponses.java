package dev.anveshan.shared.web;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * RFC 9457 Problem Details (TECHSPEC §8.4) for places that run before Spring MVC
 * (security entry points, filters) and for controllers.
 */
public final class ProblemResponses {

	public static final String MEDIA_TYPE = "application/problem+json";

	private ProblemResponses() {
	}

	public static ProblemDetail of(HttpStatus status, String code, String title, String detail) {
		ProblemDetail p = ProblemDetail.forStatusAndDetail(status, detail);
		p.setType(java.net.URI.create("urn:anveshan:problem:" + code.toLowerCase().replace('_', '-')));
		p.setTitle(title);
		p.setProperty("code", code);
		return p;
	}

	/** Writes a minimal problem body directly to the servlet response (no Jackson needed). */
	public static void write(HttpServletRequest req, HttpServletResponse res, HttpStatus status, String code,
			String title, String detail) throws IOException {
		res.setStatus(status.value());
		res.setContentType(MEDIA_TYPE);
		res.setCharacterEncoding(StandardCharsets.UTF_8.name());
		String json = "{\"type\":\"urn:anveshan:problem:" + code.toLowerCase().replace('_', '-') + "\","
				+ "\"title\":" + quote(title) + ","
				+ "\"status\":" + status.value() + ","
				+ "\"detail\":" + quote(detail) + ","
				+ "\"instance\":" + quote(req.getRequestURI()) + ","
				+ "\"code\":" + quote(code) + "}";
		res.getWriter().write(json);
	}

	static String quote(String s) {
		if (s == null) {
			return "null";
		}
		StringBuilder sb = new StringBuilder(s.length() + 2).append('"');
		for (char c : s.toCharArray()) {
			switch (c) {
				case '"' -> sb.append("\\\"");
				case '\\' -> sb.append("\\\\");
				case '\n' -> sb.append("\\n");
				case '\r' -> sb.append("\\r");
				case '\t' -> sb.append("\\t");
				case '<' -> sb.append("\\u003c");
				case '>' -> sb.append("\\u003e");
				default -> {
					if (c < 0x20) {
						sb.append(String.format("\\u%04x", (int) c));
					}
					else {
						sb.append(c);
					}
				}
			}
		}
		return sb.append('"').toString();
	}

}
