package org.phoebus.pva.mapping.scraper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;

/**
 * Fetches a Prometheus text-format endpoint and extracts one named metric sample.
 *
 * <p>Returns {@link ScraperResult.Value} when the metric is found (with optional label
 * filtering).  Returns {@link ScraperResult.Unavailable} with
 * {@link ScraperResult.Unavailable.Severity#MINOR MINOR} when the metric is absent from
 * the response (service is up, metric may have disappeared), or
 * {@link ScraperResult.Unavailable.Severity#MAJOR MAJOR} on any HTTP or network error.
 */
public class PrometheusScraper implements Scraper {

    private final String metric;
    private final Map<String, String> labels;
    private final HttpClient httpClient;

    public PrometheusScraper(String metric, Map<String, String> labels, HttpClient httpClient) {
        this.metric = metric;
        this.labels = labels;
        this.httpClient = httpClient;
    }

    @Override
    public ScraperResult scrape(String url) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Accept", "text/plain; version=0.0.4; charset=utf-8")
                    .GET()
                    .build();
            HttpResponse<String> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return new ScraperResult.Unavailable("HTTP " + response.statusCode());
            }
            List<Sample> samples = PrometheusTextParser.parse(response.body());
            return PrometheusTextParser.find(samples, metric, labels)
                    .map(s -> (ScraperResult) new ScraperResult.Value(s.value()))
                    .orElse(new ScraperResult.Unavailable("Metric not found: " + metric));
        } catch (Exception e) {
            String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            return new ScraperResult.Unavailable(msg);
        }
    }
}
