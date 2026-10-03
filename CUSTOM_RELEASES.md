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
