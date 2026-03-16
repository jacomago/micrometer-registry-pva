/**
 * Micrometer registry backed by an EPICS PV Access (PVA) server.
 *
 * <p>The public API is in {@code org.phoebus.pva.micrometer}.
 * The {@code org.phoebus.pva.micrometer.internal} package contains
 * implementation details and is not exported — callers must not depend on it.
 */
module org.phoebus.pva.micrometer {

    // ----------------------------------------------------------------
    // Public API
    // ----------------------------------------------------------------
    exports org.phoebus.pva.micrometer;
    // org.phoebus.pva.micrometer.internal is intentionally NOT exported.

    // ----------------------------------------------------------------
    // Required modules
    // ----------------------------------------------------------------

    // Micrometer — transitive: consumers reference MeterRegistry, Clock, etc.
    requires transitive io.micrometer.core;

    // Phoebus core-pva — transitive: consumers may pass a PVAServer instance
    // to PvaMeterRegistry(config, clock, PVAServer).
    requires transitive org.phoebus.core.pva;

    // Jackson Databind — implementation detail (InfoPv JSON serialisation).
    // jackson-databind re-exports jackson-annotations transitively, so
    // com.fasterxml.jackson.annotation types in InfoPv are covered.
    requires com.fasterxml.jackson.databind;

    // java.logging — java.util.logging.Logger used throughout the module.
    requires java.logging;
}
