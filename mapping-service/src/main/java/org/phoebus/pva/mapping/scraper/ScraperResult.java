package org.phoebus.pva.mapping.scraper;

/**
 * The outcome of one scrape attempt: either a numeric value ready to publish,
 * or a description of why the value is unavailable along with the alarm severity
 * that should be set on the PVA channel.
 */
public sealed interface ScraperResult permits ScraperResult.Value, ScraperResult.Unavailable {

    /**
     * A successfully extracted value.  The PVA channel will be updated with this
     * value and its alarm cleared to {@code NO_ALARM}.
     */
    record Value(double value) implements ScraperResult {}

    /**
     * The value could not be obtained.  The PVA channel alarm is set to
     * {@link Severity#MINOR} (service reachable but metric absent) or
     * {@link Severity#MAJOR} (endpoint unreachable or HTTP error).
     * The channel's previous value is retained.
     */
    record Unavailable(String reason, Severity severity) implements ScraperResult {

        public enum Severity {
            /** Service answered but the requested metric/path was not present. */
            MINOR,
            /** Endpoint unreachable, timed out, or returned an HTTP error status. */
            MAJOR
        }
    }
}
