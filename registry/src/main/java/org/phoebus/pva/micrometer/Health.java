package org.phoebus.pva.micrometer;

/**
 * Represents the health of a monitored component.
 *
 * <p>Created by a {@link HealthIndicator} and published to a PVA channel
 * by {@link PvaServiceBinder}.  Clients can observe the {@code status} and
 * {@code message} fields directly, and the alarm severity reflects the status:
 * {@code NO_ALARM} for {@link Status#UP}, {@code MINOR} for
 * {@link Status#DEGRADED}, and {@code MAJOR} for {@link Status#DOWN}.
 *
 * @param status  the health status
 * @param message optional human-readable description; must not be {@code null}
 */
public record Health(Status status, String message) {

    /**
     * Ordered health statuses, from best to worst.
     */
    public enum Status {
        /** Component is operating normally. */
        UP,
        /** Component is experiencing non-critical, recoverable issues. */
        DEGRADED,
        /** Component is unavailable or critically impaired. */
        DOWN
    }

    /**
     * Convenience factory: healthy component with no message.
     *
     * @return {@code Health(UP, "")}
     */
    public static Health up() {
        return new Health(Status.UP, "");
    }

    /**
     * Convenience factory: degraded component.
     *
     * @param message description of the degradation
     * @return {@code Health(DEGRADED, message)}
     */
    public static Health degraded(String message) {
        return new Health(Status.DEGRADED, message);
    }

    /**
     * Convenience factory: down component.
     *
     * @param message description of the failure
     * @return {@code Health(DOWN, message)}
     */
    public static Health down(String message) {
        return new Health(Status.DOWN, message);
    }
}
