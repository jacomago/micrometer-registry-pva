package org.phoebus.pva.mapping.config;

import java.time.Duration;
import java.util.List;

/**
 * Configuration for one monitored service: its base URL, an optional poll-interval
 * override, and the list of PVA channels to publish from it.
 */
public record ServiceConfig(

        /**
         * Base URL of the service (e.g. {@code http://archiver:8080}).
         * Each channel's {@code path} is appended to this to form the scrape URL.
         */
        String baseUrl,

        /**
         * Per-service poll interval.  When {@code null}, the global
         * {@link MappingProperties#pollInterval()} is used.
         */
        Duration pollInterval,

        /** Ordered list of PVA channels published from this service. */
        List<ChannelConfig> channels

) {}
