# RCQ Android

Android client for **RCQ**, an end-to-end encrypted messenger whose identity is
a number the network issues you. No phone number, no email, no name, no ads.
Censorship circumvention is built into the app rather than left to the user.

- Site and downloads: <https://rcq.app>
- Latest release: [the releases page](../../releases/latest) (`rcq-universal.apk`
  plus per-ABI builds; the same files are mirrored at <https://rcq.app/android/>
  for networks where GitHub is throttled).
- Protocol spec: <https://github.com/rcq-messenger/rcq-spec>
- Reference server: <https://github.com/rcq-messenger/rcq-server-ref>
- iOS client: <https://github.com/rcq-messenger/rcq-ios>

**Status: open beta.** Published outside Google Play by sideloading; the app
self-updates from a manifest, trying `dl.rcq.app/android/latest.json` first and
`rcq.app/android/latest.json` after it. Messages, groups, calls and media are
cross-platform with the iOS and web clients; stories are on the phones only.

## What is in here

| Path | What it is |
|---|---|
| `app/src/main/java/app/rcq/android/crypto` | libsignal-android integration, sealed sender, sender keys |
| `app/src/main/java/app/rcq/android/net` | REST + WebSocket client, and `SingBoxTransport`, the embedded circumvention transport |
| `app/src/main/java/app/rcq/android/push` | UnifiedPush connector, plus `push/embedded`: our own in-app UnifiedPush distributor |
| `app/src/main/java/app/rcq/android/ui` | Compose screens |
| `app/src/main/java/app/rcq/android/nearby` | Nearby, district chat, and Radio Chat — the offline mesh over BLE + Wi-Fi Direct |
| `app/src/main/java/app/rcq/android/backup` | The `.rcqbak` archive: the same file the iOS and web clients read |
| `docs/` | `REPRODUCIBLE-BUILDS.md` — how to check a published APK against this source |

## Building

```bash
./gradlew :app:assembleDebug
```

Requirements: Android SDK 36, and a JDK. Any JDK 17 or newer builds a debug
APK. Reproducing a **published** APK is a different question and needs the
exact toolchain — JetBrains Runtime 21.0.9, the one bundled with Android
Studio. See [docs/REPRODUCIBLE-BUILDS.md](docs/REPRODUCIBLE-BUILDS.md);
building a release on a different JDK will not match the signature you are
trying to verify. Everything else is fetched by Gradle.

A release build reads `keystore.properties` from the repo root for signing.
That file is gitignored and our release key is obviously not in here; without
it the release variant falls back to the debug key.

## Notes for anyone reading the code

- **Push does not use FCM.** Google services are unreachable in the target
  region, so wakes ride UnifiedPush. The app ships its own distributor
  (`push/embedded`) pointed at our own push server, and any third-party
  distributor such as ntfy stays selectable in Settings.
- **Circumvention is in-process.** sing-box runs inside the app and exposes a
  local SOCKS proxy. No system VPN profile, no second app to install.
- **The server is replaceable.** An account lives on the island its owner
  picked, and anyone can run one from `rcq-server-ref`.
- **Radio Chat is a separate mode, not the transport.** Normal messaging goes
  over the internet like any other messenger; the BLE + Wi-Fi Direct mesh is an
  extra for when there is no network at all.

## License

[GNU AGPL-3.0](LICENSE). A network-facing service built from this code must
publish its modifications under the same license. Third-party attributions are
in [NOTICE](NOTICE).

## Security

Please report vulnerabilities to `security@rcq.app` before filing a public
issue. See [SECURITY.md](SECURITY.md).
