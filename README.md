# Keep SIM Alive

Android app that sends an SMS on the schedule you define so your prepaid, roaming, or backup SIM card is never deactivated for inactivity.

## Install

- [GitHub releases](https://github.com/kotowski-app/keepsimalive/releases) — download the latest `keepsimalive-v<version>.apk` asset and install it (allow "install unknown apps" for your browser or file manager when Android asks)
- F-Droid — coming soon

## How it works

For each SIM on the device you set a recipient, a message, and a schedule for when the message goes out. Each keep-alive is a regular SMS: your carrier may charge for it as per your plan.

## Features

- Dual-SIM device support
- Fixed send time or a random window
- Off-schedule manual send
- Send history with per-message outcomes
- Automatic retries if network is down or the SIM is removed
- Live SIM status to clearly identify the SIM
- Schedule per SIM card and not per slot, so the schedule follows the SIM
- Optional sticky notification while keep-alive is on
- Survives reboots and app updates

## Privacy

No internet permission and no network calls: keep-alive messages are sent directly over the cellular network and all data stays on the device. No advertising, no tracking, no analytics.

## Permissions

| Permission | Why |
|---|---|
| Send SMS | the keep-alive message itself |
| Read phone state | to properly link the schedule to a certain SIM card |
| Show notifications | recommended for sticky notification |
| Boot completed | to re-arm the schedule after a reboot |
| Ignore battery optimizations | optional but recommended, for reliable wake-ups |
| Schedule exact alarms (Android 12+) | optional and experimental, for wake-ups despite aggressive battery optimizations; recommended on devices known to kill background apps. On Android 12-14 you grant it via a separate Alarms & reminders toggle, and on Android 15+ the battery exemption above grants it automatically |

## Building from source

Requires OpenJDK 17 and the Android SDK with accepted licenses — Android Studio provides both, or use the [SDK command-line tools](https://developer.android.com/studio#command-line-tools-only). The first build downloads the Gradle distribution, SDK components, and dependencies.

Build and install to all connected devices in one step (runs the full test + lint gate first, same as CI; `--no-checks` skips it):

```sh
./build.sh            # debug: simulates sending, no real SMS leaves the device
./build.sh release    # real build, signed with the developer's keystore
```

The release signing keystore is intentionally not part of this repository: without it the release variant builds unsigned and cannot be installed, while debug falls back to the standard debug key and installs fine. To sign with your own key, put a keystore at `local/release.keystore` (e.g. from `keytool -genkeypair`) and its `storePassword`, `keyAlias`, `keyPassword` in `local/release-keystore.properties` — the build picks both up automatically.

## Stack

Kotlin, Jetpack Compose (Material 3), Hilt, Room, WorkManager, Navigation Compose — minSdk 24 (Android 7.0).

## License

[GNU GPL v3](LICENSE)

## Support the development

[Buy the developer a coffee](https://www.buymeacoffee.com/kotowski)
