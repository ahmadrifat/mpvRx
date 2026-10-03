# Clipping runtime provenance

The executable FFmpeg/FFprobe and library archives for arm64-v8a, armeabi-v7a, x86 and x86_64 originate from the published youtubedl-android 0.19.0 artifacts by deniscerri:

- https://repo.maven.apache.org/maven2/io/github/deniscerri/youtubedl-android/ffmpeg/0.19.0/ffmpeg-0.19.0.aar
- https://repo.maven.apache.org/maven2/io/github/deniscerri/youtubedl-android/library/0.19.0/library-0.19.0.aar
- Project source and notices: https://github.com/deniscerri/youtubedl-android
- Native build scripts and recipes: https://github.com/deniscerri/youtubedl-android/tree/master
- FFmpeg source/license: https://ffmpeg.org/download.html and https://ffmpeg.org/legal.html
- Termux package build recipes, sources and patches: https://github.com/termux/termux-packages/tree/master/packages

The ffmpeg archive is supplemented with missing top-level non-Python shared libraries from the same-version Python artifact (including Expat/OpenSSL). Existing FFmpeg entries are preserved. Unix library symlinks are materialized as copies during runtime extraction. No executable source is modified.

FFmpeg was built with GPL codecs including x264; applicable upstream GPL/LGPL and individual dependency licenses remain in effect. The application itself is AGPL-3.0-or-later. Preserve upstream notices and make the relevant corresponding sources/build scripts available when distributing native binaries. This file is a provenance index, not a replacement for dependency licenses.

Release 3 trims the archive to the recursive shared-library dependencies of FFmpeg and FFprobe, excluding Android-provided system libraries. Recreate the archives using tools/prepare_clipping_runtime.py, then tools/prune_clipping_runtime.py --readelf <NDK llvm-readelf executable>. The phone APK bundles ARM32 and ARM64; source retains all four architectures.
