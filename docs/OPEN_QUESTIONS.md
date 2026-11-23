# Open questions

Open items for DLAP are tracked as GitHub issues:
[github.com/Data-Automation-Lab/dlap/issues](https://github.com/Data-Automation-Lab/dlap/issues).

## What the `Q-nnn` ids mean

Code comments, tests, ADRs and guides cite ids such as `Q-459`. They come from the
question log that was kept while DLAP was developed in a private repository. That log is
a working document and is not published. Where a question led to a decision, the decision
is recorded as an ADR in [DECISIONS.md](DECISIONS.md), and the ADR is the reference to use.
If a `Q-nnn` id matters to you and no ADR explains it, open an issue and ask.

Package ids (for example `SEC-07` or `DEMO-01`), wave numbers and short commit ids in the
docs come from the same pre-release history. The commits they name are not in this
repository, which started from a single fresh commit.

## Known limits

The limits of this release are listed in
[guides/deployment.md](guides/deployment.md) and in the Status note of the
[README](../README.md).

## Switched-off tests

A test that is switched off with `@Disabled` needs an open entry in this file that names
its class and says who turns it back on (`scripts/ci-guards/pre/disabled-tests-tracked.sh`
enforces it). Add the entry below as a second-level heading, for example
`## Q-930 - SomeTest is off [OPEN]`. There are none today.
