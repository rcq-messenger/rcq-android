# Governance

This document describes how decisions are made in RCQ, who makes them, and how
someone else can gain the ability to make them. It is deliberately short and
deliberately honest.

## Current structure

RCQ has a **single maintainer**: Iaroslav Tager ([@rcq-app](https://github.com/rcq-app)),
who wrote the clients, the backend and the embedded circumvention transport and
currently holds final say on all technical decisions across the
[rcq-messenger](https://github.com/rcq-messenger) organization.

We state this plainly rather than presenting the project as larger than it is.
Users of a privacy tool deserve to know its bus factor, and the mitigations
below are structural rather than aspirational.

## Roles

**Maintainer.** Merges changes, cuts releases, decides protocol direction, and
is the security contact of last resort. Currently one person.

**Contributors.** Anyone who opens an issue or a pull request. No CLA is
required. Contributions are accepted under the repository's existing license
(AGPL-3.0 for this repository) and contributors keep their copyright.

**Island operators.** People who run their own RCQ server instance from
[rcq-server-ref](https://github.com/rcq-messenger/rcq-server-ref). They are
independent: they choose their own policies, their own uptime, and their own
users. They are listed in
[rcq-servers](https://github.com/rcq-messenger/rcq-servers) at their request and
are under no obligation to the maintainer. Operators can and do influence the
roadmap by reporting what breaks in the field.

## How decisions are made

Ordinary changes are decided in the open, in issues and pull requests. The
maintainer merges.

Three categories get a higher bar:

1. **Protocol changes.** Anything that alters the wire format, the cryptographic
   envelope or the federation records must be reflected in
   [rcq-spec](https://github.com/rcq-messenger/rcq-spec) in the same cycle. The
   specification is the contract with independent implementers, so it is not
   allowed to drift behind the code.
2. **Cryptographic and transport code.** Changes to libsignal integration,
   sealed sender handling, key storage, or the circumvention transport are
   reviewed against the threat model before merge, not after. When an external
   audit is in progress, these areas are frozen for its duration.
3. **Anything that weakens a user-visible security property.** Not done
   silently. If a guarantee changes, it is documented in the release notes and,
   where relevant, in [SECURITY.md](SECURITY.md).

## Becoming a committer

There is no committee vote and no bureaucracy. The path is:

1. Land a few pull requests that show judgement, not just volume.
2. Ask for commit access to a specific area (for example the Android client),
   or be offered it.
3. Commit access to cryptographic, transport and release-signing paths is
   granted separately and later, because a compromise there is not recoverable
   by a revert.

The project actively wants this to happen. More maintainers is the direct fix
for the weakness stated at the top of this document.

## Releases

Android releases are published as GitHub Releases and installed directly as
APKs by many users, so release integrity is a security property, not a
convenience. Release builds are reproducible: the published APK can be rebuilt
from source on a documented toolchain and compared byte for byte, excluding the
signature. See [`docs/REPRODUCIBLE-BUILDS.md`](docs/REPRODUCIBLE-BUILDS.md).

The release signing key is held by the maintainer. This is a real
centralization point and is named here rather than hidden: a fork would have to
ship under its own key, and users would verify it the same way they can verify
ours, by rebuilding from source.

## Security decisions

Vulnerability reports go through [SECURITY.md](SECURITY.md), never through
public issues. The maintainer is the security lead. Response commitments,
scope, safe harbor and coordinated disclosure terms are all documented there.

Audit findings are published together with a remediation log. RCQ does not sign
non-disclosure agreements covering security results.

## Continuity

If the maintainer becomes unavailable, the project is designed to be picked up
rather than to die quietly:

- Everything a user runs is AGPL-3.0 and public: clients, reference server,
  relay code.
- The protocol is published as an open specification, so a third party can
  build a compatible implementation without access to this codebase.
- The server is self-hostable, and independent instances already run without any
  dependency on the maintainer's infrastructure.
- Android builds are reproducible, so a successor's binaries can be verified by
  users who have no reason to trust the successor personally.

## Conduct

Be technically direct and personally civil. Harassment, discrimination, and
attempts to pressure maintainers or operators into weakening user security are
grounds for removal from project spaces. Report conduct problems to
security@rcq.app if no other channel is appropriate.
