package org.phoebus.pva.mapping.scraper;

/**
 * The outcome of one scrape attempt: either a numeric value or a description of why
 * the value could not be obtained.
 *
 * <p>An {@link Unavailable} result causes the backing {@code AtomicDouble} to be set to
 * {@link Double#NaN}, which the Micrometer {@code PvaMeterRegistry} publishes with an
 * {@code INVALID} alarm.
 */
public sealed interface ScraperResult permits ScraperResult.Value, ScraperResult.Unavailable {

    record Value(double value) implements ScraperResult {}

    record Unavailable(String reason) implements ScraperResult {}
}
