[中文](README.md) | English

# NovaX Player

An Android video player for local and network media. It plays from SMB, SFTP, WebDAV, Jellyfin and local storage, runs a dual playback engine (ExoPlayer + libVLC), casts over DLNA, and keeps all data on your device.

[![GitHub Release](https://img.shields.io/github/v/release/liuyouwu1999/NovaX-Player)](https://github.com/liuyouwu1999/NovaX-Player/releases)
[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](LICENSE)
![Platform](https://img.shields.io/badge/platform-Android-lightgrey)
![Min API](https://img.shields.io/badge/Android-8.0%2B%20(API%2026)-green)

[Download](#download) · [Features](#features) · [Gestures](#gestures) · [Build from source](#build-from-source) · [Contributing](#contributing) · [License](#license)

## Download

Get the latest APK from the [Releases](https://github.com/liuyouwu1999/NovaX-Player/releases) page. APKs are split by ABI to keep each download small:

| File | Device |
|---|---|
| `NovaX-<version>-arm64-v8a.apk` | Most modern phones and tablets — pick this if unsure |
| `NovaX-<version>-armeabi-v7a.apk` | Older 32-bit devices |
| `NovaX-<version>-x86_64.apk` | Emulators and Chromebooks |

Requires Android 8.0 (API 26) or later. Every release ships with SHA-256 checksums so you can verify your download.

Access to GitHub from mainland China may require solving network reachability on your own.

Note: the in-app update checker depends on the Release asset naming `NovaX-<version>-<abi>.apk` and the tag `v<version>` — please do not change these.

## Features

Media sources:

- SMB, SFTP, WebDAV, Jellyfin and local storage; add a source once and keep using it
- NAS passwords are encrypted with Android Keystore (AES-256-GCM) before they touch the database — no plaintext (except on the rare devices where Keystore is unavailable)

Playback:

- Dual engine: ExoPlayer (Media3) as the main player, with libVLC taking over automatically for RM/RMVB and other proprietary containers
- FFmpeg audio decoding: AC3, E-AC3 (JOC), DTS (DTS-HD) and TrueHD fall back to software decoding when silent, and the choice is remembered
- Resume positions, playback speed, audio/subtitle offset, stall recovery, auto-reconnect

Casting:

- Built-in DLNA / UPnP renderer discovery and streaming — push local or NAS videos to your TV

Extras:

- Thumbnail cache, folder poster wall, TMDB scraping (bring your own API key in Settings)
- Picture-in-picture, background playback, notification controls; screen orientation follows the system
- Multi-language UI: English / Korean / Japanese + Chinese — follows the system language, or switch manually in the app under Settings → Interface → Language
- In-app update check (Settings → About), with in-app download (SHA-256 verified) or browser download

No analytics, ads or tracking SDKs are bundled. Your library, playback history and source settings never leave the device.

## Gestures

Playback follows the interaction conventions of mainstream video apps — if you have used any popular video app, every control is where your fingers expect it. All parameters are adjustable under Settings → Player:

- Double-tap to seek: double-tap the left fifth of the screen to rewind and the right fifth to advance (±15 s by default; 10/15/25/30 s available, taps stack). A double-tap in the center toggles play/pause
- Long-press for speed: hold to speed up (2x by default, six steps from 0.5x to 3x), release to restore. Swipe down while holding to latch the speed so seeking won't interrupt it. An optional 3x auto-mute is available in Settings (off by default)
- Pinch to zoom and pan the picture
- Horizontal swipe to seek, as a percentage of the video length (10% per screen by default) — no jumping on long videos
- Single tap toggles the control overlay; one-tap lock disables all gestures to prevent pocket touches
- Plus AB looping, a sleep timer, and fine-tuned audio/subtitle offsets

## Screenshots

| Home | Library | Search | Dark theme |
|:---:|:---:|:---:|:---:|
| ![Home](screenshots/home-light.jpg) | ![Library](screenshots/library.jpg) | ![Search](screenshots/search.jpg) | ![Dark theme](screenshots/home.jpg) |

Player and player menu:

![Player](screenshots/player.jpg)

![Player menu](screenshots/player-menu.jpg)

Both light and dark themes are supported and follow the system setting.

## Build from source

You need Android Studio with the Android Gradle Plugin 9.1+, JDK 17+, the Android SDK (compileSdk 37) and NDK / CMake. Minimum supported version is Android 8.0.

```bash
git clone https://github.com/liuyouwu1999/NovaX-Player.git
cd NovaX-Player
# Android Studio generates local.properties automatically, or from the command line:
./gradlew :app:assembleDebug
```

The FFmpeg extension's headers and prebuilt static libraries for each ABI are committed to the repository, so a fresh clone builds as-is. To rebuild FFmpeg itself, prepare a source tree following the [official FFmpeg documentation](https://ffmpeg.org/documentation.html).

The debug build's demo mode plays `app/src/debug/assets/sample.mp4` (not committed to the repository) — drop in any MP4 renamed to that filename.

## Contributing

Issues for bugs and feature requests are welcome. For pull requests, please open an issue first to discuss the approach. Commit messages should follow [Conventional Commits](https://www.conventionalcommits.org/en/v1.0.0/).

## Free, not for resale

NovaX Player is completely free. The only official distribution channels are this repository and its [Releases](https://github.com/liuyouwu1999/NovaX-Player/releases) page.

Anyone selling, bundling or otherwise charging for this software — on an app store, a website, or as an individual reseller — is committing fraud under the developer's name and is in no way affiliated with the project. Please do not pay for it; report such listings to the store in question, or let us know via an [issue](https://github.com/liuyouwu1999/NovaX-Player/issues).

## Disclaimer

This software is provided "as is", without warranty of any kind, express or implied. To the maximum extent permitted by law, the developer is not liable for any direct or indirect losses arising from the use of, or inability to use, this software.

The app only plays local and LAN files. It does not provide, store or recommend any content. You are responsible for the source and legality of everything you play with it; please follow the laws of your region.

## Third-party components

| Component | Role | License |
|---|---|---|
| [AndroidX Media3 / ExoPlayer](https://github.com/androidx/media) | Main playback engine | Apache-2.0 |
| [libVLC](https://code.videolan.org/videolan/libvlc-android) (VLC 4 EAP) | RM/RMVB fallback engine | LGPL-2.1+ |
| [FFmpeg](https://ffmpeg.org) | Audio software decoding | LGPL-2.1+ (prebuilt libraries bundled) |
| [jcifs-ng](https://github.com/AgNO3/jcifs-ng) / [smbj](https://github.com/hierynomus/smbj) | SMB client | LGPL-2.1 / Apache-2.0 |
| [sshj](https://github.com/hierynomus/sshj) | SFTP client | Apache-2.0 |
| [Jetty](https://github.com/jetty/jetty.project) | Cast streaming server | Apache-2.0 / EPL |
| [jUPnP](https://github.com/jUPnP/jUPnP) | DLNA / UPnP | CDDL-1.0 |
| [OkHttp](https://github.com/square/okhttp), [Coil](https://github.com/coil-kt/coil), [Haze](https://github.com/chrisbanes/haze), pinyin4j, SLF4J and other Jetpack / AndroidX libraries | Networking and UI foundations | Apache-2.0 / BSD / MIT |

All components remain the property of their respective authors. Thanks to the open-source community.

## License

This project is licensed under the [GNU GPL v3](LICENSE). Derivative works must be licensed under the same terms and keep the copyright notice.

Copyright © 2026 NovaX Dev
