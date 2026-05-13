package org.phoebus.pva.mapping;

import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.phoebus.pva.mapping.scraper.JsonScraper;
import org.phoebus.pva.mapping.scraper.ScraperResult;

import java.net.http.HttpClient;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonScraperTest {

    private MockWebServer server;
    private HttpClient httpClient;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        httpClient = HttpClient.newHttpClient();
        objectMapper = new ObjectMapper();
    }

    @AfterEach
    void tearDown() throws Exception {
        server.shutdown();
    }

    @Test
    void numericNode_usedDirectly() {
        server.enqueue(new MockResponse().setBody("{\"count\": 42}"));
        var scraper = new JsonScraper("count", null, httpClient, objectMapper);
        ScraperResult result = scraper.scrape(server.url("/info").toString());
        assertInstanceOf(ScraperResult.Value.class, result);
        assertEquals(42.0, ((ScraperResult.Value) result).value(), 1e-9);
    }

    @Test
    void numericDouble_usedDirectly() {
        server.enqueue(new MockResponse().setBody("{\"ratio\": 0.75}"));
        var scraper = new JsonScraper("ratio", null, httpClient, objectMapper);
        ScraperResult result = scraper.scrape(server.url("/info").toString());
        assertInstanceOf(ScraperResult.Value.class, result);
        assertEquals(0.75, ((ScraperResult.Value) result).value(), 1e-9);
    }

    @Test
    void booleanTrue_returns1() {
        server.enqueue(new MockResponse().setBody("{\"enabled\": true}"));
        var scraper = new JsonScraper("enabled", null, httpClient, objectMapper);
        ScraperResult result = scraper.scrape(server.url("/info").toString());
        assertInstanceOf(ScraperResult.Value.class, result);
        assertEquals(1.0, ((ScraperResult.Value) result).value(), 1e-9);
    }

    @Test
    void booleanFalse_returns0() {
        server.enqueue(new MockResponse().setBody("{\"enabled\": false}"));
        var scraper = new JsonScraper("enabled", null, httpClient, objectMapper);
        ScraperResult result = scraper.scrape(server.url("/info").toString());
        assertInstanceOf(ScraperResult.Value.class, result);
        assertEquals(0.0, ((ScraperResult.Value) result).value(), 1e-9);
    }

    @Test
    void stringWithValueMap_returnsMappedDouble() {
        server.enqueue(new MockResponse().setBody("{\"status\": \"UP\"}"));
        var scraper = new JsonScraper("status",
                Map.of("UP", 1.0, "DOWN", 0.0), httpClient, objectMapper);
        ScraperResult result = scraper.scrape(server.url("/health").toString());
        assertInstanceOf(ScraperResult.Value.class, result);
        assertEquals(1.0, ((ScraperResult.Value) result).value(), 1e-9);
    }

    @Test
    void stringNotInValueMap_returnsNaN() {
        server.enqueue(new MockResponse().setBody("{\"status\": \"UNKNOWN\"}"));
        var scraper = new JsonScraper("status",
                Map.of("UP", 1.0, "DOWN", 0.0), httpClient, objectMapper);
        ScraperResult result = scraper.scrape(server.url("/health").toString());
        assertInstanceOf(ScraperResult.Value.class, result);
        assertTrue(Double.isNaN(((ScraperResult.Value) result).value()));
    }

    @Test
    void nestedDotPath_navigatesCorrectly() {
        server.enqueue(new MockResponse()
                .setBody("{\"components\":{\"db\":{\"status\":\"UP\"}}}"));
        var scraper = new JsonScraper("components.db.status",
                Map.of("UP", 1.0, "DOWN", 0.0), httpClient, objectMapper);
        ScraperResult result = scraper.scrape(server.url("/health").toString());
        assertInstanceOf(ScraperResult.Value.class, result);
        assertEquals(1.0, ((ScraperResult.Value) result).value(), 1e-9);
    }

    @Test
    void missingPath_returnsUnavailableMinor() {
        server.enqueue(new MockResponse().setBody("{\"other\": 1}"));
        var scraper = new JsonScraper("status", null, httpClient, objectMapper);
        ScraperResult result = scraper.scrape(server.url("/health").toString());
        assertInstanceOf(ScraperResult.Unavailable.class, result);
        assertEquals(ScraperResult.Unavailable.Severity.MINOR,
                ((ScraperResult.Unavailable) result).severity());
    }

    @Test
    void missingIntermediateKey_returnsUnavailableMinor() {
        server.enqueue(new MockResponse().setBody("{\"a\": {}}"));
        var scraper = new JsonScraper("a.b.c", null, httpClient, objectMapper);
        ScraperResult result = scraper.scrape(server.url("/info").toString());
        assertInstanceOf(ScraperResult.Unavailable.class, result);
        assertEquals(ScraperResult.Unavailable.Severity.MINOR,
                ((ScraperResult.Unavailable) result).severity());
    }

    @Test
    void httpError_returnsUnavailableMajor() {
        server.enqueue(new MockResponse().setResponseCode(503));
        var scraper = new JsonScraper("status", null, httpClient, objectMapper);
        ScraperResult result = scraper.scrape(server.url("/health").toString());
        assertInstanceOf(ScraperResult.Unavailable.class, result);
        assertEquals(ScraperResult.Unavailable.Severity.MAJOR,
                ((ScraperResult.Unavailable) result).severity());
    }

    @Test
    void unreachableHost_returnsUnavailableMajor() {
        var scraper = new JsonScraper("status", null, httpClient, objectMapper);
        ScraperResult result = scraper.scrape("http://127.0.0.1:1/health");
        assertInstanceOf(ScraperResult.Unavailable.class, result);
        assertEquals(ScraperResult.Unavailable.Severity.MAJOR,
                ((ScraperResult.Unavailable) result).severity());
    }
}
