package org.phoebus.pva.micrometer;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Unit tests for the default methods on {@link PvaMeterRegistryConfig}.
 *
 * <p>Tests the {@code prefix()}, {@code step()}, and {@code namingStrategy()} defaults,
 * including the string-parsing branch of {@code step()} (line 52 of the interface).
 */
class PvaMeterRegistryConfigTest {

    /**
     * Minimal implementation that always returns the supplied step string for the
     * {@code "pva.step"} key, enabling testing of the {@code Duration.parse(v)} branch.
     */
    private static PvaMeterRegistryConfig configReturning(String stepValue) {
        return key -> "pva.step".equals(key) ? stepValue : null;
    }

    // -------------------------------------------------------------------------
    // prefix()
    // -------------------------------------------------------------------------

    @Test
    void prefix_returnsCorrectPrefix() {
        PvaMeterRegistryConfig cfg = key -> null;
        assertEquals("pva", cfg.prefix(), "Default prefix must be \"pva\"");
    }

    // -------------------------------------------------------------------------
    // step()
    // -------------------------------------------------------------------------

    @Test
    void step_defaultIsTenSeconds_whenGetReturnsNull() {
        PvaMeterRegistryConfig cfg = key -> null;
        assertEquals(Duration.ofSeconds(10), cfg.step(),
                "Default step must be 10 s when get() returns null");
    }

    @Test
    void step_parsesIsoDurationString() {
        // Exercises the non-null branch: Duration.parse(v) at line 52.
        PvaMeterRegistryConfig cfg = configReturning("PT30S");
        assertEquals(Duration.ofSeconds(30), cfg.step(),
                "step() must parse ISO-8601 duration string \"PT30S\" as 30 seconds");
    }

    // -------------------------------------------------------------------------
    // namingStrategy()
    // -------------------------------------------------------------------------

    @Test
    void namingStrategy_defaultIsDotsWithBraceTags() {
        PvaMeterRegistryConfig cfg = key -> null;
        assertEquals(PvNamingStrategy.DOTS_WITH_BRACE_TAGS, cfg.namingStrategy(),
                "Default naming strategy must be DOTS_WITH_BRACE_TAGS");
    }

    // -------------------------------------------------------------------------
    // DEFAULT singleton
    // -------------------------------------------------------------------------

    @Test
    void default_singleton_usable() {
        assertNotNull(PvaMeterRegistryConfig.DEFAULT, "DEFAULT must not be null");
        assertEquals("pva", PvaMeterRegistryConfig.DEFAULT.prefix());
        assertEquals(Duration.ofSeconds(10), PvaMeterRegistryConfig.DEFAULT.step());
        assertEquals(PvNamingStrategy.DOTS_WITH_BRACE_TAGS,
                PvaMeterRegistryConfig.DEFAULT.namingStrategy());
    }
}
