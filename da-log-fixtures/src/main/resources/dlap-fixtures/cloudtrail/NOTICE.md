# Third-party notices: cloudtrail seed cases

Cases in this directory whose `meta.yaml` says `origin: public-dataset` are anonymised excerpts of the
repositories below. Each case names its repository, commit, licence and date in `meta.yaml`;
`seeds.yaml` pins the same values and lists every selected record, so the import can be repeated.
Identifiers (account numbers, access keys, user names, IP addresses, e-mail addresses) are replaced by
`com.dlap.fixtures.anon.Anonymiser`; records are re-indented and wrapped in the delivery envelope.
Those are the only changes. Cases that are not listed in `seeds.yaml` are synthetic and need no notice.

## invictus-ir/aws_dataset

- Repository: https://github.com/invictus-ir/aws_dataset
- Commit: 4667aa9c26330bc3317ed8bd526f4b83cff13602 (2023-07-12), retrieved 2026-10-19
- Licence: MIT
- Copyright (c) 2023 Invictus Incident Response

```
MIT License

Copyright (c) 2023 Invictus Incident Response

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

What the data is: real management-event trail files, `eventVersion` 1.08 and 1.09, recorded on
2023-07-10 while Stratus Red Team techniques ran in a lab account. The files hold no S3 data events.
The real account number appears in the source file names and in the records; neither is kept.

## panther-labs/panther-analysis

- Repository: https://github.com/panther-labs/panther-analysis
- Commit: 4d78f77d545e474064f9921924d54e1e368ceffd (2026-10-09), retrieved 2026-10-19
- Licence: Apache-2.0 (https://www.apache.org/licenses/LICENSE-2.0)
- Copyright 2023 Panther Labs, Inc.
- The repository has no NOTICE file.

What the data is: sample log records inside detection-rule tests. They are marked DERIVED. The rule
authors trimmed them by hand and they may be adapted from AWS documentation, so they show plausible
field shapes but are not independent proof of what AWS delivers. Samples whose `eventTime` is in
Panther's own `yyyy-mm-dd hh:mm:ss` form went through its pipeline and are not imported, which is why
no `eventVersion` 1.10 case exists. Changes from the source are listed in each case's `notes`.
