package org.phoebus.pva.mapping;

import org.phoebus.pva.mapping.config.MappingProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Entry point for the PVA Mapping Service.
 *
 * <p>Configuration is loaded from {@code application.yml} (bundled) and any additional
 * files specified via {@code --spring.config.import=file:./mapping.yml} on the command line.
 *
 * <p>Quick start:
 * <pre>{@code
 * java -jar pva-mapping-service.jar --spring.config.import=file:./mapping.yml
 * }</pre>
 */
@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(MappingProperties.class)
public class MappingServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(MappingServiceApplication.class, args);
    }
}
