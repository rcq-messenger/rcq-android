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
- **Native libraries are never stripped**
  (`packaging { jniLibs { keepDebugSymbols += "**/*.so" } }`) — AGP otherwise
  strips them only when the NDK version it prefers happens to be installed, so
  the same source would give a different APK on a machine that has it.
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
./gradlew --no-daemon --no-build-cache clean :app:assembleSideloadRelease
# outputs: app/build/outputs/apk/sideload/release/app-sideload-<abi>-release.apk
#          (+ app-sideload-universal-release.apk)
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

## What the prebuilt parts are

Almost all of RCQ Android is compiled from this repository when you run Gradle.
A few libraries arrive already compiled, as in nearly every Android app.
"Prebuilt" (or "vendored") means only that: compiled elsewhere and used as is.
It does not mean modified.

- **libsignal** — the encryption (Double Ratchet, PQXDH). This is
  `org.signal:libsignal-android` 0.86.5: the official package that Signal builds
  from [signalapp/libsignal](https://github.com/signalapp/libsignal) and
  publishes on Maven Central. Gradle downloads it by that exact version
  (`gradle/libs.versions.toml`), and Maven Central never lets a published
  version be replaced. We neither rebuild nor change it: it is the same file
  every app on that version gets.
- **rcqbox** — the censorship-bypass transport. Upstream
  [sing-box](https://github.com/SagerNet/sing-box) at a pinned commit, unmodified,
  plus our wrapper `tools/rcqbox/rcqbox.go`: 80 lines that give the app four
  calls (create, start, stop, is-running). It is the one compiled library
  committed to this repository (`app/libs/rcqbox.aar`; the only other binary is
  Gradle's standard `gradle-wrapper.jar`), because Gradle cannot build Go.
  Anyone can rebuild it from those public sources and get the same bytes; see
  the next section.
- The other libraries (AndroidX, OkHttp, SQLCipher, WebRTC and the rest, all
  listed with their licenses in `NOTICE`) are official releases from Maven
  Central, taken the same way as libsignal. SQLCipher and WebRTC also carry
  native code.

## Verifying rcqbox.aar (sing-box)

The APK check above proves the published APK contains exactly the
`app/libs/rcqbox.aar` of its release tag. This check covers the other half: that
the `.aar` is what its public sources build into.

| Input | |
|---|---|
| sing-box | [SagerNet/sing-box](https://github.com/SagerNet/sing-box) at commit `82e84f950cab3b215f4cfb4021a3f6ad0ec78fd1`, unmodified |
| Wrapper | `tools/rcqbox/rcqbox.go` (sha256 `0dea163f12982de93b987317a08928ccc62feb0fd803e748017f1f7065073083`) |
| Recipe | `tools/build-rcqbox.sh` |

The script checks this toolchain and stops on any mismatch:

| Component | Version |
|---|---|
| Go | 1.26.3 (the official download and Homebrew's build give the same bytes) |
| gomobile, gobind | `github.com/sagernet/gomobile` v0.1.12 — the script installs it itself, through the Go module proxy, checked against sum.golang.org |
| Android NDK | 27.2.12479018 (r27c) |
| JDK | `javac` 21.0.9 (the JBR bundled with Android Studio will do) |
| Android SDK | any `platforms/android-26` or newer; it is only javac's bootclasspath (android-35 and android-36 give identical classes) |

```bash
git clone https://github.com/rcq-messenger/rcq-android
cd rcq-android
git checkout <release-tag>
export JAVA_HOME="<JDK 21.0.9>" ANDROID_HOME="<Android SDK>"   # macOS defaults are built in
./tools/build-rcqbox.sh /tmp/rcqbox-rebuilt.aar
shasum -a 256 app/libs/rcqbox.aar /tmp/rcqbox-rebuilt.aar     # the two must be equal
```

The script fetches sing-box by commit hash (from GitHub, or from a local clone
given as `SING_BOX_SRC`), copies the wrapper in, runs `gomobile bind` with a
fresh Go build cache in a temporary directory, checks the result (16 KB page
alignment on every ABI, no local path inside), and prints the hash of every
part. A cold build takes about a minute on an Apple M-series laptop. It needs
network access for sing-box and its Go dependencies, which `go.sum` pins.

Expected for sing-box `82e84f95` + wrapper `0dea163f`:

| File | SHA-256 |
|---|---|
| `rcqbox.aar` | `e32a12ba4e844bba62dc7297d1f2d42574d94feba3ae9f690992913e02b89b82` |
| `jni/arm64-v8a/libgojni.so` | `2c8465b72212580255fba7c9454e37841837c4065871def163397bd734815830` |
| `jni/armeabi-v7a/libgojni.so` | `ce4fc934fe6c29a250be9b46cf6ec62db77e98d21e9778c8a2a71a2a4a9cbf18` |
| `jni/x86_64/libgojni.so` | `06de20b17b9443c98f0838541dc988b8859875e85b0b555dda73ca3bcc0267de` |
| `classes.jar` | `4d1b53031183e3413dfe0c6d9545a7f840871560b83427c6d578008b572bfff7` |

The APK packages `libgojni.so` untouched, so a published APK can also be checked
directly, without building anything:

```bash
unzip -p PUBLISHED.apk lib/arm64-v8a/libgojni.so | shasum -a 256   # an arm64-v8a or universal APK
```

What makes it deterministic: `-trimpath` (no path of the build machine in the
binary), `-buildvcs=false` (no git state stamped in), a Go environment the
script sets itself (`GOENV=off`, pinned `GOFLAGS`, `GOAMD64`, `GOARM64`, no
`CGO_*` flags), a GNU build id that Go derives from its own content hash, and
gomobile writing the `.aar` in a fixed order with zero timestamps. `go version
-m libgojni.so` shows what Go recorded: `go1.26.3`, `-trimpath=true`, the tags.

We checked it by running the script five times on one Mac (Apple Silicon):
from different working directories into different output paths, with different
temporary directories and two different Go module caches, with sing-box fetched
from GitHub and from a local clone, and once with the official go1.26.3
download instead of Homebrew's Go. All five gave the hashes above, file for
file.

Releases up to 0.211 shipped earlier builds made without `-trimpath`: they
carry the paths of the machine that built them and cannot be matched byte for
byte. The last of them (committed 2026-08-09, `.aar` sha256
`c46dc03e5fe9f21b7cedd10179492ee6a669ebd810a7226fc83ffde279494510`) records the
same sing-box commit, tags and toolchain; its Java classes (`classes.jar`) are
identical to the rebuild's, and its libraries export the same JNI functions.

Not covered yet: a Linux build host (the Go part is host-independent by design
and r27c's clang is one release on every host, so it should match; if you try
it, please tell us either way), and the iOS client's `Rcqbox.xcframework`,
which is built from the same wrapper but not reproducibly
(`tools/rcqbox/README.md`).

## Honest limits

1. The **signature** is never reproducible by a third party (it needs our private
   key). Verification proves the *contents* match, not the signature.
2. Reproducible **only on the documented toolchain** — pin the JDK especially.
3. RB proves the binary matches the source; it is **not** a security audit.
4. If we ever need to exclude an entry (e.g. an AGP `resources.arsc` quirk), it
   will be listed in `tools/verify-apk.sh` with the reason, so the exclusion is
   auditable.
5. The APK check treats the prebuilt parts as files. `rcqbox.aar` has its own
   check (above). libsignal and the other Maven Central libraries are pinned by
   version only: this repository does not pin their hashes yet (Gradle
   dependency verification is not enabled).

## The official release certificate

The official APKs are signed by our release key. Confirm the fingerprint with
`apksigner verify --print-certs` matches the one published in the release notes
before trusting an APK. (Installing an APK signed by a *different* key over an
existing install is rejected by Android — another integrity check in your favor.)
