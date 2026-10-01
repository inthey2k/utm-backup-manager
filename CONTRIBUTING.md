# Contributing to UTM Backup Manager

Thank you for your interest in contributing to UTM Backup Manager.

Contributions are welcome, including bug reports, documentation improvements,
tests, translations, and code changes.

## Before you start

For larger changes, please open an issue first so the proposed approach can be
discussed before significant work is invested.

Small bug fixes, documentation corrections, and minor improvements can be
submitted directly as pull requests.

## Development requirements

- Java 21
- Maven
- macOS with UTM for UTM-specific integration testing

Run the complete test suite before submitting changes:

```bash
mvn clean test
```

All tests should pass.

## Code style

Please keep changes focused and avoid unrelated refactoring.

Code, comments, commit messages, and documentation should preferably be written
in English.

New functionality should include appropriate tests where practical.

## Pull requests

A pull request should:

- describe what was changed
- explain why the change is useful
- mention any relevant issue
- keep the scope as small as reasonably possible
- pass the existing test suite

Please do not include machine-specific configuration, local filesystem paths,
credentials, or personal data.

## Configuration

Local configuration belongs in `application.properties`.

Do not commit `application.properties`.

Use `application.properties.example` as the template for configuration changes.

## Licensing

By submitting a contribution, you agree that your contribution may be
distributed under the same license as this project:

GNU General Public License version 3 (GPL-3.0-only).

You certify that you have the right to submit the contribution.

## Questions

If something is unclear, please open an issue before starting larger changes.
