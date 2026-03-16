# micrometer-registry-pva

[![CI](https://github.com/jacomago/micrometer-registry-pva/actions/workflows/ci.yml/badge.svg)](https://github.com/jacomago/micrometer-registry-pva/actions/workflows/ci.yml)
[![CodeQL](https://github.com/jacomago/micrometer-registry-pva/actions/workflows/codeql.yml/badge.svg)](https://github.com/jacomago/micrometer-registry-pva/actions/workflows/codeql.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)
[![Java 17+](https://img.shields.io/badge/Java-17%2B-blue.svg)](https://adoptium.net/)

A [Micrometer](https://micrometer.io/) `MeterRegistry` backed by an
[EPICS PV Access (PVA)](https://github.com/epics-base/pvAccessJava) server.
Any Java application instrumented with Micrometer can publish all its metrics
as live PVA process variables accessible to EPICS clients (e.g. Phoebus).

---

## Table of contents

1. [Building & installing locally](#building--installing-locally)
2. [Consumer configuration](#consumer-configuration)
3. [PVA port / network configuration](#pva-port--network-configuration)
4. [Quick-start usage](#quick-start-usage)
5. [Customising the registry](#customising-the-registry)
6. [PV naming strategies](#pv-naming-strategies)
7. [Meter type → PV type reference](#meter-type--pv-type-reference)
8. [Spring Boot integration](#spring-boot-integration)

---

## Building & installing locally

```bash
mvn install
```

This compiles, tests, and installs the artifact to `~/.m2/repository`.

### Deploying to the Phoebus Nexus/Artifactory (future)

Once the Nexus/Artifactory URL is confirmed and recorded in
[`pom.xml`](pom.xml), run:

```bash
# TODO: replace <NEXUS_URL> with the confirmed repository URL before releasing
mvn deploy -P phoebus-releases -Dphoebus.nexus.url=<NEXUS_URL>
```

Configure credentials in `~/.m2/settings.xml`:

```xml
<settings>
  <servers>
    <server>
      <id>phoebus-releases</id>
      <username>your-username</username>
      <password>your-password</password>
    </server>
  </servers>
</settings>
```

---

## Consumer configuration

### Repository entry (required for `core-pva:4.7.x`)

`org.phoebus:core-pva` is **not published to Maven Central** for versions before
`4.6.7`. If you depend on `core-pva:4.7.x` (or any pre-4.6.7 release) you must
declare the Phoebus Nexus repository in your project so Maven can resolve it.

**Maven (`pom.xml`)**

```xml
<!-- TODO: replace NEXUS_URL with the confirmed Phoebus Nexus/Artifactory URL -->
<repositories>
  <repository>
    <id>phoebus-releases</id>
    <name>Phoebus Releases</name>
    <url>NEXUS_URL/repository/releases/</url>
    <releases><enabled>true</enabled></releases>
    <snapshots><enabled>false</enabled></snapshots>
  </repository>
</repositories>
```

**Gradle (`build.gradle`)**

```groovy
repositories {
    maven {
        name = 'phoebusReleases'
        // TODO: replace NEXUS_URL with the confirmed Phoebus Nexus/Artifactory URL
        url  = 'NEXUS_URL/repository/releases/'
    }
    mavenCentral()
}
```

### Dependency declaration

```xml
<!-- Maven -->
<dependency>
  <groupId>org.phoebus</groupId>
  <artifactId>micrometer-registry-pva</artifactId>
  <version>1.0.0-SNAPSHOT</version>
</dependency>
```

```groovy
// Gradle
implementation 'org.phoebus:micrometer-registry-pva:1.0.0-SNAPSHOT'
```

---

## PVA port / network configuration

The embedded PVA server reads the following standard EPICS environment variables
(or Java system properties of the same name):

| Variable / Property | Default | Description |
|---|---|---|
| `EPICS_PVA_SERVER_PORT` | `5075` | TCP port the PVA server listens on for client connections. |
| `EPICS_PVAS_INTF_ADDR_LIST` | *(all interfaces)* | Space-separated list of local interface addresses the PVA server binds to. Useful when the host has multiple NICs and you need to restrict PVA traffic to a specific network. |

Set them as JVM system properties at startup:

```bash
java -DEPICS_PVA_SERVER_PORT=5076 \
     -DEPICS_PVAS_INTF_ADDR_LIST=192.168.1.10 \
     -jar your-application.jar
```

Or as OS environment variables (picked up automatically by the PVA library):

```bash
export EPICS_PVA_SERVER_PORT=5076
export EPICS_PVAS_INTF_ADDR_LIST=192.168.1.10
java -jar your-application.jar
```

---

## Quick-start usage

The snippet below wires `PvaMeterRegistry` into a `CompositeMeterRegistry`
alongside any other registries your application already uses (e.g. Prometheus,
JMX).  All metrics registered with the composite are automatically exported as
PVA process variables.

```java
import io.micrometer.core.instrument.composite.CompositeMeterRegistry;
import org.phoebus.pva.micrometer.PvaMeterRegistry;
import org.phoebus.pva.micrometer.PvaMeterRegistryConfig;

// 1. Build the PVA registry (starts an embedded PVA server on the default port).
PvaMeterRegistry pvaRegistry = new PvaMeterRegistry(
        PvaMeterRegistryConfig.DEFAULT,
        io.micrometer.core.instrument.Clock.SYSTEM);

// 2. Add it to a composite together with your existing registries.
CompositeMeterRegistry composite = new CompositeMeterRegistry();
composite.add(pvaRegistry);
// composite.add(prometheusRegistry);  // optional

// 3. Use the composite as your global registry.
io.micrometer.core.instrument.Metrics.addRegistry(composite);

// Any Micrometer instrumentation now publishes metrics over PVA.
composite.counter("my.events").increment();
```

> **Tip:** To use an already-running `PVAServer` instance (e.g. from Phoebus),
> pass it as the third constructor argument so a second server is not started:
>
> ```java
> PvaMeterRegistry pvaRegistry = new PvaMeterRegistry(
>         PvaMeterRegistryConfig.DEFAULT,
>         Clock.SYSTEM,
>         existingPvaServer);
> ```

### Adding JVM metrics, build info, and health (PvaServiceBinder)

`PvaServiceBinder` is a fluent builder that wires standard JVM metrics and optional
service metadata into the registry in a single call:

```java
import org.phoebus.pva.micrometer.Health;
import org.phoebus.pva.micrometer.PvaServiceBinder;

PvaServiceBinder.forService("my-service")
    // Publish build metadata as a one-shot my-service.info PV (JSON)
    .withBuildInfo("1.0.0", "2024-01-15", "abc1234")
    // Publish aggregated health as my-service.health PV (updated on every poll tick)
    .withHealthIndicator(() -> dbPool.isOpen()
            ? Health.up()
            : Health.down("connection pool closed"))
    .bindTo(pvaRegistry);
```

After `bindTo`:

| PVA channel | Type | Updated |
|---|---|---|
| `my-service.info` | `NTScalar string` (JSON) | Once at startup |
| `my-service.health` | `NTScalar string` | Every poll tick |
| `jvm.memory.*`, `jvm.gc.*`, etc. | `NTScalar double` | Every poll tick |

Call `.withoutGcMetrics()`, `.withoutThreadMetrics()`, or `.withoutClassLoaderMetrics()`
before `bindTo` to suppress individual JVM metric groups.

---

## Customising the registry

All `PvaMeterRegistryConfig` properties have sensible defaults.  Override any
subset via an anonymous class or lambda:

```java
import io.micrometer.core.instrument.Clock;
import io.micrometer.core.instrument.Tags;
import org.phoebus.pva.micrometer.PvaMeterRegistry;
import org.phoebus.pva.micrometer.PvaMeterRegistryConfig;
import org.phoebus.pva.micrometer.PvNamingStrategy;

import java.net.InetAddress;
import java.time.Duration;

PvaMeterRegistry registry = new PvaMeterRegistry(
    new PvaMeterRegistryConfig() {
        @Override
        public Duration step() {
            return Duration.ofSeconds(5);  // push updates every 5 s
        }
        @Override
        public boolean enabled() {
            // Disable PVA publishing in CI environments
            return !"true".equals(System.getenv("CI"));
        }
        @Override
        public Iterable<Tag> commonTags() {
            // Tag every meter with the hostname
            try {
                return Tags.of("host", InetAddress.getLocalHost().getHostName());
            } catch (Exception e) {
                return Tags.empty();
            }
        }
        @Override
        public PvNamingStrategy namingStrategy() {
            return PvNamingStrategy.COLONS;  // use colon-separated PV names
        }
    },
    Clock.SYSTEM);
```

When using a property-source (e.g. Spring `Environment` — see
[Spring Boot integration](#spring-boot-integration) below), implement
`get(String key)` to delegate property lookups:

```java
// Reads pva.step, pva.enabled, etc. from System properties
PvaMeterRegistryConfig config = key -> System.getProperty(key);
```

Supported property keys:

| Property key | Type | Default | Description |
|---|---|---|---|
| `pva.step` | ISO-8601 duration | `PT10S` | How often meter values are polled and pushed to PVA clients. |
| `pva.enabled` | boolean | `true` | Set to `false` to disable PVA publishing without removing the registry. |

---

## PV naming strategies

`PvNamingStrategy` controls how a Micrometer `Meter.Id` (name + tags) is
converted into a PVA channel name string.  Three built-in strategies are provided:

| Strategy | Example PV name |
|---|---|
| `DOTS_WITH_BRACE_TAGS` *(default)* | `app.requests{method="GET",status="200"}` |
| `COLONS` | `app:requests:method:GET:status:200` |
| `NAME_ONLY` | `app.requests` |

> **Warning:** `NAME_ONLY` strips all tags from the PV name.  If two meters share
> the same name but differ only in tags (e.g. `http.requests{method="GET"}` and
> `http.requests{method="POST"}`), a name collision will be thrown at registration
> time.  Use `NAME_ONLY` only when you are certain all meter names are unique
> within the registry.

Tags are sorted alphabetically within the name, so PV names are deterministic
regardless of the order tags were added.

---

## Meter type → PV type reference

| Micrometer type | PVA structure type | Published fields | Units |
|---|---|---|---|
| `Gauge` | `NTScalar double` | `value` | As configured |
| `Counter` | `NTScalar double` | `value` (cumulative count) | — |
| `TimeGauge` | `NTScalar double` | `value` | seconds |
| `FunctionCounter` | `NTScalar double` | `value` (cumulative count) | — |
| `Timer` | `micrometer:Timer:1.0` | `count`, `totalTime`, `max` | `count` is dimensionless; times in seconds |
| `DistributionSummary` | `micrometer:Summary:1.0` | `count`, `total`, `max` | As configured |
| `LongTaskTimer` | `micrometer:LongTaskTimer:1.0` | `activeTasks`, `duration` | `activeTasks` is dimensionless; duration in seconds |
| `FunctionTimer` | `micrometer:FunctionTimer:1.0` | `count`, `totalTime` | times in seconds |
| `Meter` (generic) | `micrometer:Meter:1.0` | one field per `Measurement` | As reported by the measurement |

> **Note:** Percentiles, percentile histograms, and SLO boundaries are **not
> supported** by this registry.  If a `Timer` or `DistributionSummary` is
> configured with those settings (e.g. via `publishPercentiles(0.95)`), the
> settings are silently ignored and a `WARNING` is logged.

All scalar channels (`NTScalar double`) also carry an `alarm` field (severity
`NO_ALARM` on success, `INVALID` if the value function throws) and a
`timeStamp` updated on every poll tick.

---

## Spring Boot integration

This library does not ship Spring Boot auto-configuration.  Wire it manually
using a `@Configuration` class.  Because `PvaMeterRegistry` implements
`AutoCloseable`, Spring manages its lifecycle automatically — no `@PreDestroy`
annotation is needed.

### Dependencies

Add the registry dependency plus (optionally) Spring Boot Actuator for health
bridging:

**Maven (`pom.xml`)**

```xml
<dependency>
  <groupId>org.phoebus</groupId>
  <artifactId>micrometer-registry-pva</artifactId>
  <version>1.0.0-SNAPSHOT</version>
</dependency>

<!-- Optional: bridges Spring Actuator health indicators to PVA health PV -->
<dependency>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-actuator</artifactId>
</dependency>
```

**Gradle (`build.gradle`)**

```groovy
implementation 'org.phoebus:micrometer-registry-pva:1.0.0-SNAPSHOT'

// Optional
implementation 'org.springframework.boot:spring-boot-starter-actuator'
```

### Minimal `@Configuration`

```java
import io.micrometer.core.instrument.Clock;
import io.micrometer.core.instrument.composite.CompositeMeterRegistry;
import io.micrometer.core.instrument.config.MeterRegistryCustomizer;
import org.phoebus.pva.micrometer.PvaMeterRegistry;
import org.phoebus.pva.micrometer.PvaMeterRegistryConfig;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

@Configuration
public class PvaMetricsConfig {

    /**
     * Binds Spring's Environment to PvaMeterRegistryConfig so that properties
     * declared in application.properties (e.g. pva.step, pva.enabled) are
     * picked up automatically.
     */
    @Bean
    public PvaMeterRegistryConfig pvaMeterRegistryConfig(Environment env) {
        return env::getProperty;
    }

    /**
     * Creates the registry.  Spring calls close() automatically on context
     * shutdown because PvaMeterRegistry implements AutoCloseable.
     */
    @Bean
    public PvaMeterRegistry pvaMeterRegistry(PvaMeterRegistryConfig config) {
        return new PvaMeterRegistry(config, Clock.SYSTEM);
    }

    /**
     * Plugs the registry into Spring Boot's auto-configured CompositeMeterRegistry
     * so that all @Timed, @Counted, and other Micrometer instrumentation is
     * automatically published over PVA.
     */
    @Bean
    public MeterRegistryCustomizer<CompositeMeterRegistry> pvaRegistryCustomizer(
            PvaMeterRegistry pvaRegistry) {
        return composite -> composite.add(pvaRegistry);
    }
}
```

### `application.properties` / `application.yml`

```properties
# How often meter values are pushed to PVA clients (ISO-8601 duration)
pva.step=PT5S

# Set to false to disable PVA publishing without removing the bean
# (useful in test profiles)
pva.enabled=true

# EPICS network settings — can also be set as OS environment variables
EPICS_PVA_SERVER_PORT=5075
# EPICS_PVAS_INTF_ADDR_LIST=192.168.1.10
```

```yaml
pva:
  step: PT5S
  enabled: true
```

### Adding JVM metrics and service metadata

Use `PvaServiceBinder` as a `@Bean` to register standard JVM metrics, build
information, and health status alongside the registry:

```java
import org.phoebus.pva.micrometer.Health;
import org.phoebus.pva.micrometer.PvaServiceBinder;
import org.springframework.boot.info.BuildProperties;
import org.springframework.context.annotation.Bean;

@Bean
public PvaServiceBinder pvaServiceBinder(PvaMeterRegistry registry,
                                          BuildProperties buildProperties) {
    PvaServiceBinder binder = PvaServiceBinder.forService("my-service")
        .withBuildInfo(
            buildProperties.getVersion(),
            buildProperties.getTime().toString(),
            buildProperties.get("git.commit.id.abbrev"));

    binder.bindTo(registry);
    return binder;
}
```

> **Note:** `BuildProperties` is auto-configured by Spring Boot when the
> `spring-boot-maven-plugin` is configured with `<executions><execution><goals>
> <goal>build-info</goal></goals></execution></executions>`.  If it is not on
> the classpath, remove the `BuildProperties` parameter and call
> `withBuildInfo(version, date, commit)` with hard-coded or `@Value`-injected
> strings, or omit the call entirely.

### Bridging Spring Boot Actuator health indicators

If you have Spring Boot Actuator on the classpath, you can forward all registered
Spring `HealthIndicator` beans to the PVA health PV so that EPICS clients see a
live aggregated health status:

```java
import org.phoebus.pva.micrometer.Health;
import org.phoebus.pva.micrometer.PvaServiceBinder;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.context.annotation.Bean;

import java.util.Map;

@Bean
public PvaServiceBinder pvaServiceBinder(
        PvaMeterRegistry registry,
        Map<String, HealthIndicator> springIndicators) {  // injected by Spring

    PvaServiceBinder binder = PvaServiceBinder.forService("my-service");

    // Convert each Spring HealthIndicator to a PVA HealthIndicator
    springIndicators.forEach((name, indicator) ->
        binder.withHealthIndicator(() -> {
            org.springframework.boot.actuate.health.Health h = indicator.health();
            String code = h.getStatus().getCode();
            return switch (code) {
                case "UP"   -> Health.up();
                case "DOWN" -> Health.down(name + ": " + h.getDetails());
                default     -> Health.degraded(name + ": " + h.getDetails());
            };
        })
    );

    binder.bindTo(registry);
    return binder;
}
```

The `my-service.health` PVA channel will then reflect the worst-case status
across all Actuator health indicators and update on every poll tick.

### Disabling PVA publishing in tests

Add `pva.enabled=false` to a test-specific property source.  The registry bean
is still created (avoiding application-context failures), but the PVA server is
not started and no channels are updated:

```java
@SpringBootTest
@TestPropertySource(properties = "pva.enabled=false")
class MyServiceIntegrationTest {
    // ...
}
```

Or in a dedicated `application-test.properties`:

```properties
pva.enabled=false
```

activated with `@ActiveProfiles("test")`.
