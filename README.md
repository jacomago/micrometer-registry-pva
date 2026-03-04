# micrometer-registry-pva

A [Micrometer](https://micrometer.io/) `MeterRegistry` backed by an
[EPICS PV Access (PVA)](https://github.com/epics-base/pvAccessJava) server.
Any Java application instrumented with Micrometer can publish all its metrics
as live PVA process variables accessible to EPICS clients (e.g. Phoebus).

---

## Building & installing locally

```bash
mvn install
```

This compiles, tests, and installs the artifact to `~/.m2/repository`.

### Deploying to the Phoebus Nexus/Artifactory (future)

Once the repository URL is confirmed, run:

```bash
mvn deploy -P phoebus-releases -Dphoebus.nexus.url=https://nexus.example.org
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
<repositories>
  <repository>
    <id>phoebus-releases</id>
    <name>Phoebus Releases</name>
    <url>https://nexus.example.org/repository/releases/</url>   <!-- replace with confirmed URL -->
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
        url  = 'https://nexus.example.org/repository/releases/'   // replace with confirmed URL
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
