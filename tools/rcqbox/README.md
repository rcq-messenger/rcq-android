# rcqbox

The Go source of `app/libs/rcqbox.aar`, the library behind the
censorship-bypass transport
(`app/src/main/java/app/rcq/android/net/SingBoxTransport.kt`).

## What it is

`rcqbox.go` is a small wrapper that gives gomobile a four-call API over
[sing-box](https://github.com/SagerNet/sing-box): `NewBoxService()`,
`Start(configJSON)`, `Stop()` and `IsRunning()`. gomobile turns it into the Java
classes `rcqbox.Rcqbox` and `rcqbox.BoxService` plus one `libgojni.so` per ABI.
`SingBoxTransport` writes a sing-box JSON config (a VLESS+Reality or Hysteria2
outbound to a relay, a local inbound on 127.0.0.1) and hands it to `Start`.

The wrapper carries no protocol code of its own. Everything that touches the
network is upstream sing-box, unmodified.

## What it is built against

| | |
|---|---|
| sing-box | https://github.com/SagerNet/sing-box, commit `82e84f950cab3b215f4cfb4021a3f6ad0ec78fd1` (2026-05-20), no changes |
| Build tags | `with_utls,with_quic` |
| Binding | `github.com/sagernet/gomobile` v0.1.12 (sing-box's own fork, the version its `go.mod` requires) |

`tools/build-rcqbox.sh` fetches that commit, copies this directory's `.go`
files in as the package `github.com/sagernet/sing-box/rcqbox`, and runs
`gomobile bind` on a pinned toolchain. The build is reproducible: the expected
hashes and the steps to check them are in
[docs/REPRODUCIBLE-BUILDS.md](../../docs/REPRODUCIBLE-BUILDS.md#verifying-rcqboxaar-sing-box).

## License

`rcqbox.go` is GPL-3.0-or-later, the license of the sing-box code it is
compiled together with, so `rcqbox.aar` as a whole is under one license. The
app that links the `.aar` is AGPL-3.0 (see [LICENSE](../../LICENSE)); section
13 of both licenses allows the combination. See also [NOTICE](../../NOTICE).

## History

Until September 2026 this file lived only in a local sing-box checkout, and the
shipped `rcqbox.aar` was built without `-trimpath`, with local paths and a
"modified" VCS flag stamped in, so nobody else could rebuild it byte for byte.
The code is the same as in that build; only the license header and the package
comment (which used to mention iOS alone) are new.

## iOS

The iOS client ([rcq-ios](https://github.com/rcq-messenger/rcq-ios)) links the
same wrapper at the same sing-box commit, bound with `gomobile bind
-target=ios` (tags `with_quic,with_utls,ios`) into
`RCQ/Vendor/Rcqbox.xcframework`. That framework is not committed to the iOS
repository (it is gitignored: one slice is over GitHub's 100 MB file limit),
this script does not build it, and its build is not reproducible yet.
