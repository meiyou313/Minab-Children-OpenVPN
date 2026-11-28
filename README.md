# Minab OpenVPN (Android)

[![Release](https://img.shields.io/github/v/release/meiyou313/Minab-Children-OpenVPN?include_prereleases&label=Release&color=5B5BD6)](https://github.com/meiyou313/Minab-Children-OpenVPN/releases)
[![Android CI](https://github.com/meiyou313/Minab-Children-OpenVPN/actions/workflows/build-android.yml/badge.svg)](https://github.com/meiyou313/Minab-Children-OpenVPN/actions/workflows/build-android.yml)
[![License: GPL v2](https://img.shields.io/badge/License-GPLv2-blue.svg)](doc/LICENSE.txt)

**Minab OpenVPN** is a modern, streamlined Android OpenVPN client built on top of [**ics-openvpn**](https://github.com/schwabe/ics-openvpn) (Arne Schwabe, GPLv2), designed for fast multi-server management, smart reachability diagnostics, clean backup/sharing, and zero-telemetry privacy.

> **میناب اوپن‌وی‌پی‌ان** یک کلاینت رایگان، متن‌باز و بدون تبلیغات برای اندروید (بر پایه `ics-openvpn`) است که برای مدیریت ساده ده‌ها کانفیگ `.ovpn` و `.zip`، پینگ و ریل‌تست هوشمند، گروه‌بندی خودکار بر اساس IP کشورها، اشتراک‌گذاری ایمن بدون نام کاربری و رمز، پایش سرعت زنده و عیب‌یابی محلی طراحی شده است.

---

## Project Identity & Metadata

| Item | Details |
| :--- | :--- |
| **App Name** | Minab OpenVPN |
| **Application ID** | `com.minab.children` *(internal Java serialization package stays `de.blinkt.openvpn` for profile compatibility)* |
| **Current Release** | **Minab Beta v0.9.8.2** — `versionName = "0.9.8.2-beta"`, `versionCode = 29`, tag `v0.9.8.2` |
| **Platform** | Android `minSdk 23` (Android 6.0+) · `targetSdk 37` · Free & GPLv2 Open Source |
| **Repository** | [`meiyou313/Minab-Children-OpenVPN`](https://github.com/meiyou313/Minab-Children-OpenVPN) |
| **Base Upstream Commit** | `schwabe/ics-openvpn` `660901ce320539aec476bd04bd1999c396d5e778` *(OpenVPN 2 + OpenVPN 3 cores)* |
| **Standalone Origin** | Built natively in Android Java/Kotlin/XML — ships **zero VPN servers and zero accounts**; bring your own `.ovpn` profiles |

---

## Key Features Beyond Stock ics-openvpn

### 1. Visual Experience, Animated Power Orb & Zero-Lag Halos
- **Animated Hardware-Accelerated Orb (`MinabConnectOrbView`)**: Distinct visual animations and palettes for **Disconnected**, **Connecting** (radar sweep), **Connected** (emerald shockwave + spring pop), **Disconnecting** (coral inward pulse), and **Testing** (dimmed lock).
- **Zero-Lag Row Halos**: Server rows in both **Home** and **Configs** light up immediately when **Testing**, **Connecting**, **Connected**, or **Disconnecting**, and tapping Disconnect during a slow handshake enforces a clean **450 ms** termination watchdog without ghost reconnects.
- **Vazirmatn Typography, Themes & 9 Languages**: Supports System/Day/Night themes and Persian, English, Arabic, Turkish, Russian, German, French, Chinese, or System default.
- **Three Home-Screen Widgets**: 1×1 Mini Power Orb, 2×1 Compact Quick Toggle, and 4×2 Status & Live Speed Dashboard with 1-tap Best Server connect.

### 2. Unique Multi-Group Server Architecture (`MinabProfileBus`)
- **Single Source of Truth**: Each imported config is stored once (never cloned when copied to custom groups) and can belong to **multiple groups simultaneously** (`[UnitedStates · Favorites · Custom]`).
- **Synced Everywhere**: Starred status (★), Ping/Real badges, and credentials stay synchronized across **Home**, **Configs**, **All**, **Favorites**, **Active**, **Inactive**, and custom groups.
- **Clean In-Group Deletion**: Deleting inside a custom group offers a 3-way dialog: **Remove from `<group>`** (keeps it elsewhere) or **Delete permanently**.
- **Smart Deduplication**: `Remove duplicates` keeps the best keeper (connected, starred, manual group, fastest score) and **merges all group memberships and favorites** onto it before removing duplicate endpoints.

### 3. Smart Two-Stage Diagnostics (`PingTester` & `RealTester`)
- **Stage 1 — Parallel Ping Test**: Runs ICMP + TCP OpenVPN-reset probes (8 concurrent threads, ~3 s timeout) with middlebox anti-spoof validation, skipping `VpnService` so an active tunnel isn't disrupted.
- **Stage 2 — End-to-End Real Test**: Connects ranked candidates in turn and fetches your chosen target URL (e.g., `https://api.telegram.org/`, `google.com/generate_204`, `cloudflare.com`, or custom URL) with selectable per-server timeout (default `20s`) and target healthy count (default `10`). Stays alive across tab switches and screen locks.
- **Smart Test Clearing & Muted-Gray Restore (`v0.9.8.2`)**: Starting a Ping or Real test immediately clears previous scores for that test scope so stale and fresh badges never mix; if cancelled or stopped early, untouched servers restore their prior results in **muted small gray pills** ranked cleanly below fresh results.
- **Network-Aware Test History & Dead Servers**: Snapshots Ping/Real runs tagged by transport/ISP/carrier (`MinabTestHistory`) and surfaces persistently failing **Never-working servers** (`MinabServerStats`) while protecting untested and working configs.

### 4. Bulk Import, GeoIP Grouping & `Set User/Pass`
- **Multi-File, ZIP & URL Import**: Batch-import `.ovpn`, `.conf`, or `.zip` archives (up to 500 entries) and remote `.ovpn`/`.zip` URLs via `BulkImportActivity`, committing configs atomically with a live progress card and GeoIP country detection (`Skip IP detection` supported).
- **Scoped Credentials (`Set User/Pass`)**: Clear scope indicators — **green banner** for selected/group targets vs. **amber warning** when modifying `ALL` configs — with password eye toggle, opt-in `Save as default`, and Android Keystore-backed `SharedCredentials` encryption.

### 5. Zero-Credential Cross-Platform Backups & Shares (`MinabBackup`)
- **Standardized Dated Filenames**: `Minab-Backup_<Scope>_<N>cfg_yyyy.MM.dd_HH.mm.zip`, `Minab-Share_<Scope>_<N>cfg_yyyy.MM.dd_HH.mm.zip`, and `<Name>_yyyy.MM.dd_HH.mm.ovpn`.
- **Zero Credentials Guaranteed**: Usernames and passwords are **never** written to any exported `.ovpn`, `.zip`, or `minab-backup.json`.
- **Cross-Platform Clean `.ovpn` + `Minab-Info.txt`**: Exports work directly in OpenVPN Connect on Windows, macOS, Linux desktop, and iOS; full/group backups preserve multi-group memberships and favorites without overwriting existing partial settings.

### 6. Live Analytics, Local Diagnostics Center & Accordion FAQ
- **Live IP & Speed Dashboard**: Displays public IP + country flag before/after connecting, live session speed/duration on Home, and Daily/Weekly/Monthly traffic charts in **Graph**.
- **Per-App Split Tunneling & Auto-Failover**: Route all except chosen apps or only chosen apps, plus optional Auto-Failover (up to 3 verified backup servers on unexpected drops).
- **Strictly Local Diagnostics Center**: `Settings → Help & Diagnostics → Diagnostics & Debug Logs` displays crashes, breadcrumbs, app logs, and OpenVPN logs locally with `.txt` Download/Share/Copy. Zero automatic uploads.
- **Modern Searchable FAQ**: Real-time search, category pills, and clickable `#hashtags`.

---

## Architecture & Build Flavors

Minab is built remotely via GitHub Actions (since local environments without the Android NDK/SDK rely on CI):

- **Flavor Split**: Both `main/src/main` (shared service/core engine + `:openvpn` service process) and `main/src/ui` / `main/src/skeleton` compile cleanly. All `main → ui` UI callbacks go exclusively through [`MinabUiBridge`](main/src/main/java/de/blinkt/openvpn/minab/MinabUiBridge.java) and [`MinabUiBridgeImpl`](main/src/ui/java/de/blinkt/openvpn/minab/MinabUiBridgeImpl.kt) so `SkeletonOvpn2`, `UiOvpn2`, and `UiOvpn23` all pass CI.
- **CI Workflows**:
  - [`.github/workflows/build-android.yml`](.github/workflows/build-android.yml) (**Minab Android**): Builds `./gradlew assembleUiOvpn23Debug` signed with Minab's permanent release keystore on every push and publishes tagged `v*` GitHub Releases.
  - [`.github/workflows/build.yaml`](.github/workflows/build.yaml) (**Build**): Verifies release compilation & unit tests across `SkeletonOvpn2`, `UiOvpn23`, and `UiOvpn2`.
- **Signing Key Continuity**: All versions from **`v0.4` onward** (including `0.9.8.x` and future `1.0.0`) share one permanent RSA-2048 release keystore (`alias = minab`, stored securely outside the repository via CI secrets), so updates install cleanly over previous builds.

---

## Version History Summary

See [`CHANGELOG.md`](CHANGELOG.md) for the complete changelog per version:
- **`v0.9.8.2`** (`code 29`): Smart pre-test clearing with muted small gray restoration on cancel/early stop + fresh-first sorting & Best-Server ranking; full documentation overhaul.
- **`v0.9.8.1`** (`code 28`): Fixed Diagnostics insets NPE crash, zero-lag Testing/Connecting/Disconnecting halos, 450 ms Disconnect watchdog, and elimination of ghost auto-reconnect / false Connected-without-server states.
- **`v0.9.8`** (`code 27`): Unique Multi-Group profiles, `✨` Smart Tools, Dead Server cleanup, zero-credential cross-platform backups, Diagnostics Center, and Accordion FAQ.
- **`v0.9.7.x` – `v0.1`** (`codes 1–26`): Dynamic IP grouping, Day/Night themes, 9 languages, animated PowerOrb, 3 home-screen widgets, live Graph analytics, auto-failover, and initial bulk import/ping foundation.

---

## Attribution & License

- **Base Project**: [ics-openvpn / OpenVPN for Android](https://github.com/schwabe/ics-openvpn), Copyright (c) Arne Schwabe, licensed under **GNU GPLv2** (with additional terms in [`doc/LICENSE.txt`](doc/LICENSE.txt)).
- **Typography**: [Vazirmatn](doc/Vazirmatn-OFL.txt) font (SIL Open Font License 1.1).
- All Minab additions in this repository are published openly under **GNU GPLv2**.

---

# Upstream README (ics-openvpn, preserved unchanged below)

OpenVPN for Android
=============
![build status](https://github.com/schwabe/ics-openvpn/actions/workflows/build.yaml/badge.svg)

Note to other developers 
------------------------
This is a spare time project that I work on my own time and pick to work what I want.
You are free use the source code of this app with the conditions (GPL) that are attached to it
but do not expect any support or anything that I do not feel like to provide. 

The goal of this project is about providing an open-source OpenVPN app for Android. It is 
NOT about creating a library to be used in other projects.

If you build something on top of this is app you MUST publish your source code according to
the license of this app (GPL).

This not personal against other developers or your software and projects. The reason that I am not 
helping or spending time to really into issues that are not part of this app itself  is that this 
is just a spare time project of mine. The number of apps that use my code is quite large and
the majority of them violates the license of my app. People create new apps that do not publish 
their source code.

I am just not willing to be the unpaid support for other people trying to make money of my code 
for free anymore. That is just not something I want to do in my spare time, so I tend to close
these tickets quite quickly. 

When the project started, I tried to help people but most people were just taking advantage of me 
and promises about open sourcing their app when they were finished were not fulfilled and 
I was just often ghosted when asking for the promises. Some people had even the audacity to 
come then back a year or two later and demand help with critical bug fixes when their app broke
with some newer Android versions. Over the time, I simply lost confidence in people that were 
hesitant to share their source code and play with open cards.

That being said, I am happy to work together with people that are have are reproducing bugs in
this app that they observed in their open-sourced fork to improve this app. 

Description
------------
With the new VPNService of Android API level 14+ (Ice Cream Sandwich) it is possible to create a VPN service that does not need root access. This project is a port of OpenVPN.

<a href="https://f-droid.org/repository/browse/?fdid=de.blinkt.openvpn" target="_blank">
<img src="https://f-droid.org/badge/get-it-on.png" alt="Get it on F-Droid" height="80"/></a>
<a href="https://play.google.com/store/apps/details?id=de.blinkt.openvpn" target="_blank">
<img src="https://play.google.com/intl/en_us/badges/images/generic/en-play-badge.png" alt="Get it on Google Play" height="80"/></a>

Code Transparency
-----------------
Fingerprint of the code transparency key used to be able to verify that
apks generated by the Play are not modified (https://developer.android.com/guide/app-bundle/code-transparency): 

    19 62 43 6C 96 B4 9D 12 75 83 B1 22 DA 14 F4 5D 2B 78 5D A4 13 1F 04 BE 73 A0 88 32 15 59 18 8D

Full signing certificate (also under misc/code-transparency.pem):

    -----BEGIN CERTIFICATE-----
    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
    -----END CERTIFICATE-----


Developing
---------------
If you want to develop on ics-openvpn please read the [doc/README.txt](https://github.com/schwabe/ics-openvpn/blob/master/doc/README.txt) *before* opening
issues or emailing me. 

Also, please note that before contributing to the project that I would like to retain 
my ability to relicense the project for different third parties and therefore probably 
need a contributor's agreement from any contributing party. To get started, 
[sign the Contributor License Agreement](doc/CLA.md).

You can help
------------
Even if you are no programmer you can help by translating the OpenVPN client into your native language. [Crowdin provides a free service for non commercial open source projects](https://crowdin.net/project/ics-openvpn/) (Fixing/completing existing translations is very welcome as well)

FAQ
-----
You can find the FAQ here (same as in app): https://ics-openvpn.blinkt.de/FAQ.html

Controlling from external apps
------------------------------

There is the AIDL API for real controlling (see developing section). Due to high demand also 
activities to start/stop, pause/resume (like a user would with the notification)  exists
  
 - `de.blinkt.openvpn.api.DisconnectVPN`
 - `de.blinkt.openvpn.api.ConnectVPN`
 - `de.blinkt.openvpn.api.PauseVPN`
 - `de.blinkt.openvpn.api.ResumeVPN`

They use `de.blinkt.openvpn.api.profileName` as extra for the name of the VPN profile.

You can use `adb` to test these intents:

    adb -d shell am start -a android.intent.action.MAIN -n de.blinkt.openvpn/.api.ConnectVPN --es de.blinkt.openvpn.api.profileName myvpnprofile


Note to administrators
------------------------

You make your life and that of your users easier if you embed the certificates into the .ovpn file. You or the users can mail the .ovpn as an attachment to the phone and directly import and use it. Also downloading and importing the file works. The MIME Type should be application/x-openvpn-profile. 

Inline files are supported since OpenVPN 2.1rc1 and documented in the  [OpenVPN man page](https://openvpn.net/community-docs/community-articles/openvpn-2-7-manual.html) (under INLINE FILE SUPPORT) 

(Using inline certificates can also make your life on non-Android platforms easier since you only have one file.)

For example `ca mycafile.pem` becomes
```
  <ca>
  -----BEGIN CERTIFICATE-----
  MIIHPTCCBSWgAwIBAgIBADANBgkqhkiG9w0BAQQFADB5MRAwDgYDVQQKEwdSb290
  [...]
  -----END CERTIFICATE-----
  </ca>
```

Minimal UI mode/Managed configuration
-------------------------------------
The app allows configuration via configuration that is provided by a central device management. See
the [app_restrictions.xml](main/src/main/res/xml/app_restrictions.xml) for the available options to 
configure app behaviour and configuration profiles.

One particular thing that can be enabled is the minimal mode that restricts the app to a single default
VPN Profile:

![Screenshot of minimal mode](misc/minimal_ui_mode.png)

Footnotes
-----------
Please note that OpenVPN used by this project is under GPLv2. 

If you cannot or do not want to use the Play Store you can [download the apk files directly](http://plai.de/android/).

If you want to donate you can donate to [arne-paypal@rfc2549.org via paypal](https://www.paypal.com/cgi-bin/webscr?hosted_button_id=R2M6ZP9AF25LS&cmd=_s-xclick), or alternatively if you believe in fancy Internet money you can use Bitcoin: 1EVWVqpVQFhoFE6gKaqSkfvSNdmLAjcQ9z 

The old official or main repository was a Mercurial (hg) repository at http://code.google.com/p/ics-openvpn/source/

The new Git repository is now at GitHub under https://github.com/schwabe/ics-openvpn

Please read the doc/README before asking questions or starting development.
