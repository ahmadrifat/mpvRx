# mpvRx Custom 2.7.2-custom.2

Based on upstream 594bfc67, with the previous custom source checkpoint preserved in Git.

## Installation

Install the universal APK on Android 8 or newer. Package: app.gyrolet.mpvrx.custom. Version code: 20. It updates the earlier custom APK signed with the same private key and retains its data. It also coexists with the author's original app. An APK with the original package but a different signing certificate cannot update the author's installation.

## Changes

- Configurable Download player button, defaulted before More; uses the existing download engines.
- Pause/resume for direct and yt-dlp downloads; partial files retained. Direct HTTP resume requires a stable server validator and byte-range support; otherwise it restarts safely.
- Download notification opens Downloads.
- Automatic clipping: optimized Media3 export where supported, hardware encoding where needed, FFmpeg fallback. Millisecond input is resolved to available video frames; a 30 fps video cannot have a new boundary at every millisecond. Clipping progress and completed clips appear in Downloads. Online extraction attempts the selected interval first and can fall back to downloading the source. Existing complete downloads are reused.
- Torrent automatic background transfers pause on mobile data and never automatically restart when Wi-Fi returns. Playback resumes transfers; explicit Resume permits either network. Completed files open locally and partial transfers reuse saved pieces. Downloads delete removes video; Network Media delete removes saved data and history.
- MAG form below Xtream with consistent styling; single-playlist Information action; provider expiry and connection counts when supplied. Providers may omit counts or report different semantics.
- Playlist edit includes connection configuration, and Save refreshes before replacing the previous catalog. Failed saves use the app's default toast and preserve the form and existing catalog.
- Xtream category lookup and M3U EXTGRP parsing fixes.

## Build and maintain

Preserve the package and private signing key for updates. Signing files are excluded from the source bundle; back them up privately. Without them Gradle uses a local debug key that cannot update this APK. Increase customVersionCode for subsequent releases; universal version codes are multiplied by ten.

Build with the repository Gradle wrapper, a compatible JDK, Android SDK and configured NDK:

    .\gradlew.bat :app:assembleStandardCustom :app:testStandardCustomUnitTest --no-daemon --max-workers=2

Universal output: app/build/outputs/apk/standard/custom/app-standard-universal-custom.apk.

Keep feature changes in separate commits. Fetch and merge origin/master periodically, resolve conflicts, run checks and rebuild with the same signing key. Source bundles contain the final source; the local Git repository retains the checkpoint and merge history.

## Verification and limits

See Build-verification-v2.json for the actual checks performed. Live IPTV providers, torrent swarms, platform access rules and Samsung S23 behavior require device testing. MAG support targets conventional MAC-authenticated portals; extra device identity or provider-specific authorization may require adaptations. Availability of expiry and concurrency data depends on the provider.

Clipping cannot recover past live segments no longer offered by the server, bypass DRM, or promise support for every codec. Encoding is necessary for frame-accurate boundaries that cannot be safely copied. Clip encoding has cancel/retry, not pause/resume. Android force-stop and foreground-service time limits can interrupt work. Offline torrent storage is private and removed when app data is cleared or the app uninstalled.

## Source and notices

The app remains AGPL-3.0-or-later. Share the modified source, LICENSE and third-party notices with redistributed APKs. The FFmpeg runtime includes additional third-party libraries; see THIRD_PARTY_CLIPPING.md for provenance and corresponding build/source references. No provider credentials or private signing material are included in the source bundle.
