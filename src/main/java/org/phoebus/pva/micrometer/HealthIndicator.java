package org.phoebus.pva.micrometer;

/**
 * Functional interface for supplying a {@link Health} status.
 *
 * <p>Register one or more instances with {@link PvaServiceBinder#withHealthIndicator(HealthIndicator)}.
 * Multiple indicators are combined using worst-wins semantics
 * ({@code DOWN} &gt; {@code DEGRADED} &gt; {@code UP}).
 */
@FunctionalInterface
public interface HealthIndicator {

    /**
     * Determine the current health of the monitored component.
     *
     * @return current {@link Health}; never {@code null}
     */
    Health check();
}
