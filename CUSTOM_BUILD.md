# mpvRx Custom

Personal fork based on upstream commit `83332ed3` (mpvRx 2.7.2).

## Included changes

- Xtream HLS/TS selection, account status/expiry/connection checks, and live-channel API fallback when the playlist endpoint returns an HLS manifest or unusable catalog.
- Xtream account URLs imported through the playlist-link form use the saved account path.
- Standalone HLS manifests can be saved as one stream instead of importing their segments as videos.
- MAG/Stalker live-TV portal form with name, URL, MAC and optional user agent; portal checks; fresh playback-link resolution; existing stream/HLS proxy integration.
- Playlist refresh on opening, throttled to 15 minutes, with a per-playlist switch. Pull-to-refresh remains available. Cached entries remain available after a failed refresh.
- Torrent media retained in private app storage and downloaded by a foreground service after the player closes. Downloads includes completion badges, offline playback, resume, and separate video/metadata deletion choices. Saved torrent metadata/resume files are retained where the engine supplies them.
- A download button in portrait and landscape video controls using the existing direct/yt-dlp engines. yt-dlp history survives application restarts; interrupted downloads can be retried.
- Fast lossless clip export, alongside the existing precise/cropped export.

## Installation and updates

The custom build uses package `app.gyrolet.mpvrx.custom` and label **mpvRx Custom**. It can coexist with the original application. Its settings and downloads are separate. Original APKs are signed with another key and cannot update this custom application. The upstream updater is disabled.

Build with JDK 17+ supported by the Gradle wrapper and the SDK/NDK versions in `app/build.gradle.kts`:

```powershell
.\gradlew.bat :app:assembleStandardCustom :app:testStandardCustomUnitTest --no-daemon --max-workers=2
```

Universal output: `app/build/outputs/apk/standard/custom/app-standard-universal-custom.apk`.

Local private signing material is in `.custom-signing/release.jks`; signing configuration is in ignored `local.properties`. **Back up both privately. Do not include them when sharing source or APKs.** If that configuration is absent, Gradle uses the local debug key, which cannot replace this privately signed APK.

For the next custom release, preserve the package and signing key, increase `-PcustomVersionCode=2` (then 3, etc.), and update the custom version-name suffix. The universal APK in the first release has version code 10.

## Practical limits

- This is a test build. Desktop compilation, APK signature/architecture checks and local Xtream fixture tests do not validate real phones, providers, torrent swarms or platform downloads.
- MAG support targets conventional MAC-authenticated live-TV portals. Portals requiring extra serial/device identifiers, provider-specific authorization or login fields may need adaptations. Expiry is displayed only when the portal supplies it.
- Fast lossless trimming copies encoded audio/video samples into MP4. The start snaps to the preceding video keyframe. Unsupported codecs/tracks produce an error rather than silently converting. Crop and precise export use Media3. Container metadata and timestamps are not promised to remain identical.
- Platform downloads depend on the installed yt-dlp runtime and the site's current access rules. A running foreground service does not guarantee survival of a force-stop or Android timeout. Reopen Downloads to resume interrupted torrents or retry platform downloads.
- Torrent downloads currently keep the selected media file. Switching files in the same torrent can pause a previous incomplete download. Completed files remain available. Close playback before deleting its torrent files.
- Offline torrent media is app-private; uninstalling or clearing app data removes it.

## Source and license

The application remains AGPL-3.0-or-later. Keep the license/notices and provide the corresponding modified source alongside APKs you redistribute. The source bundle excludes private signing material and build caches. Dependencies and native distributions are fetched using the existing Gradle configuration.
