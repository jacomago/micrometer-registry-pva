package org.phoebus.pva.mapping;

import org.junit.jupiter.api.Test;
import org.phoebus.pva.mapping.scraper.PrometheusTextParser;
import org.phoebus.pva.mapping.scraper.Sample;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PrometheusTextParserTest {

    // ── parse() ──────────────────────────────────────────────────────────────

    @Test
    void parse_emptyBody_returnsEmptyList() {
        assertTrue(PrometheusTextParser.parse("").isEmpty());
        assertTrue(PrometheusTextParser.parse(null).isEmpty());
        assertTrue(PrometheusTextParser.parse("   ").isEmpty());
    }

    @Test
    void parse_commentsOnly_returnsEmptyList() {
        String body = """
                # HELP jvm_memory_used_bytes Memory used
                # TYPE jvm_memory_used_bytes gauge
                """;
        assertTrue(PrometheusTextParser.parse(body).isEmpty());
    }

    @Test
    void parse_simpleGaugeNoLabels() {
        String body = "uptime_seconds 42.5";
        List<Sample> samples = PrometheusTextParser.parse(body);
        assertEquals(1, samples.size());
        assertEquals("uptime_seconds", samples.get(0).name());
        assertEquals(42.5, samples.get(0).value(), 1e-9);
        assertTrue(samples.get(0).labels().isEmpty());
    }

    @Test
    void parse_gaugeWithLabels() {
        String body = """
                # TYPE jvm_memory_used_bytes gauge
                jvm_memory_used_bytes{area="heap",id="G1 Old Gen"} 12345678.0
                """;
        List<Sample> samples = PrometheusTextParser.parse(body);
        assertEquals(1, samples.size());
        assertEquals("jvm_memory_used_bytes", samples.get(0).name());
        assertEquals(12345678.0, samples.get(0).value(), 1e-9);
        assertEquals(Map.of("area", "heap", "id", "G1 Old Gen"), samples.get(0).labels());
    }

    @Test
    void parse_counterWithTotalSuffix() {
        String body = """
                # TYPE http_requests_total counter
                http_requests_total{method="GET",status="200"} 1027.0 1395066363000
                http_requests_total{method="POST",status="400"} 3.0
                """;
        List<Sample> samples = PrometheusTextParser.parse(body);
        assertEquals(2, samples.size());
        assertEquals(1027.0, samples.get(0).value(), 1e-9);
        assertEquals("GET", samples.get(0).labels().get("method"));
        assertEquals(3.0, samples.get(1).value(), 1e-9);
    }

    @Test
    void parse_histogramSuffixLinesAreEachOwnSample() {
        String body = """
                # TYPE http_request_duration_seconds histogram
                http_request_duration_seconds_bucket{le="0.05"} 24054.0
                http_request_duration_seconds_bucket{le="0.1"} 33444.0
                http_request_duration_seconds_sum 53423.0
                http_request_duration_seconds_count 144320.0
                """;
        List<Sample> samples = PrometheusTextParser.parse(body);
        assertEquals(4, samples.size());
        assertEquals("http_request_duration_seconds_bucket", samples.get(0).name());
        assertEquals(24054.0, samples.get(0).value(), 1e-9);
        assertEquals("http_request_duration_seconds_count", samples.get(3).name());
    }

    @Test
    void parse_malformedLinesSkipped() {
        String body = """
                good_metric 1.0
                this is not valid
                another_good_metric 2.0
                """;
        List<Sample> samples = PrometheusTextParser.parse(body);
        assertEquals(2, samples.size());
    }

    @Test
    void parse_specialValueInf() {
        String body = "metric_total +Inf";
        List<Sample> samples = PrometheusTextParser.parse(body);
        assertEquals(1, samples.size());
        assertTrue(Double.isInfinite(samples.get(0).value()));
    }

    @Test
    void parse_labelValueWithEscapedQuote() {
        String body = "metric{label=\"val\\\"ue\"} 1.0";
        List<Sample> samples = PrometheusTextParser.parse(body);
        assertEquals(1, samples.size());
        assertEquals("val\"ue", samples.get(0).labels().get("label"));
    }

    // ── find() ───────────────────────────────────────────────────────────────

    @Test
    void find_returnsFirstMatchingByName() {
        List<Sample> samples = PrometheusTextParser.parse("""
                jvm_memory_used_bytes{area="heap"} 100.0
                jvm_memory_used_bytes{area="nonheap"} 200.0
                """);
        Optional<Sample> found = PrometheusTextParser.find(samples, "jvm_memory_used_bytes", null);
        assertTrue(found.isPresent());
        assertEquals(100.0, found.get().value(), 1e-9);
    }

    @Test
    void find_labelFilterMatchesExactEntry() {
        List<Sample> samples = PrometheusTextParser.parse("""
                jvm_memory_used_bytes{area="heap",id="G1 Old Gen"} 111.0
                jvm_memory_used_bytes{area="nonheap",id="Metaspace"} 222.0
                """);
        Optional<Sample> found = PrometheusTextParser.find(
                samples, "jvm_memory_used_bytes", Map.of("area", "nonheap"));
        assertTrue(found.isPresent());
        assertEquals(222.0, found.get().value(), 1e-9);
    }

    @Test
    void find_labelFilterAllowsExtraLabelsOnSample() {
        List<Sample> samples = PrometheusTextParser.parse(
                "metric{a=\"1\",b=\"2\",c=\"3\"} 42.0");
        // Only require a=1; b and c are extra — still matches
        Optional<Sample> found = PrometheusTextParser.find(
                samples, "metric", Map.of("a", "1"));
        assertTrue(found.isPresent());
    }

    @Test
    void find_metricAbsent_returnsEmpty() {
        List<Sample> samples = PrometheusTextParser.parse("other_metric 1.0");
        assertFalse(PrometheusTextParser.find(samples, "missing_metric", null).isPresent());
    }

    @Test
    void find_labelMismatch_returnsEmpty() {
        List<Sample> samples = PrometheusTextParser.parse(
                "metric{area=\"heap\"} 1.0");
        assertFalse(PrometheusTextParser.find(
                samples, "metric", Map.of("area", "nonheap")).isPresent());
    }
}
