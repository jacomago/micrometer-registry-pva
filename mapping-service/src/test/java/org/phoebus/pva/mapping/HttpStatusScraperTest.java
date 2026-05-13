package org.phoebus.pva.mapping;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.phoebus.pva.mapping.scraper.HttpStatusScraper;
import org.phoebus.pva.mapping.scraper.ScraperResult;

import java.net.http.HttpClient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class HttpStatusScraperTest {

    private MockWebServer server;
    private HttpStatusScraper scraper;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        scraper = new HttpStatusScraper(HttpClient.newHttpClient());
    }

    @AfterEach
    void tearDown() throws Exception {
        server.shutdown();
    }

    @Test
    void status200_returnsValue1() {
        server.enqueue(new MockResponse().setResponseCode(200));
        ScraperResult result = scraper.scrape(server.url("/health").toString());
        assertInstanceOf(ScraperResult.Value.class, result);
        assertEquals(1.0, ((ScraperResult.Value) result).value(), 1e-9);
    }

    @Test
    void status201_returnsValue1() {
        server.enqueue(new MockResponse().setResponseCode(201));
        ScraperResult result = scraper.scrape(server.url("/health").toString());
        assertInstanceOf(ScraperResult.Value.class, result);
        assertEquals(1.0, ((ScraperResult.Value) result).value(), 1e-9);
    }

    @Test
    void status503_returnsUnavailableMajor() {
        server.enqueue(new MockResponse().setResponseCode(503));
        ScraperResult result = scraper.scrape(server.url("/health").toString());
        assertInstanceOf(ScraperResult.Unavailable.class, result);
        var unavailable = (ScraperResult.Unavailable) result;
        assertEquals(ScraperResult.Unavailable.Severity.MAJOR, unavailable.severity());
        assertEquals("HTTP 503", unavailable.reason());
    }

    @Test
    void status404_returnsUnavailableMajor() {
        server.enqueue(new MockResponse().setResponseCode(404));
        ScraperResult result = scraper.scrape(server.url("/missing").toString());
        assertInstanceOf(ScraperResult.Unavailable.class, result);
        assertEquals(ScraperResult.Unavailable.Severity.MAJOR,
                ((ScraperResult.Unavailable) result).severity());
    }

    @Test
    void unreachableHost_returnsUnavailableMajor() {
        // Port 1 is not listening on any reasonable system
        ScraperResult result = scraper.scrape("http://127.0.0.1:1/health");
        assertInstanceOf(ScraperResult.Unavailable.class, result);
        assertEquals(ScraperResult.Unavailable.Severity.MAJOR,
                ((ScraperResult.Unavailable) result).severity());
    }
}
