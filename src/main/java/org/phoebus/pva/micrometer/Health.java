package org.phoebus.pva.micrometer;

/**
 * Represents the health of a service component.
 *
 * @param status  overall health status
 * @param message human-readable description
 */
public record Health(Status status, String message) {

    /** Health status levels, ordered from best to worst. */
    public enum Status {
        /** Service is operating normally. Maps to NO_ALARM (severity 0). */
        UP,
        /** Service is operating in a degraded state. Maps to MINOR alarm (severity 1). */
        DEGRADED,
        /** Service is not operating. Maps to MAJOR alarm (severity 2). */
        DOWN
    }

    /** Convenience factory for a healthy status with no message. */
    public static Health up() {
        return new Health(Status.UP, "");
    }

    /** Convenience factory for a down status. */
    public static Health down(String message) {
        return new Health(Status.DOWN, message);
    }

    /** Convenience factory for a degraded status. */
    public static Health degraded(String message) {
        return new Health(Status.DEGRADED, message);
    }
}
