# Reproducible builds (Android)

RCQ Android release APKs are **reproducible**: anyone can build a published
release from source on the documented toolchain and verify that the official APK
matches, byte-for-byte, except for the signature. This lets you confirm the APK
you install was built from exactly this public source — no hidden code.

This is meaningful for RCQ specifically: it's distributed as a **sideloaded APK**
(GitHub releases + the in-app updater) to users under censorship, so "trust the
binary" must be verifiable, not assumed.

> Reproducible builds prove the published binary corresponds to the public
> source. They do **not** by themselves prove the source is free of bugs or
> backdoors — that's what the source being open + audited is for (see
> `../SECURITY.md` and `RCQ/docs/audit-scope.md`).

## What makes the build deterministic

The release build is configured for reproducibility (`app/build.gradle.kts`):
- **No code minification** (`isMinifyEnabled = false`) and **no resource
  shrinker** (`isShrinkResources = false`) — removes R8 mapping/ordering and
  shrinker output as non-determinism sources.
- **No embedded dependency-metadata blob**
  (`dependenciesInfo { includeInApk = false; includeInBundle = false }`) — AGP
  otherwise embeds a version-stamped blob in the APK signing block.
- **No embedded VCS info** (`vcsInfo { include = false }`) — AGP 8.3+ otherwise
  writes `META-INF/version-control-info.textproto` with the git commit, which
  changes every commit.
- Modern AGP/Gradle zero ZIP entry timestamps and emit deterministic entry order
  and file permissions.
- The release is **RSA**-signed; RSASSA-PKCS#1 v1.5 is deterministic (same key +
  same content → identical signature), so even the signed APK is reproducible by
  the holder of the key. (Third parties don't have the key — see verification.)

We verified determinism directly: **two independent clean `assembleRelease`
builds produce byte-for-byte identical APKs (identical SHA-256) for every ABI.**

## Pinned toolchain

Reproducibility holds on this exact toolchain (a different JDK vendor/version can
legitimately change the bytecode):

| Component | Version |
|---|---|
| Gradle | 9.3.1 (via the committed `gradle-wrapper`) |
| Android Gradle Plugin | 9.1.1 |
| Kotlin | 2.2.10 |
| JDK | JetBrains Runtime (JBR) **21.0.9** — the one bundled with Android Studio |
| compileSdk / targetSdk | 36 |
| minSdk | 26 |
| Build environment | `LANG=C`, `TZ=UTC` |

A pinned Docker image (the most robust way for a third party to match the JDK
exactly, as Signal/Molly do) is the recommended next step; until then, use the
JBR 21.0.9 from the matching Android Studio.

## Build it yourself

```bash
git clone https://github.com/rcq-messenger/rcq-android
cd rcq-android
git checkout <release-tag>            # e.g. v0.50

export JAVA_HOME="<path to JBR 21.0.9>"   # Android Studio: .../Android Studio.app/Contents/jbr/Contents/Home
export LANG=C TZ=UTC
./gradlew --no-daemon --no-build-cache clean :app:assembleRelease
# outputs: app/build/outputs/apk/release/app-<abi>-release.apk (+ app-universal-release.apk)
```

Build from the **Gradle CLI**, not Android Studio's "Build APK" action (it has
historically reordered ZIP entries). Without the release keystore, the build
falls back to a debug signature — that's fine, verification ignores the
signature.

## Verify a published APK matches

A third party doesn't have our private signing key, so verification compares
**everything except the signature**. Each GitHub release also publishes the
per-ABI SHA-256 of the official APKs.

### Recommended: `apksigcopier` (the F-Droid / Reproducible-Builds method)

Grafts the official signature onto your locally-built APK and confirms it still
verifies — which only succeeds if the contents are byte-identical:

```bash
pip install apksigcopier        # or: apt install apksigcopier
# PUBLISHED = the official APK from the GitHub release; BUILT = your local build
apksigcopier compare PUBLISHED.apk --unsigned BUILT.apk \
  && echo "REPRODUCIBLE: contents byte-identical to the published APK"
# and confirm which key signed the official APK you trust:
apksigner verify --print-certs PUBLISHED.apk
```

### Alternative: content diff

`./tools/verify-apk.sh <published.apk> <built.apk>` (in this repo) compares every
ZIP entry, ignoring only the signature files (`META-INF/*.SF`, `*.RSA`, `*.EC`,
`MANIFEST.MF`) and the APK Signing Block. If something mismatches, `diffoscope
published.apk built.apk` shows exactly which entry differs.

## Honest limits

1. The **signature** is never reproducible by a third party (it needs our private
   key). Verification proves the *contents* match, not the signature.
2. Reproducible **only on the documented toolchain** — pin the JDK especially.
3. RB proves the binary matches the source; it is **not** a security audit.
4. If we ever need to exclude an entry (e.g. an AGP `resources.arsc` quirk), it
   will be listed in `tools/verify-apk.sh` with the reason, so the exclusion is
   auditable.

## The official release certificate

The official APKs are signed by our release key. Confirm the fingerprint with
`apksigner verify --print-certs` matches the one published in the release notes
before trusting an APK. (Installing an APK signed by a *different* key over an
existing install is rejected by Android — another integrity check in your favor.)
