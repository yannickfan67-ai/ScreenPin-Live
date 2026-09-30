# ScreenPin Live

Real-time screen replacement for filming displays without moiré.

- **Android**: CameraX + OpenCV detects a display-like quadrilateral, overlays the tracking result, JPEG-encodes the camera frame, and sends the frame + exact normalized corners to the PC.
- **PC**: Avalonia app accepts the mobile stream, captures the primary desktop, computes a projective transform (homography), replaces the tracked quadrilateral in real time, previews the result, and can pipe frames to FFmpeg for MP4 recording.
- **Discovery**: PC broadcasts a LAN beacon over UDP so the Android app can auto-find it.

## Protocol / ports

- TCP `45900`: camera frames and normalized corner coordinates.
- UDP `45901`: discovery beacon (`SCREENPIN|1|45900`).

The TCP packet is big-endian:

```
4 bytes  magic = SPL1
int32    version = 1
int64    timestampNs
int32    width
int32    height
float32  TL.x, TL.y, TR.x, TR.y, BR.x, BR.y, BL.x, BL.y
int32    jpegLength
byte[]   JPEG frame
```

Coordinates are normalized to `[0,1]` in the transmitted, rotation-corrected JPEG frame.

## Android build

Requirements: JDK 17+, Android SDK platform 35 and Build Tools 36, Gradle 9.6.

```bash
cd android
gradle :app:assembleDebug
```

APK: `android/app/build/outputs/apk/debug/app-debug.apk`

The project uses official OpenCV Android AAR from Maven Central (`org.opencv:opencv:4.14.0`) and CameraX 1.6.2.

## PC build

Requirements: .NET 10 SDK. The app is pure managed except OS screen-capture calls (GDI on Windows / X11 on Linux).

### Windows x64 single-file EXE

```bash
cd pc
dotnet publish ScreenPinLive/ScreenPinLive.csproj -c Release -r win-x64 --self-contained true -p:PublishSingleFile=true -p:IncludeNativeLibrariesForSelfExtract=true -o out/win-x64
```

### Linux x64 + DEB

```bash
cd pc
./build-deb.sh
```

The Linux capture backend currently targets **X11**. On native Wayland, start the app through an X11 session/XWayland or add a PipeWire capture backend later.

### Recording

Preview/replacement does not require FFmpeg. The **Record** button uses `ffmpeg` from `PATH` to write H.264 MP4. The Debian package declares `ffmpeg` as a dependency.

## Use

1. Start the PC app. It immediately listens on TCP 45900 and broadcasts discovery beacons.
2. Open the Android app and grant camera permission.
3. Tap **Auto find PC**, then **Connect** if needed.
4. Point the camera at the display. The green quadrilateral is the current tracked replacement surface.
5. Tap **Freeze quad** when you want to stop automatic re-detection and keep the last corners fixed.
6. The PC preview shows its primary desktop perspective-warped into that quadrilateral in real time.

## Current MVP limitations

- Detector picks the strongest large rectangular contour; reflections, bezel-less displays, or extreme perspective can confuse it.
- One tracked screen at a time.
- PC source is the primary desktop; monitor/window selection is the next logical addition.
- No foreground occlusion mask yet, so a hand passing in front of the physical display will currently be covered by the replacement.
- The CPU homography compositor favors portability over maximum FPS. A Vulkan/D3D11 shader path is the next performance step.
