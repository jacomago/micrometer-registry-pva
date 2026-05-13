package org.phoebus.pva.mapping.scraper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/**
 * Checks whether an endpoint is reachable by issuing a HEAD request.
 *
 * <p>Any 2xx response → {@link ScraperResult.Value Value(1.0)}.
 * Any non-2xx response or network failure → {@link ScraperResult.Unavailable} with
 * {@link ScraperResult.Unavailable.Severity#MAJOR MAJOR} alarm.
 */
public class HttpStatusScraper implements Scraper {

    private final HttpClient httpClient;

    public HttpStatusScraper(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    @Override
    public ScraperResult scrape(String url) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .method("HEAD", HttpRequest.BodyPublishers.noBody())
                    .build();
            int status = httpClient.send(request, HttpResponse.BodyHandlers.discarding())
                    .statusCode();
            return (status >= 200 && status < 300)
                    ? new ScraperResult.Value(1.0)
                    : new ScraperResult.Unavailable("HTTP " + status,
                            ScraperResult.Unavailable.Severity.MAJOR);
        } catch (Exception e) {
            String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            return new ScraperResult.Unavailable(msg, ScraperResult.Unavailable.Severity.MAJOR);
        }
    }
}
