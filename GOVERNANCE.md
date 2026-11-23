# DLAP Project Governance

DLAP (Data Lifecycle Automation Platform) is an independent open-source
project under the Apache License 2.0. **It is not an Apache Software
Foundation (ASF) project, it is not in the Apache Incubator, and no
incubation proposal has been filed.** A proposal may follow if a community
forms; until then every mention of incubation, a PPMC or ASF infrastructure
below describes a target, not the present.

This document describes how the project is governed **today** and how that
governance would evolve as a community forms. It is intentionally honest
about the project's current stage: DLAP was built and released by a single
founder (Data Automation Labs, DALab). See ADR-0012 in
[docs/DECISIONS.md](docs/DECISIONS.md).

We model our target governance on the [Apache Way](https://www.apache.org/theapacheway/):
merit-based membership, community over code, consensus decision-making, and
public, archived communication.

---

## 1. Current state (pre-incubation)

DLAP today has a **single maintainer** (the founder/donor). There is no
Project Management Committee (PMC) and no independent committer body yet:
those form during incubation, once a community exists. Until then:

- The maintainer acts as the sole committer and release manager.
- Decisions are made and recorded transparently (ADRs; see §4).
- Contributions are welcome and reviewed against
  [CONTRIBUTING.md](CONTRIBUTING.md); merit accrues from the first patch.

The roles and processes below describe the **target model** we adopt as
contributors arrive. We do not pretend to a governance structure larger
than the project's actual community - that honesty is itself an Apache
value (the "excessive fascination with the Apache brand" anti-pattern).

---

## 2. Roles

DLAP maps to the standard ASF role model (Apache Incubator variant).

### Contributor

Anyone who contributes - code, documentation, tests, triage, design
review, or community support. No prior status is required; opening an
issue or a pull request makes you a contributor. Contributors do not need
commit access; a committer merges reviewed work on their behalf.

### Committer

A contributor recognized for **sustained, quality contribution** and given
write access to the repository (and, under the ASF, a signed Individual
Contributor License Agreement on file). Committers:

- Merge their own and others' reviewed pull requests.
- Are expected to uphold coding standards ([CONTRIBUTING.md](CONTRIBUTING.md),
  [CLAUDE.md](CLAUDE.md)) and the [Code of Conduct](CODE_OF_CONDUCT.md).
- Vote on releases and technical decisions (binding once the PMC exists;
  see §5).

Committer status is **merit-based and permanent** (it is not revoked for
inactivity). See §3 for how to become one.

### PPMC / PMC member

During incubation, the **Podling Project Management Committee (PPMC)**
governs the project (it becomes the **PMC** on graduation to a Top-Level
Project). PMC members:

- Are responsible for the project's technical direction, releases, and
  community health.
- Cast **binding votes** on releases and on adding new committers/PMC
  members.
- Have oversight of the project's legal and trademark obligations to the
  ASF.

At incubation, the initial committers named in the incubation proposal
(not yet written for publication) would seed the PPMC.

### Mentors and Champion (incubation only)

The ASF Incubator assigns **Mentors** (existing ASF members) who guide the
podling through the Apache Way, and a **Champion** who shepherds the
proposal. These are TBD until the proposal is filed and accepted.

---

## 3. Becoming a committer

There is no application form. Committership is **earned and offered**, not
requested. The path:

1. **Contribute consistently.** Land meaningful pull requests, help triage
   issues, review others' work, participate in design discussions on the
   public channels.
2. **Demonstrate the Apache Way.** Collaborate in public, respect
   consensus, sign off your commits (DCO - see [CONTRIBUTING.md](CONTRIBUTING.md)),
   and hold to the [Code of Conduct](CODE_OF_CONDUCT.md).
3. **Be nominated.** Any existing committer (today, the maintainer; later,
   the PPMC) proposes you on the private project list. The PPMC votes by
   lazy consensus (§5). Once incubated, the standard ASF committer-vote and
   ICLA process apply.

The bar is quality and trust, not volume. Depth of judgment, good faith,
and reliable review count as much as code.

---

## 4. Decision-making

### Lazy consensus

Most decisions are made by **lazy consensus**: a proposal is made in the
open (issue, pull request, or mailing-list thread), and if no one objects
within a reasonable window (typically **72 hours** for non-trivial
changes), it is considered approved. Silence is assent. Anyone may request
more time.

Routine changes (bug fixes, docs, tests that pass review) proceed under
lazy consensus via ordinary pull-request review - one committer approval is
sufficient today; the ASF norm is at least one +1 from a committer other
than the author and no unresolved -1.

### Consensus and voting

Significant decisions - releases, adding committers/PMC members, changes to
governance, or contested technical direction - use an explicit **vote**:

- `+1`: agree / in favor
- `0`: abstain / no strong opinion
- `-1`: disagree; **must include a technical rationale** (a bare -1 is not
  a veto)

Per Apache convention, a **-1 on a code/technical change is a veto** that
must be justified and can be overridden only by addressing the concern, not
by out-voting it. Release votes require at least **three binding +1s** and
more +1s than -1s (majority), per ASF release policy.

### Architecture Decision Records (ADRs)

Every architectural or status-bearing decision is recorded as an **ADR** in
[docs/DECISIONS.md](docs/DECISIONS.md) - append-only, newest first, one
decision per entry (context, decision, consequences, status). This is the
project's durable decision log; it predates and will outlive any single
maintainer. Reversing a decision means adding a new ADR that supersedes the
old one, never editing history. Unresolved questions requiring human input
live in GitHub issues (see [docs/OPEN_QUESTIONS.md](docs/OPEN_QUESTIONS.md))
until an ADR resolves them.

Contributors proposing a substantial design change should open an ADR
(or an issue that will become one) so the rationale is captured before the
code lands.

---

## 5. Releases

DLAP follows semantic versioning; the changelog starts at 0.1.0
([CHANGELOG.md](CHANGELOG.md)). The release process:

1. **Propose** a release on the project list, naming the candidate commit
   and version.
2. **Cut** a release candidate: tag, build the source and binary
   artifacts, and stage them.
3. **Vote.** A release requires at least three binding +1 votes and a
   majority (ASF policy). During incubation, the PPMC vote is followed by
   an **Incubator PMC (IPMC)** vote - releases from a podling carry the
   `-incubating` suffix and an IPMC sign-off.
4. **Verify** the artifacts: license headers, `LICENSE`/`NOTICE`
   correctness, signatures/checksums, and that a fresh clone builds and the
   quickstart works (see the launch checklist in [CLAUDE.md](CLAUDE.md) §10
   and [docs/getting-started.md](docs/getting-started.md)).
5. **Release** and announce.

Pre-incubation, the maintainer is the release manager and the above vote
collapses to a documented decision; the mechanics (source release, license
verification, tagging) are practiced now so they are routine at incubation.

---

## 6. Communication

The Apache Way requires that project decisions happen **in public and are
archived** - "if it didn't happen on the list, it didn't happen." As the
community forms, DLAP will stand up ASF-provided mailing lists
(`dev@`, `users@`, `private@`) and use the public issue tracker and
discussions in the interim.

Until the lists exist, design discussion happens on GitHub Issues and
Discussions, and decisions are captured in ADRs so the record is durable.

---

## 7. Code of Conduct

All participation is governed by the project
[Code of Conduct](CODE_OF_CONDUCT.md) (Contributor Covenant 2.1). Community
leaders are responsible for enforcement; reports go to the contact listed
there.

---

## 8. Amending this document

Changes to governance are themselves decided by the process in §4:
proposed in the open, recorded as an ADR, and, once the PPMC exists,
approved by PPMC vote. This document will be revised at incubation to
reflect the actual PPMC roster and ASF infrastructure.
