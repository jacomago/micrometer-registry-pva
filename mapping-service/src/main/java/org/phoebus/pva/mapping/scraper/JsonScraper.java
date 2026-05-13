package org.phoebus.pva.mapping.scraper;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;

/**
 * GETs a JSON or YAML endpoint and extracts a value at a dot-notation path.
 *
 * <p>Value coercion rules:
 * <ul>
 *   <li>Numeric node → used directly as {@code double}
 *   <li>Boolean node → {@code true} = 1.0, {@code false} = 0.0
 *   <li>String node with a {@code valueMap} entry → mapped value
 *   <li>String node without a matching {@code valueMap} entry → {@link Double#NaN}
 * </ul>
 *
 * <p>Returns {@link ScraperResult.Unavailable} with
 * {@link ScraperResult.Unavailable.Severity#MINOR MINOR} when the path is absent,
 * or {@link ScraperResult.Unavailable.Severity#MAJOR MAJOR} on HTTP / network errors.
 */
public class JsonScraper implements Scraper {

    private final String jsonPath;
    private final Map<String, Double> valueMap;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public JsonScraper(String jsonPath,
                       Map<String, Double> valueMap,
                       HttpClient httpClient,
                       ObjectMapper objectMapper) {
        this.jsonPath = jsonPath;
        this.valueMap = valueMap;
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
    }

    @Override
    public ScraperResult scrape(String url) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .GET()
                    .build();
            HttpResponse<String> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return new ScraperResult.Unavailable("HTTP " + response.statusCode(),
                        ScraperResult.Unavailable.Severity.MAJOR);
            }
            JsonNode root = objectMapper.readTree(response.body());
            JsonNode node = navigatePath(root, jsonPath);
            if (node.isMissingNode() || node.isNull()) {
                return new ScraperResult.Unavailable(
                        "Path not found: " + jsonPath,
                        ScraperResult.Unavailable.Severity.MINOR);
            }
            return new ScraperResult.Value(coerce(node));
        } catch (Exception e) {
            String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            return new ScraperResult.Unavailable(msg, ScraperResult.Unavailable.Severity.MAJOR);
        }
    }

    private JsonNode navigatePath(JsonNode root, String dotPath) {
        JsonNode cur = root;
        for (String key : dotPath.split("\\.")) {
            cur = cur.path(key);
            if (cur.isMissingNode()) return cur;
        }
        return cur;
    }

    private double coerce(JsonNode node) {
        if (node.isNumber())  return node.doubleValue();
        if (node.isBoolean()) return node.booleanValue() ? 1.0 : 0.0;
        if (node.isTextual() && valueMap != null) {
            Double mapped = valueMap.get(node.textValue());
            return mapped != null ? mapped : Double.NaN;
        }
        return Double.NaN;
    }
}
