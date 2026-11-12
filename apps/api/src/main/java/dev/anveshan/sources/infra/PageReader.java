package dev.anveshan.sources.infra;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.text.PDFTextStripper;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jsoup.select.NodeVisitor;
import org.springframework.stereotype.Component;

import dev.anveshan.shared.security.SafeHttp;
import dev.anveshan.shared.security.SafeHttp.BodyTooLargeException;
import dev.anveshan.shared.security.UnsafeUrlException;
import dev.anveshan.sources.domain.Texts;
import net.dankito.readability4j.Article;
import net.dankito.readability4j.Readability4J;

/**
 * Fetches one URL through {@link SafeHttp} and returns its readable text (TECHSPEC §12.4): HTML → Readability main
 * text (fallback: the body without nav/footer/script), PDF → PDFBox text per page (page starts kept for chunk
 * metadata), plain text as is. Fetched content is data only — nothing in it is ever executed or followed.
 */
@Component
public class PageReader {

	/** What came back: either text, or why not (plain words for the live log). */
	public sealed interface Result permits Page, Unreadable {
	}

	/**
	 * @param pageStarts PDF page offsets into {@code text} (first = 0); null for web pages
	 */
	public record Page(String title, String text, List<String> authors, LocalDate published, String contentType,
			int[] pageStarts, URI finalUrl, Map<String, Object> metadata) implements Result {
	}

	public record Unreadable(String reason) implements Result {
	}

	private static final Set<String> BLOCKS = Set.of("p", "h1", "h2", "h3", "h4", "h5", "h6", "li", "pre", "blockquote",
			"figcaption", "td", "th", "tr", "dt", "dd", "div", "section", "article", "table", "ul", "ol", "br", "hr");
	private static final String NOISE = "script, style, noscript, nav, footer, header, aside, form, iframe, svg, "
			+ "button, [role=navigation], [aria-hidden=true], .cookie, .advert, .ads";

	private final SafeHttp http;
	private final FetchProperties props;
	private final RobotsCache robots;

	PageReader(SafeHttp http, FetchProperties props, RobotsCache robots) {
		this.http = http;
		this.props = props;
		this.robots = robots;
	}

	public Result read(String url) {
		SafeHttp.Fetched f;
		try {
			// the type is known only after the response: read up to the PDF limit, HTML is re-checked below
			f = http.get(URI.create(url), robots.policy(), SafeHttp.PDF_LIMIT_BYTES);
		}
		catch (BodyTooLargeException e) {
			return new Unreadable("The page is too large to read (over 10 MB).");
		}
		catch (UnsafeUrlException e) {
			return new Unreadable("The address isn't allowed (" + e.getMessage() + ").");
		}
		catch (IOException | RuntimeException e) {
			return new Unreadable("The site didn't answer.");
		}
		if (f.status() < 200 || f.status() >= 300) {
			return new Unreadable("The page couldn't be opened (HTTP " + f.status() + ").");
		}
		String type = f.contentType() == null ? "" : f.contentType().toLowerCase(Locale.ROOT);
		byte[] body = f.body();
		if (type.contains("pdf") || startsWithPdfMagic(body)) {
			return pdf(body, f.finalUri());
		}
		if (body.length > SafeHttp.PAGE_LIMIT_BYTES) {
			return new Unreadable("The page is too large to read (over 2 MB).");
		}
		if (type.contains("html") || type.contains("xml") || type.isEmpty()) {
			return html(new String(body, charset(type)), f.finalUri());
		}
		if (type.startsWith("text/")) {
			return checked(new Page(null, Texts.clean(new String(body, charset(type))), List.of(), null, "text/plain",
					null, f.finalUri(), Map.of("contentType", "text/plain")));
		}
		return new Unreadable("This kind of file can't be read (" + type.split(";")[0] + ").");
	}

	/** A user's file (upload): PDF, Markdown or plain text — the same cleaning as fetched pages. */
	public Result readFile(byte[] bytes, String contentType, String name) {
		String type = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
		URI label = URI.create("upload:" + java.net.URLEncoder.encode(name, StandardCharsets.UTF_8));
		if (type.contains("pdf") || startsWithPdfMagic(bytes)) {
			return pdf(bytes, label);
		}
		if (type.startsWith("text/") || name.toLowerCase(Locale.ROOT).matches(".*\\.(md|markdown|txt)$")) {
			return checked(new Page(null, Texts.clean(new String(bytes, StandardCharsets.UTF_8)), List.of(), null,
					"text/plain", null, label, Map.of("contentType", "text/plain")));
		}
		return new Unreadable("Only PDF, Markdown and text files can be read.");
	}

	/** HTML → main text with paragraph breaks; title, authors and date from the page's own metadata. */
	Result html(String html, URI url) {
		Document doc = Jsoup.parse(html, url.toString());
		String text = null;
		String title = null;
		try {
			Article a = new Readability4J(url.toString(), doc.clone()).parse();
			title = a.getTitle();
			if (a.getContent() != null) {
				text = blockText(Jsoup.parse(a.getContent()).body());
			}
		}
		catch (RuntimeException e) {
			// Readability gives up on some layouts: the fallback below still works
		}
		if (text == null || text.length() < props.minTextChars()) {
			Document copy = doc.clone();
			copy.select(NOISE).remove();
			text = blockText(copy.body());
		}
		if (title == null || title.isBlank()) {
			title = firstNonBlank(meta(doc, "citation_title"), meta(doc, "og:title"), doc.title());
		}
		List<String> authors = new ArrayList<>();
		for (Element m : doc.select("meta[name=citation_author], meta[name=author]")) {
			String v = m.attr("content").strip();
			if (!v.isEmpty() && authors.size() < 20 && !authors.contains(v)) {
				authors.add(v);
			}
		}
		LocalDate published = date(firstNonBlank(meta(doc, "citation_publication_date"),
				meta(doc, "article:published_time"), meta(doc, "datePublished"), meta(doc, "date")));
		return checked(new Page(title == null ? null : title.strip(), text, authors, published, "text/html", null, url,
				Map.of("contentType", "text/html")));
	}

	/** PDF → text page by page (encrypted or broken files are unreadable); at most {@code max-pdf-pages}. */
	Result pdf(byte[] bytes, URI url) {
		try (PDDocument pdf = Loader.loadPDF(bytes)) {
			int pages = Math.min(pdf.getNumberOfPages(), props.maxPdfPages());
			PDFTextStripper stripper = new PDFTextStripper();
			StringBuilder text = new StringBuilder();
			int[] starts = new int[pages];
			int kept = 0;
			for (int p = 1; p <= pages; p++) {
				stripper.setStartPage(p);
				stripper.setEndPage(p);
				String pageText = Texts.clean(stripper.getText(pdf));
				if (!text.isEmpty()) {
					text.append("\n\n");
				}
				starts[kept++] = text.length();
				text.append(pageText);
			}
			String title = pdf.getDocumentInformation() == null ? null : pdf.getDocumentInformation().getTitle();
			Map<String, Object> meta = new LinkedHashMap<>();
			meta.put("contentType", "application/pdf");
			meta.put("pages", pdf.getNumberOfPages());
			meta.put("pagesRead", pages);
			return checked(new Page(title, text.toString(), List.of(), null, "application/pdf",
					java.util.Arrays.copyOf(starts, kept), url, meta));
		}
		catch (InvalidPasswordException e) {
			return new Unreadable("The PDF is password-protected.");
		}
		catch (IOException | RuntimeException e) {
			return new Unreadable("The PDF couldn't be read.");
		}
	}

	private Result checked(Page p) {
		if (p.text() == null || p.text().length() < props.minTextChars()) {
			return new Unreadable("The page had almost no readable text (maybe a login or paywall page).");
		}
		return p;
	}

	/** Text of an element with a paragraph break around every block element. */
	static String blockText(Element root) {
		if (root == null) {
			return "";
		}
		StringBuilder out = new StringBuilder();
		root.traverse(new NodeVisitor() {
			@Override
			public void head(Node node, int depth) {
				if (node instanceof TextNode t) {
					out.append(t.text());
				}
				else if (node instanceof Element e && BLOCKS.contains(e.normalName())) {
					out.append(e.normalName().equals("br") ? "\n" : "\n\n");
				}
			}

			@Override
			public void tail(Node node, int depth) {
				if (node instanceof Element e && BLOCKS.contains(e.normalName()) && !e.normalName().equals("br")) {
					out.append("\n\n");
				}
			}
		});
		return Texts.clean(out.toString());
	}

	private static String meta(Document doc, String name) {
		Element m = doc.selectFirst("meta[name=" + name + "], meta[property=" + name + "], meta[itemprop=" + name + "]");
		return m == null ? null : m.attr("content");
	}

	private static String firstNonBlank(String... values) {
		for (String v : values) {
			if (v != null && !v.isBlank()) {
				return v;
			}
		}
		return null;
	}

	private static LocalDate date(String s) {
		if (s == null || s.length() < 10) {
			return null;
		}
		try {
			return LocalDate.parse(s.strip().substring(0, 10));
		}
		catch (RuntimeException e) {
			return null;
		}
	}

	private static boolean startsWithPdfMagic(byte[] b) {
		return b.length > 4 && b[0] == '%' && b[1] == 'P' && b[2] == 'D' && b[3] == 'F';
	}

	private static Charset charset(String contentType) {
		int i = contentType.indexOf("charset=");
		if (i >= 0) {
			try {
				return Charset.forName(contentType.substring(i + 8).split("[;\\s]")[0].replace("\"", ""));
			}
			catch (RuntimeException e) {
				return StandardCharsets.UTF_8;
			}
		}
		return StandardCharsets.UTF_8;
	}

}
