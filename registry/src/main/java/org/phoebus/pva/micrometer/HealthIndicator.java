package org.phoebus.pva.micrometer;

/**
 * Strategy for contributing health information to a {@code <prefix>.health} PVA channel.
 *
 * <p>Implementations are registered via
 * {@link PvaServiceBinder#withHealthIndicator(HealthIndicator)} and invoked on every
 * poll tick.  Multiple indicators are <em>aggregated</em>: the worst status among all
 * indicators wins and their messages are joined with {@code "; "}.
 *
 * <p>This is a {@link FunctionalInterface}, so callers can use a lambda:
 * <pre>{@code
 * binder.withHealthIndicator(() ->
 *     pool.isOpen() ? Health.up() : Health.down("connection pool closed"));
 * }</pre>
 *
 * <p>Implementations should be fast and non-blocking; expensive checks should be
 * performed asynchronously and the result cached for {@code check()}.
 */
@FunctionalInterface
public interface HealthIndicator {

    /**
     * Returns the current health of the component this indicator monitors.
     *
     * <p>Must not return {@code null}.  If the check itself fails unexpectedly the
     * {@link PvaServiceBinder} implementation will catch the exception and treat it as
     * {@link Health.Status#DOWN}.
     *
     * @return a non-null {@link Health} instance
     */
    Health check();
}
