# Contributing to UTM Backup Manager

Thank you for your interest in contributing to UTM Backup Manager.

Contributions are welcome, including bug reports, documentation improvements,
tests, translations, and code changes.

## Before you start

For larger changes, please open an issue first so the proposed approach can be
discussed before significant work is invested.

Issues can be reported at: https://jira.fjordkommission.de

Small bug fixes, documentation corrections, and minor improvements can be
submitted directly as pull requests.

## Development requirements

- Java 21
- Maven

Run the complete test suite before submitting changes:

```bash
mvn clean verify
```

All tests should pass.

## Code style

Please keep changes focused and avoid unrelated refactoring.

Code, comments, JavaDoc, commit messages, and technical 
documentation must be written in English.

New functionality should include appropriate tests where practical.

For more detailed instructions on how to create a new hypervisor interface, 
please refer to the [Hypervisor Integration Guide](docs/hypervisor-provider-integration-guide.md) 

## Pull requests

A pull request should:

- describe what was changed
- explain why the change is useful
- mention any relevant issue
- keep the scope as small as reasonably possible
- add or update tests for changed behavior where practical
- update affected documentation
- pass `mvn clean verify`

Please do not include machine-specific configuration, local filesystem paths,
credentials, or personal data.

## Configuration

Local configuration belongs in `application.properties`.

Do not commit `application.properties`.

Use `application.properties.example` as the template for configuration changes.

> **Important:** When adding or changing configuration properties, also update
`application.properties.example` accordingly.

## Licensing

By submitting a contribution, you agree that your contribution may be
distributed under the same license as this project:

GNU General Public License version 3 (GPL-3.0-only).

You certify that you have the right to submit the contribution.

## Questions

If something is unclear, please open an issue before starting larger changes.
