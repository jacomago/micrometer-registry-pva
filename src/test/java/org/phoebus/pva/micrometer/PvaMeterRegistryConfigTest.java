package org.phoebus.pva.micrometer;

import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Tags;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Iterator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    // enabled()
    // -------------------------------------------------------------------------

    @Test
    void enabled_defaultsToTrue_whenGetReturnsNull() {
        PvaMeterRegistryConfig cfg = key -> null;
        assertTrue(cfg.enabled(), "enabled() must default to true when get() returns null");
    }

    @Test
    void enabled_returnsFalse_whenPropertyIsFalse() {
        PvaMeterRegistryConfig cfg = key -> "pva.enabled".equals(key) ? "false" : null;
        assertFalse(cfg.enabled(), "enabled() must return false when pva.enabled=false");
    }

    @Test
    void enabled_returnsTrue_whenPropertyIsTrue() {
        PvaMeterRegistryConfig cfg = key -> "pva.enabled".equals(key) ? "true" : null;
        assertTrue(cfg.enabled(), "enabled() must return true when pva.enabled=true");
    }

    // -------------------------------------------------------------------------
    // commonTags()
    // -------------------------------------------------------------------------

    @Test
    void commonTags_defaultIsEmpty() {
        PvaMeterRegistryConfig cfg = key -> null;
        Iterable<Tag> tags = cfg.commonTags();
        assertNotNull(tags, "commonTags() must not return null");
        assertFalse(tags.iterator().hasNext(), "commonTags() must be empty by default");
    }

    @Test
    void commonTags_canBeOverridden() {
        Iterable<Tag> customTags = Tags.of("region", "us-east");
        PvaMeterRegistryConfig cfg = new PvaMeterRegistryConfig() {
            @Override
            public String get(String key) {
                return null;
            }

            @Override
            public Iterable<Tag> commonTags() {
                return customTags;
            }
        };
        Iterator<Tag> it = cfg.commonTags().iterator();
        assertTrue(it.hasNext(), "overridden commonTags() must have at least one tag");
        Tag tag = it.next();
        assertEquals("region", tag.getKey());
        assertEquals("us-east", tag.getValue());
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
        assertTrue(PvaMeterRegistryConfig.DEFAULT.enabled());
        assertFalse(PvaMeterRegistryConfig.DEFAULT.commonTags().iterator().hasNext());
    }
}
