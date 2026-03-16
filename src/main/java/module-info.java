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

    // Jackson needs reflective access to InfoDto (a record inside InfoPv)
    // for serialisation.  We open only to jackson-databind, not to ALL-UNNAMED,
    // to keep the encapsulation as tight as possible.
    opens org.phoebus.pva.micrometer to com.fasterxml.jackson.databind;

    // ----------------------------------------------------------------
    // Required modules
    // ----------------------------------------------------------------

    // Micrometer — transitive: consumers reference MeterRegistry, Clock, etc.
    // Module name comes from Automatic-Module-Name = project.name.replace('-','.')
    // in Micrometer's root build.gradle → "micrometer.core".
    requires transitive micrometer.core;

    // Phoebus core-pva — transitive: consumers may pass a PVAServer instance
    // to PvaMeterRegistry(config, clock, PVAServer).
    // core-pva ships no Automatic-Module-Name; the name is derived from the
    // JAR filename: core-pva-*.jar → "core.pva".
    requires transitive core.pva;

    // Jackson Databind — implementation detail (InfoPv JSON serialisation).
    // jackson-databind ships a module-info.class (added via moditect) with
    // name "com.fasterxml.jackson.databind", which re-exports
    // jackson-annotations transitively, covering the @JsonInclude usage.
    requires com.fasterxml.jackson.databind;

    // java.logging — java.util.logging.Logger used throughout the module.
    requires java.logging;
}
