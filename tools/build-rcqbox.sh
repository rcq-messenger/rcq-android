#!/usr/bin/env bash
#
# build-rcqbox.sh — rebuild app/libs/rcqbox.aar from public sources, so that
# anyone can check the shipped archive against them.
#
# rcqbox.aar is the gomobile binding of the sing-box core that carries the
# censorship-bypass transport (app/src/main/java/app/rcq/android/net/
# SingBoxTransport.kt). Gradle does not build it; it is committed as a binary.
# This script is the whole recipe for that binary, and nothing in it depends on
# the machine it runs on:
#
#   upstream sing-box, fetched at the commit pinned below (no changes)
#   + tools/rcqbox/rcqbox.go, our 80-line wrapper (published here)
#   + gomobile bind on the toolchain pinned below
#
# Two runs, from any directory into any output path, on any machine with that
# toolchain, give the same bytes. The hashes to expect are in
# docs/REPRODUCIBLE-BUILDS.md ("Verifying rcqbox.aar"). Every hash is printed
# at the end, so a run doubles as the verification.
#
# What makes it deterministic:
#   -trimpath         no file system path of this machine ends up in the binary
#                     (module cache, GOROOT, temp dirs, the checkout itself)
#   -buildvcs=false   no git state stamped in (the old build said
#                     vcs.modified=true, which is what the wrapper looked like
#                     to Go when it sat untracked inside the sing-box checkout)
#   GOENV=off, GOFLAGS, GOAMD64, GOARM64, CGO_* ...  pinned below, so a
#                     developer's go env file or shell cannot leak in
#   the GNU build id  Go derives it from its own content hash, so it is stable
#   the .aar zip      gomobile writes entries in a fixed order with the zero
#                     (1980) timestamp, so the archive itself is stable too
#
# ⚠ 16 KB pages. Android 15+ devices can have a 16 KB page size, and a library
# whose PT_LOAD segments are aligned to 4 KB will not load there. Go does not
# set the alignment itself, so we pass it to the NDK linker explicitly. Without
# it the app still starts (the library is loaded when the tunnel comes up, not
# at launch) and the only symptom is that the bypass never works. The script
# checks every ABI with llvm-readelf and fails on anything but 0x4000.
#
# Usage: ./tools/build-rcqbox.sh [output.aar]
#
#   output.aar   default: app/libs/rcqbox.aar
#
# Environment, all optional:
#   SING_BOX_SRC      a local sing-box git repository to take the pinned commit
#                     from instead of GitHub. Only that commit's objects are
#                     used; the repository's working tree, local changes and
#                     untracked files never reach the build.
#   ANDROID_HOME      Android SDK (needs platforms/android-26 or newer)
#   ANDROID_NDK_HOME  default: $ANDROID_HOME/ndk/<pinned NDK version>
#   JAVA_HOME         default on macOS: the JBR bundled with Android Studio
#   RCQBOX_GOCACHE    reuse this Go build cache. Default: a fresh empty one per
#                     run, so a run proves the build rather than the cache.
#   RCQBOX_KEEP_WORK=1                  keep the temporary build directory
#   RCQBOX_ALLOW_MISMATCH=1             build on a toolchain or wrapper that
#                                       differs from the pins below; the
#                                       result will not match the published
#                                       hashes, which is the point of the pins
#
set -euo pipefail

# ── Pinned inputs. Change one and the expected hashes in
#    docs/REPRODUCIBLE-BUILDS.md change with it. ──────────────────────────────
SING_BOX_REPO="https://github.com/SagerNet/sing-box.git"
SING_BOX_COMMIT="82e84f950cab3b215f4cfb4021a3f6ad0ec78fd1"  # 2026-05-20
WRAPPER_SHA256="0dea163f12982de93b987317a08928ccc62feb0fd803e748017f1f7065073083"  # tools/rcqbox/rcqbox.go
GO_VERSION="go1.26.3"
GOMOBILE_MODULE="github.com/sagernet/gomobile"  # sing-box's fork; gomobile + gobind
GOMOBILE_VERSION="v0.1.12"                      # the version sing-box's go.mod requires
NDK_VERSION="27.2.12479018"                     # r27c
JAVAC_VERSION="21.0.9"                          # JBR 21.0.9, same JDK as the APK build
ANDROID_API="26"
TARGETS="android/arm,android/arm64,android/amd64"
TAGS="with_utls,with_quic"
LDFLAGS="-extldflags=-Wl,-z,max-page-size=16384"
ABIS=(armeabi-v7a arm64-v8a x86_64)

REPO="$(cd "$(dirname "$0")/.." && pwd)"
OUT="${1:-$REPO/app/libs/rcqbox.aar}"
case "$OUT" in *.aar) ;; *) echo "output must end in .aar: $OUT" >&2; exit 2 ;; esac
mkdir -p "$(dirname "$OUT")"
OUT="$(cd "$(dirname "$OUT")" && pwd)/$(basename "$OUT")"

say()  { printf '\n==> %s\n' "$*" >&2; }
die()  { printf '\nERROR: %s\n' "$*" >&2; exit 1; }
mismatch() {
  if [ "${RCQBOX_ALLOW_MISMATCH:-}" = 1 ]; then
    printf '\nWARNING: %s\n         RCQBOX_ALLOW_MISMATCH=1: building anyway; the hashes will NOT match the published ones.\n' "$*" >&2
  else
    printf '\nERROR: %s\n       The published hashes hold only for the pinned toolchain and sources.\n       Set RCQBOX_ALLOW_MISMATCH=1 to build anyway (the result will not match).\n' "$*" >&2
    exit 1
  fi
}
sha256() {
  if command -v sha256sum >/dev/null; then sha256sum "$1" | cut -d' ' -f1
  else shasum -a 256 "$1" | cut -d' ' -f1; fi
}

# ── Toolchain ────────────────────────────────────────────────────────────────
case "$(uname -s)" in
  Darwin)
    : "${JAVA_HOME:=/Applications/Android Studio.app/Contents/jbr/Contents/Home}"
    : "${ANDROID_HOME:=$HOME/Library/Android/sdk}" ;;
  *)
    : "${ANDROID_HOME:=${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}" ;;
esac
: "${ANDROID_NDK_HOME:=$ANDROID_HOME/ndk/$NDK_VERSION}"
export ANDROID_HOME ANDROID_NDK_HOME ANDROID_NDK_ROOT="$ANDROID_NDK_HOME"
if [ -n "${JAVA_HOME:-}" ]; then export JAVA_HOME PATH="$JAVA_HOME/bin:$PATH"; fi
if [ -n "${SING_BOX_SRC:-}" ] && [ -d "$SING_BOX_SRC" ]; then
  SING_BOX_SRC="$(cd "$SING_BOX_SRC" && pwd)"
fi

command -v go >/dev/null   || die "go not found (need $GO_VERSION: https://go.dev/dl/)"
command -v git >/dev/null  || die "git not found"
command -v javac >/dev/null || die "javac not found (set JAVA_HOME to a JDK $JAVAC_VERSION)"
command -v unzip >/dev/null || die "unzip not found"

# Carry over only where to download modules from; everything that shapes the
# output is set explicitly below, and GOENV=off makes sure a `go env -w` file
# cannot quietly override it.
for v in GOPROXY GOSUMDB GONOSUMDB GONOPROXY GOPRIVATE GOINSECURE GOAUTH; do
  val="$(GOTOOLCHAIN=local go env "$v" 2>/dev/null || true)"
  if [ -n "$val" ]; then export "$v=$val"; fi
done
[ -n "${GOMODCACHE:-}" ] || export GOMODCACHE="$(GOTOOLCHAIN=local go env GOMODCACHE)"
export GOENV=off GOTOOLCHAIN=local GOFLAGS=-mod=readonly GOWORK=off
export GO111MODULE=on CGO_ENABLED=1 GOEXPERIMENT= GOFIPS140=off
export GOAMD64=v1 GOARM64=v8.0 GOARM=7
unset CGO_CFLAGS CGO_CPPFLAGS CGO_CXXFLAGS CGO_FFLAGS CGO_LDFLAGS GOGCCFLAGS CC CXX AR PKG_CONFIG
export LC_ALL=C TZ=UTC
umask 022

have_go="$(go env GOVERSION)"
[ "$have_go" = "$GO_VERSION" ] || mismatch "Go is $have_go, pinned $GO_VERSION"

[ -f "$ANDROID_NDK_HOME/source.properties" ] || die "no NDK at $ANDROID_NDK_HOME (install NDK $NDK_VERSION: sdkmanager 'ndk;$NDK_VERSION')"
have_ndk="$(sed -n 's/^Pkg.Revision *= *//p' "$ANDROID_NDK_HOME/source.properties" | tr -d '\r')"
[ "$have_ndk" = "$NDK_VERSION" ] || mismatch "NDK is $have_ndk ($ANDROID_NDK_HOME), pinned $NDK_VERSION"
READELF="$(ls "$ANDROID_NDK_HOME"/toolchains/llvm/prebuilt/*/bin/llvm-readelf 2>/dev/null | head -1)"
[ -x "$READELF" ] || die "llvm-readelf not found in the NDK"

have_javac="$(javac -version 2>&1 | awk '{print $2}')"
[ "$have_javac" = "$JAVAC_VERSION" ] || mismatch "javac is $have_javac ($(command -v javac)), pinned $JAVAC_VERSION"

ls "$ANDROID_HOME"/platforms/android-*/android.jar >/dev/null 2>&1 \
  || die "no Android platform in $ANDROID_HOME/platforms (need android-$ANDROID_API or newer)"

have_wrapper="$(sha256 "$REPO/tools/rcqbox/rcqbox.go")"
[ "$have_wrapper" = "$WRAPPER_SHA256" ] \
  || mismatch "tools/rcqbox/rcqbox.go has sha256 $have_wrapper, pinned $WRAPPER_SHA256 (edited? checked out with CRLF line endings?)"

# ── Work directory ───────────────────────────────────────────────────────────
WORK="$(mktemp -d "${TMPDIR:-/tmp}/rcqbox-build.XXXXXX")"
WORK="$(cd "$WORK" && pwd -P)"
cleanup() {
  if [ "${RCQBOX_KEEP_WORK:-}" = 1 ]; then
    echo "work directory kept: $WORK" >&2
  else
    chmod -R u+w "$WORK" 2>/dev/null || true
    rm -rf "$WORK"
  fi
}
trap cleanup EXIT
export GOCACHE="${RCQBOX_GOCACHE:-$WORK/gocache}"
export TMPDIR="$WORK/tmp" GOTMPDIR="$WORK/tmp"
mkdir -p "$TMPDIR" "$WORK/bin" "$WORK/out"
export PATH="$WORK/bin:$PATH"
LOG="$WORK/build.log"

# ── gomobile + gobind at the pinned version. `go install mod@version` goes
#    through the module proxy and is checked against sum.golang.org, so this
#    is the published v0.1.12 and nothing else. ─────────────────────────────
say "gomobile $GOMOBILE_VERSION"
(cd "$WORK" && GOBIN="$WORK/bin" GOFLAGS= go install \
  "$GOMOBILE_MODULE/cmd/gomobile@$GOMOBILE_VERSION" \
  "$GOMOBILE_MODULE/cmd/gobind@$GOMOBILE_VERSION")
for t in gomobile gobind; do
  go version -m "$WORK/bin/$t" | grep -qE "^[[:space:]]+mod[[:space:]]+$GOMOBILE_MODULE[[:space:]]+$GOMOBILE_VERSION[[:space:]]" \
    || die "$t does not report $GOMOBILE_MODULE $GOMOBILE_VERSION"
done

# ── sing-box at the pinned commit. Fetched by hash into a fresh repository,
#    so neither a branch name nor anyone's local checkout can substitute
#    something else. Line-ending conversion is off: a CRLF checkout is
#    different source. ───────────────────────────────────────────────────────
SB="$WORK/sing-box"
say "sing-box $SING_BOX_COMMIT"
git init -q "$SB"
GIT=(git -C "$SB" -c core.autocrlf=false -c core.eol=lf -c advice.detachedHead=false)
"${GIT[@]}" fetch -q --depth 1 "${SING_BOX_SRC:-$SING_BOX_REPO}" "$SING_BOX_COMMIT" \
  || die "could not fetch $SING_BOX_COMMIT from ${SING_BOX_SRC:-$SING_BOX_REPO}"
"${GIT[@]}" checkout -q --detach "$SING_BOX_COMMIT"
[ "$("${GIT[@]}" rev-parse HEAD)" = "$SING_BOX_COMMIT" ] || die "checkout is not at $SING_BOX_COMMIT"
[ -z "$("${GIT[@]}" status --porcelain)" ] || die "sing-box checkout is not clean"

mkdir "$SB/rcqbox"
cp "$REPO"/tools/rcqbox/*.go "$SB/rcqbox/"

# ── Bind. gomobile builds the three ABIs in parallel, then javac-compiles the
#    Java side and writes the .aar. ───────────────────────────────────────────
say "gomobile bind ($TARGETS, API $ANDROID_API, tags $TAGS); a cold build takes a minute or more"
AAR="$WORK/out/rcqbox.aar"
if ! (cd "$SB" && gomobile bind -v \
      -o "$AAR" \
      -target="$TARGETS" \
      -androidapi "$ANDROID_API" \
      -tags "$TAGS" \
      -trimpath \
      -buildvcs=false \
      -ldflags="$LDFLAGS" \
      ./rcqbox) >"$LOG" 2>&1; then
  tail -40 "$LOG" >&2
  die "gomobile bind failed (full log above; RCQBOX_KEEP_WORK=1 keeps $LOG)"
fi

# ── Check what came out ──────────────────────────────────────────────────────
X="$WORK/extract"
mkdir "$X"
(cd "$X" && unzip -q "$AAR")
for abi in "${ABIS[@]}"; do
  so="$X/jni/$abi/libgojni.so"
  [ -f "$so" ] || die "missing jni/$abi/libgojni.so in the .aar"

  aligns="$("$READELF" -lW "$so" | awk '$1=="LOAD"{print $NF}' | sort -u | tr '\n' ' ')"
  [ "$aligns" = "0x4000 " ] || die "$abi: PT_LOAD alignment is '$aligns', must be 0x4000 (16 KB pages)"

  info="$(go version -m "$so")"
  grep -q "^$so: $GO_VERSION\$" <<<"$info" || die "$abi: not built by $GO_VERSION"
  grep -qE "^[[:space:]]+build[[:space:]]+-trimpath=true" <<<"$info" || die "$abi: not built with -trimpath"
  if grep -qE "^[[:space:]]+build[[:space:]]+vcs" <<<"$info"; then die "$abi: VCS state stamped in"; fi

  # No path of this machine may reach the binary: not the build directory,
  # the module cache, GOROOT, the NDK, or this checkout.
  for p in "$WORK" "$GOMODCACHE" "$(go env GOROOT)" "$ANDROID_NDK_HOME" "$REPO"; do
    if LC_ALL=C grep -qaF "$p" "$so"; then die "$abi: the binary contains the local path $p"; fi
  done
done

# ── Result ───────────────────────────────────────────────────────────────────
cp "$AAR" "$OUT.tmp.$$" && mv -f "$OUT.tmp.$$" "$OUT"

{
  echo
  echo "rcqbox.aar built from sing-box $SING_BOX_COMMIT + tools/rcqbox"
  echo "toolchain: $GO_VERSION, $GOMOBILE_MODULE $GOMOBILE_VERSION, NDK $NDK_VERSION, javac $JAVAC_VERSION"
  echo
  echo "sha256:"
  for abi in "${ABIS[@]}"; do
    printf '  %s  jni/%s/libgojni.so\n' "$(sha256 "$X/jni/$abi/libgojni.so")" "$abi"
  done
  printf '  %s  classes.jar\n' "$(sha256 "$X/classes.jar")"
  printf '  %s  rcqbox.aar\n' "$(sha256 "$OUT")"
  echo
  echo "written to $OUT"
}
