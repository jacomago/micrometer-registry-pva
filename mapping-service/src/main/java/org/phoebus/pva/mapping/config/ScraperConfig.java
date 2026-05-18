package org.phoebus.pva.mapping.config;

import java.util.Map;

/**
 * Describes how to extract a single double value from a remote endpoint.
 *
 * <p>The {@code type} field selects the scraper implementation:
 * <ul>
 *   <li>{@code http-status} — issues a HEAD request; 2xx → 1.0, anything else → 0.0 (MAJOR alarm)
 *   <li>{@code prometheus}  — GETs a Prometheus text-format body and finds a named metric sample
 *   <li>{@code json}        — GETs a JSON or YAML body and navigates a dot-notation path
 * </ul>
 *
 * <p>Fields that are irrelevant to the chosen type are ignored.
 */
public record ScraperConfig(

        /** Selects the scraper implementation: {@code http-status}, {@code prometheus}, or {@code json}. */
        String type,

        // ── prometheus fields ─────────────────────────────────────────────────

        /** Prometheus metric name to look up (e.g. {@code jvm_memory_used_bytes}). */
        String metric,

        /**
         * Label filter for Prometheus: all entries must appear on the sample.
         * Extra labels present on the sample are allowed.  {@code null} → first matching sample.
         */
        Map<String, String> labels,

        // ── json fields ───────────────────────────────────────────────────────

        /**
         * Dot-notation path into the JSON/YAML response body
         * (e.g. {@code status}, {@code build.version}, {@code components.db.status}).
         */
        String jsonPath,

        /**
         * Optional string-to-double mapping applied when the node at {@code jsonPath} is a string.
         * Example: {@code {"UP": 1.0, "DOWN": 0.0}}.
         * If the string is not in the map, the result is {@link Double#NaN}.
         */
        Map<String, Double> valueMap

) {}
