package dev.anveshan.sources;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.test.context.DynamicPropertyRegistry;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * Stand-in for every search API (JDK HttpServer, like byok's FakeProvider). Each API has a path prefix and answers
 * with a response in its real shape (recorded from the public APIs, trimmed). {@link #fail} makes one API return an
 * HTTP status instead. Every request is recorded.
 */
public final class FakeSearchApis {

	public record Request(String api, String method, URI uri, Map<String, List<String>> headers, String body) {
	}

	public static final List<String> APIS = List.of("openalex", "semanticscholar", "arxiv", "crossref", "wikipedia",
			"github", "searxng", "tavily");

	private final HttpServer server;
	private final Map<String, Integer> failures = new ConcurrentHashMap<>();
	private final Map<String, String> bodies = new ConcurrentHashMap<>();
	public final List<Request> requests = Collections.synchronizedList(new ArrayList<>());

	public FakeSearchApis() {
		try {
			server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		}
		catch (IOException e) {
			throw new IllegalStateException(e);
		}
		server.createContext("/", this::handle);
		server.start();
	}

	public String baseUrl(String api) {
		return "http://localhost:" + server.getAddress().getPort() + "/" + api + "/";
	}

	/** Points every search API (and the Tavily preset) at this server; loopback allowed for the guarded client. */
	public void register(DynamicPropertyRegistry r) {
		r.add("anveshan.outbound.allow-loopback", () -> "true");
		for (String api : APIS) {
			if (!api.equals("tavily")) {
				r.add("anveshan.search.apis." + api + ".base-url", () -> baseUrl(api));
			}
		}
		r.add("anveshan.providers.TAVILY.base-url", () -> baseUrl("tavily"));
		r.add("anveshan.search.retry-wait", () -> "50ms");
		r.add("anveshan.search.cache-ttl", () -> "1ms"); // every test sees the API's current behaviour
	}

	/** {@code api} answers every request with {@code status} until {@link #reset}. */
	public void fail(String api, int status) {
		failures.put(api, status);
	}

	/** {@code api} answers with {@code json} instead of its recorded response, until {@link #reset}. */
	public void respond(String api, String json) {
		bodies.put(api, json);
	}

	public void reset() {
		failures.clear();
		bodies.clear();
		requests.clear();
	}

	public List<Request> requestsTo(String api) {
		synchronized (requests) {
			return requests.stream().filter(r -> r.api().equals(api)).toList();
		}
	}

	private void handle(HttpExchange ex) throws IOException {
		String path = ex.getRequestURI().getPath();
		String api = path.split("/").length > 1 ? path.split("/")[1] : "";
		String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
		requests.add(new Request(api, ex.getRequestMethod(), ex.getRequestURI(), Map.copyOf(ex.getRequestHeaders()), body));
		Integer status = failures.get(api);
		if (status != null) {
			send(ex, status, "application/json", "{\"error\":\"down\"}");
			return;
		}
		String custom = bodies.get(api);
		if (custom != null) {
			send(ex, 200, "application/json", custom);
			return;
		}
		switch (api) {
			case "openalex" -> send(ex, 200, "application/json", OPENALEX);
			case "semanticscholar" -> send(ex, 200, "application/json", SEMANTIC_SCHOLAR);
			case "arxiv" -> send(ex, 200, "application/atom+xml", ARXIV);
			case "crossref" -> send(ex, 200, "application/json", CROSSREF);
			case "wikipedia" -> send(ex, 200, "application/json", WIKIPEDIA);
			case "github" -> send(ex, 200, "application/json", GITHUB);
			case "searxng" -> send(ex, 200, "application/json", SEARXNG);
			case "tavily" -> send(ex, 200, "application/json", TAVILY);
			default -> send(ex, 404, "application/json", "{}");
		}
	}

	private static void send(HttpExchange ex, int status, String type, String body) throws IOException {
		byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
		ex.getResponseHeaders().set("Content-Type", type);
		ex.sendResponseHeaders(status, bytes.length);
		try (OutputStream out = ex.getResponseBody()) {
			out.write(bytes);
		}
	}

	// --- responses in each API's real shape (trimmed) ---

	public static final String OPENALEX = """
			{"meta":{"count":2},"results":[
			 {"id":"https://openalex.org/W1","doi":"https://doi.org/10.1000/LOSTFOUND.2023",
			  "display_name":"Lost and found management systems for university campuses","publication_date":"2023-04-02",
			  "type":"article","primary_location":{"landing_page_url":"https://journal.example/lf"},
			  "open_access":{"oa_url":"https://journal.example/lf.pdf"},
			  "abstract_inverted_index":{"We":[0],"compare":[1],"campus":[2],"lost":[3],"and":[4],"found":[5],"apps.":[6]}},
			 {"id":"https://openalex.org/W2","doi":null,"display_name":"Mobile apps for student services",
			  "publication_date":"2019-01-10","type":"preprint","primary_location":{"landing_page_url":"https://repo.example/w2"},
			  "open_access":{"oa_url":null},"abstract_inverted_index":null}]}""";

	public static final String SEMANTIC_SCHOLAR = """
			{"total":2,"offset":0,"data":[
			 {"paperId":"p1","title":"Lost and Found Management Systems for University Campuses","url":"https://www.semanticscholar.org/paper/p1",
			  "abstract":"A long abstract.","year":2023,"publicationDate":"2023-04-02","externalIds":{"DOI":"10.1000/lostfound.2023"},
			  "tldr":{"model":"tldr@v2","text":"Campus lost-and-found apps compared."}},
			 {"paperId":"p2","title":"Item recovery with QR codes","url":"https://www.semanticscholar.org/paper/p2",
			  "abstract":null,"year":2022,"publicationDate":null,"externalIds":{"ArXiv":"2201.00001"},"tldr":null}]}""";

	public static final String ARXIV = """
			<?xml version="1.0" encoding="UTF-8"?>
			<feed xmlns="http://www.w3.org/2005/Atom" xmlns:arxiv="http://arxiv.org/schemas/atom">
			  <title>ArXiv Query</title>
			  <entry>
			    <id>http://arxiv.org/abs/2201.00001v1</id>
			    <published>2022-01-01T10:00:00Z</published>
			    <title>Item recovery with
			      QR codes</title>
			    <summary>We study QR-code based item recovery on campuses.</summary>
			  </entry>
			  <entry>
			    <id>http://arxiv.org/abs/2305.12345v2</id>
			    <published>2023-05-20T10:00:00Z</published>
			    <title>Crowdsourced lost item search</title>
			    <summary>Crowdsourcing helps find lost items.</summary>
			    <arxiv:doi>10.2000/crowd.2023</arxiv:doi>
			  </entry>
			</feed>""";

	public static final String CROSSREF = """
			{"status":"ok","message-type":"work-list","message":{"total-results":2,"items":[
			 {"DOI":"10.1000/lostfound.2023","title":["Lost and found management systems for university campuses"],
			  "URL":"https://doi.org/10.1000/lostfound.2023","abstract":"<jats:p>We compare <jats:italic>campus</jats:italic> apps.</jats:p>",
			  "issued":{"date-parts":[[2023,4]]},"type":"journal-article"},
			 {"DOI":"10.3000/post.1","title":["A preprint on lost items"],"URL":"https://doi.org/10.3000/post.1",
			  "issued":{"date-parts":[[2024]]},"type":"posted-content"}]}}""";

	public static final String WIKIPEDIA = """
			{"batchcomplete":"","query":{"searchinfo":{"totalhits":2},"search":[
			 {"ns":0,"title":"Lost and found","pageid":1,"snippet":"A <span class=\\"searchmatch\\">lost</span> and found is a place"},
			 {"ns":0,"title":"Campus card","pageid":2,"snippet":"A campus card is an identification card"}]}}""";

	public static final String GITHUB = """
			{"total_count":1,"incomplete_results":false,"items":[
			 {"full_name":"college/lost-and-found","html_url":"https://github.com/college/lost-and-found",
			  "description":"Open-source lost and found app for colleges","stargazers_count":42,"pushed_at":"2025-02-03T08:00:00Z"}]}""";

	public static final String SEARXNG = """
			{"query":"lost and found","number_of_results":2,"results":[
			 {"url":"https://blog.example/lost-found-apps?utm_source=x","title":"5 lost and found apps for campuses",
			  "content":"We tried five apps.","publishedDate":"2025-06-01T00:00:00"},
			 {"url":"https://www.quora.com/q/lost","title":"Which lost and found app is best?","content":"Opinions."}]}""";

	public static final String TAVILY = """
			{"query":"lost and found","results":[
			 {"title":"Campus lost and found guide","url":"https://university.example/lost-found","content":"How the office works.",
			  "score":0.91},
			 {"title":"Lost and found software comparison","url":"https://review.example/compare","content":"A comparison.",
			  "score":0.83,"published_date":"2025-03-01"}],"response_time":0.8}""";

}
