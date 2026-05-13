package org.phoebus.pva.mapping.scraper;

/**
 * Fetches and extracts a single double value from a remote endpoint.
 *
 * <p>Implementations must be thread-safe (they may be called concurrently from
 * different service poll threads).  They must never throw — all failure modes are
 * returned as {@link ScraperResult.Unavailable}.
 */
@FunctionalInterface
public interface Scraper {
    ScraperResult scrape(String url);
}
