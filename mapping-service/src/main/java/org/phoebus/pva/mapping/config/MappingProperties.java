package org.phoebus.pva.mapping.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;
import java.util.Map;

/**
 * Root configuration for the PVA mapping service, bound from the {@code pva-mapping}
 * prefix in {@code application.yml} (or any Spring-imported config file).
 *
 * <p>Example:
 * <pre>{@code
 * pva-mapping:
 *   poll-interval: PT10S
 *   services:
 *     archiver:
 *       base-url: "http://archiver:8080"
 *       channels:
 *         - pv: "ARCH:up"
 *           path: /actuator/health
 *           scraper: {type: http-status}
 * }</pre>
 */
@ConfigurationProperties(prefix = "pva-mapping")
public record MappingProperties(

        /**
         * Named map of services to monitor.  The map key is a logical service name used
         * in log messages and is not published as a PV name.
         */
        Map<String, ServiceConfig> services,

        /** Global poll interval applied to services that do not declare their own. Default: 10 s. */
        @DefaultValue("PT10S") Duration pollInterval

) {}
