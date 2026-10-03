# mpvRx 2.7.2.6

Based on upstream 0e79b470, with the previous custom source checkpoint preserved in Git.

## Installation

Install the ARM universal APK on Android 8 or newer. It includes arm64-v8a and armeabi-v7a for 64-bit and 32-bit phones, including Samsung S23. It does not include x86/x86_64 devices or emulators. All four architectures remain available when building from source. Package: app.gyrolet.mpvrx.cs. Version code: 70. The package ID changed from .custom to .cs at the user's request. This release installs separately from the earlier custom app and does not automatically transfer its settings/playlists. It coexists with both that app and the author's original app. Subsequent .cs releases with the same signing key can update this installation. An APK with the original package but a different signing certificate cannot update the author's installation.

## Changes

- Audio/clip download history now retains yt-dlp thumbnail URLs or existing playback artwork, reusing the current Downloads image loader. No web-search service is added.
- Audio mode now uses Save audio and the audio icon instead of Save clip. Audio failures are labelled Audio export failed.
- Audio export now aligns delayed audio and fills missing end audio with silence so the requested interval matches the video timeline even when the audio track is shorter. Timestamp validation is retained. Regression checks cover delayed tracks, shorter audio and a 10.700–230.000 s interval at 44.1 kHz.


- Displayed name is now mpvRx; package and signing identity remain unchanged for in-place upgrades.
- Panel defaults follow the requested screenshots. Video/audio download controls are before More in Top Right and Portrait Bottom. Existing customized selections remain saved; Reset defaults applies the full default panel configuration.
- Configurable blended headphone/download icon opens the existing millisecond trim editor, defaults to the full duration when known, and saves M4A, MP3, WAV or raw AAC under Music/mpvRx/Clips. M4A is selected initially. Only audio is encoded; extractor sites use separate audio where available. Progress, completion, retry and deletion share Downloads with clips. No video conversion runs for audio exports. Audio mode has no crop controls/information, preserves Start/End labels, and uses outlined format buttons with a tonal fill for the selected format. Live IPTV/HLS/non-seekable network media hide the audio button; finite duration must be known before a full range can be selected. A media item must have an accessible audio track.
- M3U imports follow HTTP redirects and detect Xtream get.php credentials at the destination, even if the returned body is an HLS manifest or account error. This enables Xtream catalog, categories and account information after shortened links. Browser/JavaScript/CAPTCHA shorteners are not supported. No provider link was supplied for live verification.
- Playback buffer limits and live manifest caching have not been changed.


- Configurable Download player button, defaulted before More; uses the existing download engines.
- Downloads uses only Active and Completed sections for torrents, clips and other videos, with consistent row controls.
- Every direct and yt-dlp download starts concurrently, with independent cancellation and pause controls and unique filenames.
- Separate YouTube audio/video streams are merged by FFmpeg stream copy without re-encoding. The UI shows Finalizing while publishing the combined file.
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

    .\gradlew.bat :app:assembleStandardCustom :app:testStandardCustomUnitTest -PenableX86=false --no-daemon --max-workers=2

Universal output: app/build/outputs/apk/standard/custom/app-standard-universal-custom.apk.

Keep feature changes in separate commits. Fetch upstream and review/merge upstream/master into custom-release, resolve conflicts, run checks and rebuild with the same signing key. Push customized code to origin/custom-release and the untouched upstream snapshot to origin/upstream-master. Preserve the existing fork master branch; do not reset it. Source bundles contain the final source; the local Git repository retains the checkpoint and merge history.

## Verification and limits

See Build-verification-2.7.2.6.json for the actual checks performed. Live IPTV providers, torrent swarms, platform access rules and Samsung S23 behavior require device testing. MAG support targets conventional MAC-authenticated portals; extra device identity or provider-specific authorization may require adaptations. Availability of expiry and concurrency data depends on the provider.

Clipping cannot recover past live segments no longer offered by the server, bypass DRM, or promise support for every codec. Encoding is necessary for frame-accurate boundaries that cannot be safely copied. Clip encoding has cancel/retry, not pause/resume. Android force-stop and foreground-service time limits can interrupt work. Offline torrent storage is private and removed when app data is cleared or the app uninstalled.

## Source and notices

The app remains AGPL-3.0-or-later. Share the modified source, LICENSE and third-party notices with redistributed APKs. The FFmpeg runtime includes additional third-party libraries; see THIRD_PARTY_CLIPPING.md for provenance and corresponding build/source references. No provider credentials or private signing material are included in the source bundle.

## Live stream timing and Play Protect

A live HLS playlist duration can grow by the segment duration (for example, 10 seconds). Brief pauses can mean playback has reached the currently published edge. The proxy also caches manifests for five seconds, which can delay observing new segments. These are candidate causes; the reported provider has not been traced, so no definitive diagnosis or buffer change is claimed.

The exact warning "Play Protect hasn't seen an app from this developer before. It may be unsafe" is Google's Uncommon classification, not the separate Scan app recommendation. A renamed app or a valid signature does not guarantee clearance. Keep the same release identity, distribute the verified APK and corresponding source, review permissions/dependencies, and if incorrectly classified request Google's review/appeal. No Google submission or scan has been performed on the user's behalf.

Official references:
- https://developers.google.com/android/play-protect/warning-strings
- https://developers.google.com/android/play-protect/warning-dev-guidance

Raw AAC (.aac/ADTS) lacks the gapless metadata used by M4A/MP3 to describe encoder delay and padding. The requested interval is trimmed before encoding, but raw AAC playback can include codec-frame padding. Choose M4A, MP3 or WAV when precise decoded clip length matters. WAV is uncompressed and larger; transcoding cannot recover lost source quality.

The M4A timeline is checked for the requested interval. Raw decoding of compressed audio can expose padding in its last codec frame; WAV has exact PCM sample boundaries. The supplied YouTube link is attempted in the Android test; see the build verification report for whether online access succeeded.

## Custom releases and updates

GitHub repository: https://github.com/ahmadrifat/mpvRx. Customized source is on custom-release; upstream-master retains untouched developer source. The original master branch is preserved. Updates for the custom build use only https://api.github.com/repos/ahmadrifat/mpvRx/releases/latest. Preview channel selection is hidden and ignored for custom builds. Automatic checks are enabled by default; users may disable them and check manually in About. A repository with no published stable release returns no update.

Release versions use upstream major.minor.patch plus our mod revision: 2.7.2.6, followed by 2.7.2.7, or 2.7.3.1 after an upstream version change. Publish each version under its own tag (v2.7.2.6), as a non-draft, non-prerelease GitHub release marked latest, with the signed mpvRx-2.7.2.6-ARM-universal.apk asset. Four numeric components are compared, so revision 10 follows revision 9. Drafts and prereleases are excluded by the latest-release endpoint. Do not publish original-package APKs in these custom releases.

The public version name is independent of Android's internal versionCode: this initial renamed release uses 70, higher than the previous 2.7.2-cs.6 build's 60. Increase customVersionCode for every subsequent APK; it must keep increasing even when upstream changes or mod revision resets. The universal code is customVersionCode times ten. Use the existing private release key. Publish modified source and third-party notices alongside every APK. Signing keys and local.properties remain private.

Inherited original-build workflows are gated to the author's repository, preventing them from publishing original-package builds in this fork. Custom releases are built and signed locally until a dedicated custom signing workflow is configured.
