# Helux DSH (Android)

[![Build APK](https://github.com/ywildan/HeluxDsh/actions/workflows/build-apk.yml/badge.svg)](https://github.com/ywildan/HeluxDsh/actions/workflows/build-apk.yml)

A small Android app that pairs with the **[Helux Tunnel](https://github.com/ywildan/dsh-tunnel)**
DSH plugin by scanning a QR code, then opens your DSH Web UI at
`https://helux.web.id`.

Pairing replaces copying a launch token by hand: the panel draws the token as a
QR, this app scans it once, DSH exchanges the token for a cookie, and every later
launch goes straight to the clean URL.

## How it behaves

1. **First launch** — a pairing screen explains what to do and offers *Scan QR
   code*. The camera is requested only when you tap it.
2. **Scan** — the QR is decoded offline with ZXing (no Google Play Services, no
   network call). The payload is validated before anything is loaded:
   `https`, host exactly `helux.web.id`, no credentials, default port, path `/`,
   and exactly one non-empty `token` parameter. Anything else is refused with a
   reason and you can scan again.
3. **Pair** — the validated URL is loaded once. DSH mints its session cookie and
   redirects to `https://helux.web.id/`. The tokenized URL is dropped from the
   WebView history and never stored.
4. **Later launches** — the app opens the clean URL directly. The cookie lives in
   the WebView cookie jar, so the device stays paired until the cookie expires
   (DSH's `cookieMaxAgeDays`, 30 days by default) or DSH is reset.
5. **Expired pairing** — a `401`/`403` on the main frame shows *This device is no
   longer paired* with a **Pair again** button instead of DSH's raw error page.
6. **Tunnel off** — a main-frame network error shows *Tunnel is not reachable*
   with **Try again**, so the app never looks broken when the tunnel is OFF.

## Safety properties

- Only `INTERNET` and `CAMERA` permissions; nothing else is declared.
- `android:usesCleartextTraffic="false"` — plain HTTP is impossible.
- WebView has file/content access disabled and `MIXED_CONTENT_NEVER_ALLOW`.
- Any navigation to another host opens in the system browser, never in the app's
  WebView.
- The launch token is never written to preferences, logs, or disk. `PairingStore`
  records only the hostname it paired with, so `adb backup`/logcat reveal nothing.
- No auto-pairing, no deep links, no background services, no analytics.

## Build the APK with GitHub Actions

The workflow is [`.github/workflows/build-apk.yml`](.github/workflows/build-apk.yml).
It runs on `ubuntu-latest` (Android SDK preinstalled), runs the unit tests, builds
a debug APK, and uploads it as an artifact.

```bash
git init
git add .
git commit -m "Helux DSH Android app"
git remote add origin git@github.com:ywildan/HeluxDsh.git
git push -u origin main
```

Then open **[Actions → Build APK](https://github.com/ywildan/HeluxDsh/actions/workflows/build-apk.yml)**
and press *Run workflow* (it also runs on every push to `main`). When it
finishes, download **`helux-dsh-debug-apk`** from the run's *Artifacts* section
and install it on the phone.

Or fetch the latest successful build from the command line:

```bash
gh run download --repo ywildan/HeluxDsh \
  --name helux-dsh-debug-apk --dir ~/Downloads/helux-apk
unzip -o ~/Downloads/helux-apk/*.zip -d ~/Downloads/helux-apk
# ~/Downloads/helux-apk/app-debug.apk
```

> The debug APK is signed with the standard Android debug key, so it installs
> with "install from unknown sources" enabled. For a release build, add your own
> keystore as repository secrets and extend the workflow - never commit a
> keystore.

## Build locally (optional)

Requires JDK 17 and the Android SDK (platform 34, build-tools 34):

```bash
./gradlew testDebugUnitTest
./gradlew assembleDebug
# app/build/outputs/apk/debug/app-debug.apk
```

## Troubleshooting

**The app closes immediately after tapping "Scan QR code".**
Fixed in 1.0.1: `ScanActivity` was missing from `AndroidManifest.xml`, so the
explicit `Intent` threw `ActivityNotFoundException`. Every launched component
must be declared; `scripts/check-manifest.py` now fails the build if one is not.

**Scanning finds nothing even though the QR is clearly in view.**
The camera delivers padded frames rotated against the screen. `FrameRotation`
packs the Y plane (ignoring `rowStride`) and rotates by
`ImageInfo.rotationDegrees` before ZXing sees it; `FrameRotationTest` covers all
four rotations against hand-checked patterns.

**Capturing a crash log**

```bash
adb logcat -c
adb logcat | grep -E "AndroidRuntime|id.helux.dsh"
```

Look for the first `FATAL EXCEPTION` block - it names the exact class and line.

## Project layout

| Path | Purpose |
| --- | --- |
| `app/src/main/java/id/helux/dsh/HeluxConfig.kt` | Fixed hostname, token parameter, preference keys |
| `app/src/main/java/id/helux/dsh/LaunchUrlValidator.kt` | Strict QR payload validation |
| `app/src/main/java/id/helux/dsh/ScanActivity.kt` | CameraX + ZXing scanner, one-shot delivery |
| `app/src/main/java/id/helux/dsh/MainActivity.kt` | Pairing screen, WebView, status panels |
| `app/src/main/java/id/helux/dsh/FrameRotation.kt` | Packs padded planes and rotates frames upright |
| `app/src/main/java/id/helux/dsh/PairingStore.kt` | Remembers the paired hostname only |
| `app/src/test/java/id/helux/dsh/LaunchUrlValidatorTest.kt` | 14 validation cases |
| `app/src/test/java/id/helux/dsh/FrameRotationTest.kt` | 11 frame geometry cases |
| `scripts/check-manifest.py` | Fails the build when a launched component is undeclared |

## Pairing checklist

1. On the computer, open the DSH Web UI at `http://127.0.0.1:3080` and open the
   **Helux Tunnel** panel.
2. Turn the tunnel **ON** and wait for the green state.
3. Press **Pair device (QR)** and scan the code with this app.
4. The QR hides itself after 60 seconds; press the button again if it disappeared.

The QR is password-equivalent: it grants access to your DSH session. Do not
photograph or share it. It also stops working when DSH restarts, because DSH
generates a new launch token per process.
