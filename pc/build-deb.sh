#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
rm -rf out/linux-x64 staging
mkdir -p out/linux-x64 staging/DEBIAN staging/usr/lib/screenpin-live staging/usr/bin staging/usr/share/applications

dotnet publish ScreenPinLive/ScreenPinLive.csproj -c Release -r linux-x64 --self-contained true \
  -p:PublishSingleFile=true -p:IncludeNativeLibrariesForSelfExtract=true -o out/linux-x64
cp -a out/linux-x64/. staging/usr/lib/screenpin-live/
cat > staging/usr/bin/screenpin-live <<'SH'
#!/bin/sh
exec /usr/lib/screenpin-live/ScreenPinLive "$@"
SH
chmod +x staging/usr/bin/screenpin-live staging/usr/lib/screenpin-live/ScreenPinLive
cat > staging/DEBIAN/control <<'CTRL'
Package: screenpin-live
Version: 0.1.1
Section: video
Priority: optional
Architecture: amd64
Maintainer: ScreenPin Live
Depends: libx11-6, libfontconfig1, libice6, libsm6, ffmpeg
Description: Real-time tracked display replacement
 Replaces a filmed physical display with the PC desktop using mobile tracking and homography.
CTRL
cat > staging/usr/share/applications/screenpin-live.desktop <<'DESKTOP'
[Desktop Entry]
Type=Application
Name=ScreenPin Live
Exec=screenpin-live
Terminal=false
Categories=AudioVideo;Utility;
DESKTOP
mkdir -p out
dpkg-deb --build --root-owner-group staging out/screenpin-live_0.1.1_amd64.deb
printf 'Built %s\n' "$PWD/out/screenpin-live_0.1.1_amd64.deb"
