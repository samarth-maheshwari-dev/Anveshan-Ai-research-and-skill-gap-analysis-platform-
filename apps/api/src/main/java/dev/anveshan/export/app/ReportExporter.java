package dev.anveshan.export.app;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;
import org.springframework.stereotype.Service;

import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;

import dev.anveshan.report.api.Reports;
import dev.anveshan.report.api.Reports.ExportDoc;
import dev.anveshan.report.api.Reports.ExportSection;
import dev.anveshan.shared.web.ApiException;

/**
 * Report export (P16b, F-FR-8): Markdown, or PDF rendered from that Markdown via OpenHTMLtoPDF. The report text came
 * from an LLM, so raw HTML in it is escaped, never rendered. Built synchronously on request (D-113).
 */
@Service
public class ReportExporter {

	public enum Format {
		MD, PDF
	}

	/** The file to download. */
	public record ExportFile(String filename, String contentType, byte[] body) {
	}

	static final String DISCLAIMER = "AI-assisted research draft. Verify before submitting it anywhere.";

	/** Unicode fonts on the server (Docker image: DejaVu + Noto incl. Devanagari); missing files are skipped. */
	private static final List<String[]> FONTS = List.of(
			new String[] { "DejaVu Sans", "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf" },
			new String[] { "Noto Sans Devanagari", "/usr/share/fonts/truetype/noto/NotoSansDevanagari-Regular.ttf" },
			new String[] { "DejaVu Sans", "C:/Windows/Fonts/arial.ttf" });

	private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("MMMM d, yyyy", Locale.ENGLISH)
			.withZone(ZoneOffset.UTC);

	private final Reports reports;
	private final Parser parser = Parser.builder().build();
	private final HtmlRenderer html = HtmlRenderer.builder().escapeHtml(true).sanitizeUrls(true).build();

	ReportExporter(Reports reports) {
		this.reports = reports;
	}

	public ExportFile export(UUID ownerId, UUID reportId, Format format) {
		ExportDoc doc = reports.exportDoc(ownerId, reportId).orElseThrow(ApiException::notFound);
		String base = filename(doc.title());
		return switch (format) {
			case MD -> new ExportFile(base + ".md", "text/markdown; charset=utf-8",
					markdown(doc).getBytes(StandardCharsets.UTF_8));
			case PDF -> new ExportFile(base + ".pdf", "application/pdf", pdf(doc));
		};
	}

	/** The whole report as one Markdown file: title, date line, sections, disclaimer. */
	static String markdown(ExportDoc doc) {
		StringBuilder out = new StringBuilder();
		out.append("# ").append(oneLine(doc.title())).append("\n\n");
		out.append('*').append(DATE.format(doc.createdAt()));
		if (doc.citationStyle() != null && !doc.citationStyle().isBlank()) {
			out.append(" · ").append(doc.citationStyle().toUpperCase(Locale.ROOT)).append(" references");
		}
		out.append("*\n\n");
		for (ExportSection s : doc.sections()) {
			out.append("## ").append(oneLine(s.title())).append("\n\n");
			if (!s.markdown().isBlank()) {
				out.append(s.markdown().strip()).append("\n\n");
			}
		}
		out.append("---\n\n*").append(DISCLAIMER).append("*\n");
		return out.toString();
	}

	byte[] pdf(ExportDoc doc) {
		String body = html.render(parser.parse(markdown(doc)));
		String page = """
				<!DOCTYPE html>
				<html lang="%s"><head><meta charset="utf-8"/><title>%s</title><style>
				@page { size: A4; margin: 22mm 20mm; @bottom-center { content: "%s  -  " counter(page) " / " counter(pages); font-family: 'DejaVu Sans', sans-serif; font-size: 8pt; color: #666; } }
				body { font-family: 'DejaVu Sans', 'Noto Sans Devanagari', sans-serif; font-size: 10.5pt; line-height: 1.5; color: #111; }
				h1 { font-size: 20pt; line-height: 1.2; margin: 0 0 6pt; }
				h2 { font-size: 13.5pt; margin: 18pt 0 6pt; padding-bottom: 3pt; border-bottom: 0.5pt solid #ccc; }
				h3 { font-size: 11.5pt; }
				p, li { margin: 0 0 6pt; }
				code { font-family: monospace; font-size: 9.5pt; }
				hr { border: 0; border-top: 0.5pt solid #ccc; margin: 16pt 0 8pt; }
				em { color: #444; }
				</style></head><body>%s</body></html>
				""".formatted("hi".equals(doc.language()) ? "hi" : "en", escape(oneLine(doc.title())), DISCLAIMER, body);
		try (var out = new ByteArrayOutputStream()) {
			PdfRendererBuilder b = new PdfRendererBuilder();
			b.useFastMode();
			for (String[] f : FONTS) {
				File file = new File(f[1]);
				if (file.isFile()) {
					b.useFont(file, f[0]);
				}
			}
			b.withHtmlContent(toXhtml(page), null);
			b.toStream(out);
			b.run();
			return out.toByteArray();
		}
		catch (IOException e) {
			throw new UncheckedIOException("PDF export failed", e);
		}
	}

	/** commonmark writes HTML void tags ({@code <br>}, {@code <hr>}); the PDF renderer needs XHTML. */
	static String toXhtml(String html) {
		return html.replaceAll("<(br|hr|img)([^>]*?)\\s*/?>", "<$1$2 />");
	}

	/** A safe ASCII file name from the title (at most 80 chars). */
	static String filename(String title) {
		String s = title == null ? "" : title.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-")
				.replaceAll("(^-+|-+$)", "");
		if (s.length() > 80) {
			s = s.substring(0, 80).replaceAll("-+$", "");
		}
		return s.isEmpty() ? "anveshan-report" : s;
	}

	private static String oneLine(String s) {
		return s == null ? "" : s.replaceAll("\\s+", " ").strip();
	}

	private static String escape(String s) {
		return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
	}

}
