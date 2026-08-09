#!/usr/bin/env bash
#
# build-rcqbox.sh — rebuild app/libs/rcqbox.aar, the gomobile binding of the
# sing-box core that carries the censorship-bypass transport
# (app/src/main/java/app/rcq/android/net/SingBoxTransport.kt).
#
# The .aar is a prebuilt binary in this repo (~60MB, three ABIs). It is the one
# artifact an APK-level reproducible build does NOT re-derive from source, so
# the exact recipe lives here rather than in someone's shell history. The flags
# below match what is recorded inside the shipped library; confirm with:
#
#   unzip -p app/libs/rcqbox.aar jni/arm64-v8a/libgojni.so > /tmp/libgojni.so
#   go version -m /tmp/libgojni.so | grep -E '^\s+build'
#
# Inputs: a sing-box checkout with the RCQ wrapper package (rcqbox/) at $SRC.
# Output: an .aar with jni/{arm64-v8a,armeabi-v7a,x86_64}/libgojni.so plus the
# Java bindings (rcqbox.Rcqbox, rcqbox.BoxService, package go.rcqbox.gojni).
#
# ⚠ 16 KB pages. Android 15+ devices can have a 16 KB page size, and a library
# whose PT_LOAD segments are aligned to 4 KB will not load there. Go does not
# set the alignment itself, so we pass it to the NDK linker explicitly. Without
# the -ldflags below the build silently produces a 4 KB library: the app still
# starts (the library is loaded when the tunnel comes up, not at launch) and the
# only symptom is that the bypass never works. Check the result, don't assume
# (macOS has no readelf; the NDK ships llvm-readelf):
#
#   "$ANDROID_NDK_HOME"/toolchains/llvm/prebuilt/*/bin/llvm-readelf -lW <lib> \
#     | awk '$1=="LOAD"{print $NF}'      # must be 0x4000, not 0x1000
#
# Usage: ./tools/build-rcqbox.sh [output.aar] [sing-box-src-dir]
#
set -euo pipefail

OUT="${1:-$(cd "$(dirname "$0")/.." && pwd)/app/libs/rcqbox.aar}"
SRC="${2:-$HOME/sing-box-src}"

export JAVA_HOME="${JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
export ANDROID_NDK_HOME="${ANDROID_NDK_HOME:-$(ls -d "$ANDROID_HOME"/ndk/* | tail -1)}"
export ANDROID_NDK_ROOT="$ANDROID_NDK_HOME"
export PATH="$JAVA_HOME/bin:$(go env GOPATH)/bin:$PATH"

command -v gomobile >/dev/null || {
  echo "gomobile not found. Install the sagernet fork:" >&2
  echo "  go install github.com/sagernet/gomobile/cmd/gomobile@v0.1.12" >&2
  echo "  go install github.com/sagernet/gomobile/cmd/gobind@v0.1.12" >&2
  exit 1
}

cd "$SRC"
gomobile bind -v \
  -o "$OUT" \
  -target=android/arm,android/arm64,android/amd64 \
  -androidapi 26 \
  -tags with_utls,with_quic \
  -ldflags="-extldflags=-Wl,-z,max-page-size=16384" \
  ./rcqbox

echo "built $OUT"
