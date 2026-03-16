# Contributing to micrometer-registry-pva

Thank you for your interest in contributing! This document covers how to report
bugs, propose features, and submit pull requests.

## Table of contents

- [Code of conduct](#code-of-conduct)
- [Reporting bugs](#reporting-bugs)
- [Suggesting features](#suggesting-features)
- [Development setup](#development-setup)
- [Coding standards](#coding-standards)
- [Submitting a pull request](#submitting-a-pull-request)

---

## Code of conduct

Please be respectful and constructive in all interactions. We follow the
[Contributor Covenant](https://www.contributor-covenant.org/version/2/1/code_of_conduct/)
code of conduct.

---

## Reporting bugs

Before opening a new issue, please search the
[existing issues](https://github.com/jacomago/micrometer-registry-pva/issues)
to avoid duplicates.

When filing a bug report use the **Bug report** issue template and include:

- A short, descriptive title.
- Steps to reproduce the problem.
- Expected vs. actual behaviour.
- Java version (`java -version`) and OS.
- Any relevant log output or stack traces.

---

## Suggesting features

Open a **Feature request** issue and describe:

- The problem you are trying to solve.
- Your proposed solution and any alternatives you considered.
- Whether you are willing to implement it yourself.

---

## Development setup

### Prerequisites

| Tool | Minimum version |
|------|----------------|
| JDK  | 17             |
| Maven | 3.9           |

### Build & test

```bash
# Compile, run tests, and install to local Maven cache
mvn install

# Run only the tests
mvn test

# Run tests with coverage report (target/site/jacoco/)
mvn verify
```

The build enforces **80 % instruction coverage**; the `verify` goal will fail
if coverage drops below that threshold.

### IDE setup

The project is a standard single-module Maven project.  Import it as an
existing Maven project in IntelliJ IDEA, Eclipse, or VS Code with the Java
Extension Pack.

---

## Coding standards

- Target **Java 17** language level.
- Follow the existing code style (no trailing whitespace, 4-space indentation).
- Write unit tests for all new public API.
- Keep commits small and focused; one logical change per commit.
- Write clear commit messages in the imperative mood
  (`Add support for X`, not `Added support for X`).

---

## Submitting a pull request

1. Fork the repository and create a feature branch from `main`:

   ```bash
   git checkout -b feat/my-feature
   ```

2. Make your changes and ensure the full build passes:

   ```bash
   mvn verify
   ```

3. Push your branch and open a pull request against `main`.

4. Fill in the PR template, linking any related issues.

5. A maintainer will review your PR.  Please respond to feedback promptly and
   squash or address requested changes.

---

## Questions?

Open a [Discussion](https://github.com/jacomago/micrometer-registry-pva/discussions)
or comment on an existing issue.
