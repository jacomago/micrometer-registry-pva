package org.phoebus.pva.mapping.config;

/**
 * Declares one PVA channel: the EPICS PV name it publishes under, where to fetch the
 * value from, and how to extract it.
 */
public record ChannelConfig(

        /** EPICS PV name chosen by the OPI author (e.g. {@code ARCH:mem:heap}). */
        String pv,

        /**
         * URL path appended to the service {@code base-url}
         * (e.g. {@code /actuator/prometheus}).  Ignored when {@link #url} is set.
         */
        String path,

        /**
         * Fully-qualified URL override.  When set, takes precedence over
         * {@link #path} + service {@code base-url}.
         */
        String url,

        /** Selects and configures the scraper implementation. */
        ScraperConfig scraper,

        /** Human-readable description written to {@code display.description} on the PVA channel. */
        String description,

        /** Engineering unit string written to {@code display.units} (EGU) on the PVA channel. */
        String unit

) {
    /** Returns the effective URL, preferring {@link #url} over {@code baseUrl + path}. */
    public String resolvedUrl(String baseUrl) {
        if (url != null && !url.isBlank()) return url;
        return (baseUrl != null ? baseUrl : "") + (path != null ? path : "");
    }
}
