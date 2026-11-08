# Helux DSH (Android)

[![Build APK](https://github.com/ywildan/HeluxDsh/actions/workflows/build-apk.yml/badge.svg)](https://github.com/ywildan/HeluxDsh/actions/workflows/build-apk.yml)

A small Android app that pairs with the **[Helux Tunnel](https://github.com/ywildan/dsh-tunnel)**
DSH plugin by scanning a QR code, then opens your DSH Web UI on **your own
hostname**.

Pairing replaces copying a launch token by hand: the panel draws the token as a
QR, this app scans it once, DSH exchanges the token for a cookie, and every later
launch goes straight to the clean URL.

## Works with any domain

One APK serves every deployment. The **first QR you scan binds the hostname** to
this device (trust on first use); every later scan must match it:

- Unpaired, any valid `https` host is accepted, so someone running their own
  tunnel can pair this build to `dsh.example.com`.
- Once paired, a QR for a *different* host is refused with
  *"This device is paired with …"*, so a hostile QR cannot move an
  already-paired device to a look-alike server.
- **Pair again** on the main screen clears the binding so you can switch servers
  deliberately.

That is why the APK contains no domain constant: the QR is the configuration.

## How it behaves

1. **First launch** — a pairing screen explains what to do and offers *Scan QR
   code*. The camera is requested only when you tap it. The first scan decides
   which server this device trusts.
2. **Scan** — the QR is decoded offline with ZXing (no Google Play Services, no
   network call). The payload is validated before anything is loaded:
   `https`, no credentials, default port, path `/`, exactly one non-empty
   `token` parameter, and a host that matches the paired one. Anything else is
   refused with a reason and you can scan again.
3. **Pair** — the validated URL is loaded once. DSH mints its session cookie and
   redirects to `https://<host>/`. The tokenized URL is dropped from the WebView
   history and never stored; only the hostname is remembered.
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
- A paired device cannot be re-pointed at another server without an explicit
  **Pair again**, so scanning a hostile QR fails instead of silently migrating.
- No auto-pairing, no deep links, no background services, no analytics.

## Build the APK with GitHub Actions

The workflow is [`.github/workflows/build-apk.yml`](.github/workflows/build-apk.yml).
It runs on `ubuntu-latest` (Android SDK preinstalled), runs the unit tests
(including Robolectric tests that actually launch both Activities), builds **both**
a debug and a release APK, verifies the packaged files, and uploads both as
artifacts.

```bash
git init
git add .
git commit -m "Helux DSH Android app"
git remote add origin git@github.com:ywildan/HeluxDsh.git
git push -u origin main
```

Then open **[Actions → Build APK](https://github.com/ywildan/HeluxDsh/actions/workflows/build-apk.yml)**
and press *Run workflow* (it also runs on every push to `main`). When it
finishes, download an artifact from the run's *Artifacts* section and install it
on the phone:

| Artifact | File | Signing |
| --- | --- | --- |
| `helux-dsh-release-apk` | `app-release.apk` | your release key, or the debug key when no key is configured |
| `helux-dsh-debug-apk` | `app-debug.apk` | standard Android debug key |

Or fetch the latest successful build from the command line:

```bash
gh run download --repo ywildan/HeluxDsh \
  --name helux-dsh-release-apk --dir ~/Downloads/helux-apk
unzip -o ~/Downloads/helux-apk/*.zip -d ~/Downloads/helux-apk
# ~/Downloads/helux-apk/app-release.apk
```

> Both APKs are always signed, so either one installs with "install from unknown
> sources" enabled. A release APK signed with the debug key is fine for
> sideloading; add a real keystore before distributing it anywhere.

### Optional: sign the release APK with your own key

Add these repository secrets (*Settings → Secrets and variables → Actions*). The
workflow skips the step cleanly when they are absent, and the release APK falls
back to the debug key:

| Secret | Value |
| --- | --- |
| `HELUX_KEYSTORE_BASE64` | `base64 -w0 your-release.jks` |
| `HELUX_KEYSTORE_PASSWORD` | keystore password |
| `HELUX_KEY_ALIAS` | key alias |
| `HELUX_KEY_PASSWORD` | key password |

Never commit a keystore: `*.jks`, `*.keystore`, and `keystore.properties` are
git-ignored.

## Build locally (optional)

Requires JDK 17 and the Android SDK (platform 34, build-tools 34). Point Gradle at
the SDK with `local.properties`:

```properties
sdk.dir=/path/to/android-sdk
```

```bash
./gradlew testDebugUnitTest          # unit + Robolectric launch tests
./gradlew assembleDebug assembleRelease
# app/build/outputs/apk/debug/app-debug.apk
# app/build/outputs/apk/release/app-release.apk

# Check the built files: signature, version, declared components, permissions
./scripts/verify-apk.sh app/build/outputs/apk/release/app-release.apk
```

For a locally signed release build, create a keystore and a git-ignored
`keystore.properties` at the repo root (the same four values the CI secrets
supply):

```bash
keytool -genkeypair -keystore "$HOME/helux-release.jks" -alias helux \
  -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Helux DSH, O=Helux, C=ID"

cat > keystore.properties <<EOF
storeFile=$HOME/helux-release.jks
storePassword=CHANGE_ME
keyAlias=helux
keyPassword=CHANGE_ME
EOF
```

Gradle reads either `keystore.properties` or the `HELUX_KEYSTORE_FILE`,
`HELUX_KEYSTORE_PASSWORD`, `HELUX_KEY_ALIAS`, `HELUX_KEY_PASSWORD` environment
variables, so `assembleRelease` never needs a keystore to succeed.

## Troubleshooting

**The app closes immediately on launch.**
Fixed in 1.0.2. Two separate causes were fixed, in this order:

1. *1.0.1* - `ScanActivity` was missing from `AndroidManifest.xml`, so the
   explicit `Intent` threw `ActivityNotFoundException` as soon as you tapped
   *Scan QR code*. `scripts/check-manifest.py` now fails the build if a launched
   component is undeclared, and the APK verification step re-reads the packaged
   manifest to confirm both activities are inside the shipped file.
2. *1.0.2* - the real reason it still closed **before drawing anything**:
   `MainActivity` and `ScanActivity` declared their panels as `FrameLayout` while
   the layouts use `ScrollView` and `LinearLayout`, so `findViewById` threw
   `ClassCastException: LinearLayout cannot be cast to FrameLayout` inside
   `onCreate`. The fields are now typed `View` (only `visibility` is ever used),
   and `ActivityLaunchTest` builds both Activities on the JVM so this class of
   bug fails the build instead of the phone.

**Is `id.helux.dsh.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` expected?**
Yes. AGP adds it to every app targeting SDK 33+, so dynamically registered
receivers default to `NOT_EXPORTED`. It is signature-level, granted
automatically, and never shown to the user. The APK check allowlists exactly
this permission and still fails on any other.

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
| `app/src/main/java/id/helux/dsh/HeluxConfig.kt` | Token parameter, preference keys, URL building (no hostname constant) |
| `app/src/main/java/id/helux/dsh/LaunchUrlValidator.kt` | Strict QR payload validation |
| `app/src/main/java/id/helux/dsh/ScanActivity.kt` | CameraX + ZXing scanner, one-shot delivery |
| `app/src/main/java/id/helux/dsh/MainActivity.kt` | Pairing screen, WebView, status panels |
| `app/src/main/java/id/helux/dsh/FrameRotation.kt` | Packs padded planes and rotates frames upright |
| `app/src/main/java/id/helux/dsh/PairingStore.kt` | Remembers which hostname this device paired with |
| `app/src/test/java/id/helux/dsh/LaunchUrlValidatorTest.kt` | 18 validation cases, unpaired and paired |
| `app/src/test/java/id/helux/dsh/FrameRotationTest.kt` | 11 frame geometry cases |
| `app/src/test/java/id/helux/dsh/ActivityLaunchTest.kt` | Robolectric launch tests for both Activities |
| `scripts/check-manifest.py` | Fails the build when a launched component is undeclared |
| `scripts/verify-apk.sh` | Checks a built APK's signature, version, components, and permissions |

## Pairing checklist

1. On the computer, open the DSH Web UI at `http://127.0.0.1:3080` and open the
   **Helux Tunnel** panel.
2. Turn the tunnel **ON** and wait for the green state.
3. Press **Pair device (QR)** and scan the code with this app.
4. The QR hides itself after 60 seconds; press the button again if it disappeared.

The QR is password-equivalent: it grants access to your DSH session. Do not
photograph or share it. It also stops working when DSH restarts, because DSH
generates a new launch token per process.

## License

[MIT](LICENSE). Build it, sign it with your own key, and pair it with your own
tunnel.

## Known-issue fix: first pairing opened Chrome

DSH answers `https://<host>/?token=…` with a `303` to `./`. The WebView client
decided whether that redirect stayed in-app by comparing it against the
*stored* paired authority - which is still empty on a first pairing. The
redirect therefore looked like an off-host link and was handed to the system
browser, which loaded the clean URL without its token and answered
`dsh web authentication required`.

The authority from the in-flight scan now counts immediately, so the redirect
stays in the WebView and the cookie is minted there. See
`NavigationPolicy` and its tests.
