# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

---

## [Unreleased]

### Added
- Community hygiene files: `CONTRIBUTING.md`, `SECURITY.md`, `CHANGELOG.md`.
- GitHub issue templates (bug report, feature request) and pull-request template.
- README: Spring Boot integration guide with `@Configuration` examples,
  `application.properties` reference, Spring Actuator health bridging,
  and test-environment disable pattern.
- README: `PvaServiceBinder` usage section, PV naming strategy reference,
  and meter-type → PV-type mapping table.
- README: `PvaMeterRegistryConfig` customisation section with supported
  property keys and anonymous-class override examples.

---

## [1.0.0] - Unreleased (in development)

### Added
- `PvaMeterRegistry` — a Micrometer `MeterRegistry` that publishes metrics as
  live EPICS PV Access (PVA) process variables.
- `PvaMeterRegistryConfig` — configuration interface with sensible defaults
  (server port `5075`, step interval `1 minute`).
- Support for all core Micrometer meter types: `Counter`, `Gauge`, `Timer`,
  `DistributionSummary`, `LongTaskTimer`, `FunctionCounter`, `FunctionTimer`,
  and `TimeGauge`.
- Optional constructor argument to reuse an existing `PVAServer` instance
  instead of starting a new one.
- GitHub Actions CI pipeline (Java 17 & 21 matrix).
- GitHub Actions CodeQL security analysis.
- GitHub Actions release workflow (deploy to Nexus on version tag).
- Dependabot configuration for Maven and GitHub Actions dependency updates.
- JaCoCo coverage enforcement (≥ 80 % instruction coverage).

[Unreleased]: https://github.com/jacomago/micrometer-registry-pva/compare/HEAD...HEAD
[1.0.0]: https://github.com/jacomago/micrometer-registry-pva/releases/tag/v1.0.0
