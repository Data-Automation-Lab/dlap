# Third-party notices: azstoragelog seed cases

Cases in this directory whose `meta.yaml` says `origin: public-dataset` are anonymised excerpts of the
repository below. Each case names its repository, commit, licence and date in `meta.yaml`;
`seeds.yaml` pins the same values, so the import can be repeated. Identifiers (tenant, subscription and
object GUIDs, IP addresses) are replaced by `com.dlap.fixtures.anon.Anonymiser`, and the JSON is
re-indented. Those are the only changes.

## open-telemetry/opentelemetry-collector-contrib

- Repository: https://github.com/open-telemetry/opentelemetry-collector-contrib
- Path: `extension/encoding/azureencodingextension/internal/unmarshaler/logs/testdata/storage`
- Commit: f9fdf9e8059dfd81c1aca3b4d4a9797d4182a09d (2026-10-19), retrieved 2026-10-19
- Licence: Apache-2.0 (https://www.apache.org/licenses/LICENSE-2.0)
- The repository's NOTICE file concerns other components (gopsutil, gRPC-Go) and does not apply to
  this test file.

What the data is: one `ListBlobs` record that returned HTTP 500 with `authorization.result: Denied` and
`reason: NoPolicy`, a delegation SAS and TLS 1.2. It carries `schemaVersion` 2.1, so it is filed under
2.1; Microsoft's reference lists only schema 1.0. The file has an independent expected output
(`storage-read-log_expected.yaml`) in the same directory; it is not copied here.
