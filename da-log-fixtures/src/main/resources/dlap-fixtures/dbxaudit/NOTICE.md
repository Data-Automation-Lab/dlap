# Third-party notices: dbxaudit seed cases

Cases in this directory whose `meta.yaml` says `origin: public-dataset` are anonymised excerpts of the
repository below. Each case names its repository, commit, licence and date in `meta.yaml`;
`seeds.yaml` pins the same values, so the import can be repeated. Identifiers (e-mail addresses, IP
addresses) are replaced by `com.dlap.fixtures.anon.Anonymiser`, and records are written as JSON Lines.
Those are the only changes.

## panther-labs/panther-analysis

- Repository: https://github.com/panther-labs/panther-analysis
- Path: `rules/databricks_rules`
- Commit: 4d78f77d545e474064f9921924d54e1e368ceffd (2026-10-09), retrieved 2026-10-19
- Licence: Apache-2.0 (https://www.apache.org/licenses/LICENSE-2.0)
- Copyright 2023 Panther Labs, Inc.
- The repository has no NOTICE file.

What the data is: one sample record from a detection-rule test, marked DERIVED. It was written by hand
with placeholder identifiers, so it shows the delivered field names but is not independent proof of what
Databricks delivers. Of the 162 Databricks samples in the repository only this one carries `version`,
`requestId` and an integer `timestamp`; the others are abbreviated and are not imported. Real cases need
the LIVE-03 capture.
