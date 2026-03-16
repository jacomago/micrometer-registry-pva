# Security policy

## Supported versions

| Version         | Supported |
|-----------------|-----------|
| 1.x (main)      | Yes       |

Older tags / branches receive no security fixes.  Please upgrade to the latest
release before reporting a vulnerability.

## Reporting a vulnerability

**Do not open a public GitHub issue for security vulnerabilities.**

Please report security issues privately via
[GitHub's private vulnerability reporting](https://github.com/jacomago/micrometer-registry-pva/security/advisories/new).

Include as much of the following as you can:

- A description of the vulnerability and its potential impact.
- Steps to reproduce or a minimal proof-of-concept.
- Affected versions (check `pom.xml` for the current version).
- Any suggested mitigations you are aware of.

You should receive an acknowledgement within **5 business days**.  We aim to
publish a fix and a CVE advisory within **90 days** of the initial report,
sooner when possible.

## Scope

This library starts an embedded EPICS PVA server on the local host.  The
following are **in scope**:

- Unauthenticated remote access to process variables beyond what PVA normally
  allows.
- Denial-of-service conditions caused by malformed client requests.
- Information disclosure through exported metric names or values.

The following are **out of scope**:

- Vulnerabilities in upstream libraries (Micrometer, Phoebus core-pva).  Report
  those to the respective projects.
- Issues that require physical access to the host machine.
