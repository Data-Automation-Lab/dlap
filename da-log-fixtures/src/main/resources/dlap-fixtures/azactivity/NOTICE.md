# Third-party notices: azactivity seed cases

Cases in this directory whose `meta.yaml` says `origin: public-dataset` are anonymised excerpts of the
repositories below. Each case names its repository, commit, licence and date in `meta.yaml`;
`seeds.yaml` pins the same values and lists every selected record, so the import can be repeated.
Identifiers (tenant, subscription and object GUIDs, e-mail addresses, IP addresses) are replaced by
`com.dlap.fixtures.anon.Anonymiser`; records are wrapped in `{"records": [...]}` and re-indented. Those
are the only changes.

## open-telemetry/opentelemetry-collector-contrib

- Repository: https://github.com/open-telemetry/opentelemetry-collector-contrib
- Path: `extension/encoding/azureencodingextension/internal/unmarshaler/logs/testdata/activitylogs`
- Commit: f9fdf9e8059dfd81c1aca3b4d4a9797d4182a09d (2026-10-19), retrieved 2026-10-19
- Licence: Apache-2.0 (https://www.apache.org/licenses/LICENSE-2.0)
- The repository's NOTICE file concerns other components (gopsutil, gRPC-Go) and does not apply to
  these test files.

What the data is: one diagnostic export per Activity Log category, eight of the nine. Each file has an
independent expected output (`<name>_expected.yaml`) in the same directory; it is not copied here. The
ninth, `pim-log.json`, is not used: its operation names are Entra directory events with spaces, not ARM
operations.

## panther-labs/panther-analysis

- Repository: https://github.com/panther-labs/panther-analysis
- Commit: 4d78f77d545e474064f9921924d54e1e368ceffd (2026-10-09), retrieved 2026-10-19
- Licence: Apache-2.0 (https://www.apache.org/licenses/LICENSE-2.0)
- Copyright 2023 Panther Labs, Inc.
- The repository has no NOTICE file.

What the data is: sample log records inside detection-rule tests. They are marked DERIVED: the rule
authors trimmed them by hand, so they show plausible field shapes but are not independent proof of what
Azure delivers. Changes from the source are listed in each case's `notes`.
