package org.phoebus.pva.mapping.scraper;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.phoebus.pva.mapping.config.ScraperConfig;
import org.springframework.stereotype.Component;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * Creates {@link Scraper} instances from their {@link ScraperConfig} descriptions.
 *
 * <p>All scrapers share the same {@link HttpClient} bean (configured with connect and
 * read timeouts) and, for the JSON scraper, the application's {@link ObjectMapper}.
 */
@Component
public class ScraperFactory {

    static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(5);
    static final Duration DEFAULT_REQUEST_TIMEOUT  = Duration.ofSeconds(10);

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public ScraperFactory(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(DEFAULT_CONNECT_TIMEOUT)
                .build();
    }

    /**
     * Creates a {@link Scraper} for the given configuration.
     *
     * @throws IllegalArgumentException if {@code config.type()} is not recognised
     */
    public Scraper create(ScraperConfig config) {
        if (config == null || config.type() == null) {
            throw new IllegalArgumentException("ScraperConfig must have a non-null type");
        }
        return switch (config.type()) {
            case "http-status" -> new HttpStatusScraper(httpClient);
            case "prometheus"  -> new PrometheusScraper(config.metric(), config.labels(), httpClient);
            case "json"        -> new JsonScraper(config.jsonPath(), config.valueMap(),
                                                  httpClient, objectMapper);
            default -> throw new IllegalArgumentException(
                    "Unknown scraper type '" + config.type() + "'. "
                    + "Valid types: http-status, prometheus, json");
        };
    }
}
