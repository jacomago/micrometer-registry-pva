package org.phoebus.pva.mapping;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.phoebus.pva.mapping.scraper.PrometheusScraper;
import org.phoebus.pva.mapping.scraper.ScraperResult;

import java.net.http.HttpClient;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class PrometheusScraperTest {

    private MockWebServer server;
    private HttpClient httpClient;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        httpClient = HttpClient.newHttpClient();
    }

    @AfterEach
    void tearDown() throws Exception {
        server.shutdown();
    }

    private static final String SAMPLE_BODY = """
            # HELP jvm_memory_used_bytes Memory used
            # TYPE jvm_memory_used_bytes gauge
            jvm_memory_used_bytes{area="heap",id="G1 Old Gen"} 12345678.0
            jvm_memory_used_bytes{area="nonheap",id="Metaspace"} 55000.0
            # TYPE uptime_seconds gauge
            uptime_seconds 3600.0
            """;

    @Test
    void metricFoundNoLabels_returnsValue() {
        server.enqueue(new MockResponse().setBody(SAMPLE_BODY));
        var scraper = new PrometheusScraper("uptime_seconds", null, httpClient);
        ScraperResult result = scraper.scrape(server.url("/prometheus").toString());
        assertInstanceOf(ScraperResult.Value.class, result);
        assertEquals(3600.0, ((ScraperResult.Value) result).value(), 1e-9);
    }

    @Test
    void metricFoundWithLabelFilter_returnsMatchingSample() {
        server.enqueue(new MockResponse().setBody(SAMPLE_BODY));
        var scraper = new PrometheusScraper("jvm_memory_used_bytes",
                Map.of("area", "nonheap"), httpClient);
        ScraperResult result = scraper.scrape(server.url("/prometheus").toString());
        assertInstanceOf(ScraperResult.Value.class, result);
        assertEquals(55000.0, ((ScraperResult.Value) result).value(), 1e-9);
    }

    @Test
    void metricAbsent_returnsUnavailableMinor() {
        server.enqueue(new MockResponse().setBody("other_metric 1.0"));
        var scraper = new PrometheusScraper("missing_metric", null, httpClient);
        ScraperResult result = scraper.scrape(server.url("/prometheus").toString());
        assertInstanceOf(ScraperResult.Unavailable.class, result);
        assertEquals(ScraperResult.Unavailable.Severity.MINOR,
                ((ScraperResult.Unavailable) result).severity());
    }

    @Test
    void labelMismatch_returnsUnavailableMinor() {
        server.enqueue(new MockResponse().setBody(SAMPLE_BODY));
        var scraper = new PrometheusScraper("jvm_memory_used_bytes",
                Map.of("area", "nonexistent"), httpClient);
        ScraperResult result = scraper.scrape(server.url("/prometheus").toString());
        assertInstanceOf(ScraperResult.Unavailable.class, result);
        assertEquals(ScraperResult.Unavailable.Severity.MINOR,
                ((ScraperResult.Unavailable) result).severity());
    }

    @Test
    void httpError_returnsUnavailableMajor() {
        server.enqueue(new MockResponse().setResponseCode(500));
        var scraper = new PrometheusScraper("any_metric", null, httpClient);
        ScraperResult result = scraper.scrape(server.url("/prometheus").toString());
        assertInstanceOf(ScraperResult.Unavailable.class, result);
        assertEquals(ScraperResult.Unavailable.Severity.MAJOR,
                ((ScraperResult.Unavailable) result).severity());
    }

    @Test
    void unreachableHost_returnsUnavailableMajor() {
        var scraper = new PrometheusScraper("any_metric", null, httpClient);
        ScraperResult result = scraper.scrape("http://127.0.0.1:1/prometheus");
        assertInstanceOf(ScraperResult.Unavailable.class, result);
        assertEquals(ScraperResult.Unavailable.Severity.MAJOR,
                ((ScraperResult.Unavailable) result).severity());
    }
}
