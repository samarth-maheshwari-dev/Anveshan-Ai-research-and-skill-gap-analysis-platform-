package dev.anveshan.sources.infra;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;

import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import dev.anveshan.sources.api.SourceSearch.Candidate;
import dev.anveshan.sources.api.SourceSearch.CandidateType;
import dev.anveshan.sources.api.SourceSearch.SourceKind;
import dev.anveshan.sources.app.SearchAdapter;
import dev.anveshan.sources.infra.SearchHttp.SearchApiException;

/** arXiv preprints (Atom XML, TECHSPEC §11.1). Polite: ≤ 1 request per 3 s. XML parsed with DTDs disabled (XXE). */
@Component
class ArxivAdapter implements SearchAdapter {

	static final String NAME = "arxiv";
	private static final String ATOM = "http://www.w3.org/2005/Atom";
	private static final String ARXIV = "http://arxiv.org/schemas/atom";

	private final SearchHttp http;
	private final SearchProperties props;

	ArxivAdapter(SearchHttp http, SearchProperties props) {
		this.http = http;
		this.props = props;
	}

	@Override
	public String name() {
		return NAME;
	}

	@Override
	public String label() {
		return "arXiv";
	}

	@Override
	public Set<SourceKind> kinds() {
		return Set.of(SourceKind.ACADEMIC);
	}

	@Override
	public boolean isAvailableFor(UUID ownerId) {
		return props.api(NAME).usable();
	}

	@Override
	public List<Candidate> search(AdapterQuery q) {
		// all:word AND all:word … (at most 6 words): an exact phrase finds almost nothing
		String terms = Arrays.stream(q.text().replaceAll("[^\\p{L}\\p{N}\\s-]", " ").strip().split("\\s+"))
				.filter(w -> w.length() > 1).limit(6).map(w -> "all:" + w).collect(Collectors.joining(" AND "));
		Map<String, String> p = new LinkedHashMap<>();
		p.put("search_query", terms.isEmpty() ? "all:" + q.text() : terms);
		p.put("start", "0");
		p.put("max_results", Integer.toString(q.maxResults()));
		p.put("sortBy", "relevance");
		String xml = http.getText(AdapterSupport.uri(props.api(NAME).baseUrl(), "api/query", p), Map.of(),
				"application/atom+xml");
		return parse(xml);
	}

	static List<Candidate> parse(String xml) {
		Document doc;
		try {
			DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
			f.setNamespaceAware(true);
			f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
			f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
			f.setExpandEntityReferences(false);
			doc = f.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
		}
		catch (Exception e) {
			throw new SearchApiException("unreadable arXiv response");
		}
		List<Candidate> out = new ArrayList<>();
		NodeList entries = doc.getElementsByTagNameNS(ATOM, "entry");
		for (int i = 0; i < entries.getLength(); i++) {
			Element e = (Element) entries.item(i);
			String title = AdapterSupport.text(child(e, ATOM, "title"));
			String id = child(e, ATOM, "id");
			if (title == null || id == null) {
				continue;
			}
			String doiText = child(e, ARXIV, "doi");
			String doi = doiText == null ? null : "https://doi.org/" + doiText.strip();
			String url = id.strip().replaceFirst("^http://", "https://");
			out.add(new Candidate(title, url, doi, AdapterSupport.text(child(e, ATOM, "summary")),
					AdapterSupport.date(child(e, ATOM, "published")), CandidateType.PREPRINT, NAME, 0));
		}
		return out;
	}

	private static String child(Element e, String ns, String name) {
		NodeList n = e.getElementsByTagNameNS(ns, name);
		return n.getLength() == 0 ? null : n.item(0).getTextContent();
	}

}
