# Custom mpvRx releases

This fork maintains the customized mpvRx app with package `app.gyrolet.mpvrx.cs`.
Download signed APKs from https://github.com/ahmadrifat/mpvRx/releases/latest.

## Branches

- `custom-release`: customized app source and release tags; repository default branch.
- `upstream-master`: untouched developer snapshot, currently `0e79b470`.
- `master`: preserved historical fork branch.

The local `origin` remote points to this fork. `upstream` points to
https://github.com/Riteshp2001/mpvRx; its push URL is disabled locally.
Fetch upstream changes, inspect them, merge into `custom-release`, test, then
push this branch. Update `upstream-master` only to an exact upstream commit.
An upstream force-push requires review; do not blindly reset custom source.

## Versioning and publication

Use `upstream.major.minor.patch.modRevision`, for example `2.7.2.6`.
Each version gets a new Git tag and a separate stable GitHub release:
`v2.7.2.6`, `v2.7.2.7`, etc. Mark the newest supported stable release as latest.
Publish the signed ARM universal APK, modified source, notices and checksums.
The app checks the GitHub latest stable release endpoint and compares all four
version components numerically. It does not use the developer's update feeds.
Automatic checks default to enabled; About also provides a manual check.

Keep Android's internal `customVersionCode` increasing independently of the
display version. The initial `2.7.2.6` release uses build number 7 (universal
versionCode 70), upgrading the prior `2.7.2-cs.6` APK (versionCode 60).
Future display-version resets must not reset Android's versionCode.

Use the same private release signing key for every update. Do not commit keys,
passwords or local.properties. Inherited original-package release workflows
are gated to the upstream repository; this fork uses locally signed custom
builds until a dedicated custom workflow is configured.

mpvRx is originally developed by Ritesh Pandit and contributors. This fork
retains the AGPL-3.0-or-later license and third-party notices. See
CUSTOM_BUILD.md and THIRD_PARTY_CLIPPING.md for build and dependency details.

## MAG compatibility update: 2.7.2.7

VersionCode 80 updates version 2.7.2.6 (code 70). Refresh an existing MAG
playlist after installation to update category labels. The client fetches
genre names, honors static versus temporary-link flags, refreshes tokenized
catalog URLs at playback, and retains account-scoped session cookies.
The opt-in StalkerLiveTest takes MPVRX_TEST_MAG_PORTAL, MPVRX_TEST_MAG_MAC and
MPVRX_TEST_MAG_CHANNEL from the test process environment; never commit real
provider credentials or temporary stream URLs to fixtures or release notes.

## Unified downloads and live recordings: 2.7.2.9

VersionCode 100 upgrades the signed 2.7.2.8 build (code 90). Finite media uses
one Download control with Video/Audio tabs, source format/resolution choices,
full-range defaults and the existing precision/crop editor. Audio offers M4A,
MP3, WAV and AAC. Compatible whole audio can retain its original encoding;
other compressed exports use 192 kbps. Filename, optional artist, artwork and
per-job local destination choices persist with job history. Local whole video
copies preserve the source bytes. Settings do not migrate existing files.

Live media uses a theme-tinted Record control. Recording owns a connection and
portal registration independently of playback, writes Matroska with stream
copy, and exposes Stop in the popup, Downloads and its notification. Its
filename, format and destination are locked while recording. Failed/interrupted
recordings never automatically resume; any recoverable saved portion remains
available in the selected local folder. Cloud destinations are excluded.

Video edits retain the current Media3/FFmpeg precision pipeline and its MP4
output. Source quality selection is input selection, not a promise to retain
the source codec after precision/crop processing. The popup displays this.
Clips/audio exports support cancellation and retry; their processing does not
support pause/resume. Ordinary yt-dlp downloads retain pause/resume support.

### 2.7.2.9 popup revision

The existing release is refreshed in place without changing its displayed
version or package/signing identity. Download/record panels use 80% of available
height. Download tabs remain fixed, reset the content scroll position, and
format choices use a height-bounded scrollable menu. Author is always visible;
thumbnail selection supports source artwork, a local image or an external URL.
Filename/rename fields wrap long text and preserve cursor selection.

The final popup revision puts the destination above the save actions, preserves
scroll on active-tab taps, filters missing/null author metadata, and presents
thumbnail source/storage/link buttons around an outlined source field with a
preview. Choosing Storage commits the new thumbnail only after image selection.
Streaming source classification is independent of duration/seekability, so
Download and Record can coexist as streams accumulate buffered playback.
