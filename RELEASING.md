# Releasing SkyRay for Android on Google Play

For anyone who builds SkyRay and uploads it to Google Play on the owner's behalf. The Play bundle
is signed with SkyRay's own upload key, so Google Play treats it exactly as a release from the owner.

## What you need

- **Access**, from the owner:
  - GitHub: `allionapp/skyray-android` (this repository) and `allionapp/AndroidLibXrayLite` (the
    Xray core, a submodule).
  - Play Console: a user on the SkyRay app (`com.allion.skyray`) allowed to create and roll out
    releases.
- **The upload key**: two files from the owner, sent privately. Put both in `V2rayNG/`. They are in
  `.gitignore`; never commit, share or upload them anywhere else.
  - `skyray-release.jks`: the key itself.
  - `keystore.properties`:
    ```
    storeFile=skyray-release.jks
    storePassword=…
    keyAlias=…
    keyPassword=…
    ```
- **The native libraries** in `V2rayNG/app/libs/` (`libv2ray.aar` plus the per-ABI
  hev-socks5-tunnel libraries), also from the owner: they are not in git. Or rebuild the core (last
  section).
- JDK 17 and the Android SDK (compileSdk 37). Android Studio provides both.

## Check the key once, before the first build

```
cd V2rayNG
./gradlew :app:signingReport
```

Under `Variant: playRelease` it must say `Config: upload` and `SHA-256: 71:74:BB:CD:A3:E8:37:8B…`.
If it does, every Play build from this checkout is signed with SkyRay's key.

## Build

1. In `V2rayNG/app/build.gradle.kts`, raise `versionCode` and `versionName` together:
   `versionCode = 4000000 + the version's digits` (1.2.3 → `4000123`). It must be higher than every
   versionCode already on Play, on any track.
2. Build:
   ```
   cd V2rayNG
   ./gradlew :app:testPlayDebugUnitTest
   ./gradlew :app:bundlePlayRelease
   ```
   The two `UtilsTest` tests `test_isIpAddress` and `test_IsIpInCidr` fail on upstream v2rayNG as
   well; every other test must pass.
3. The bundle is `V2rayNG/app/build/outputs/bundle/playRelease/app-play-release.aab`. Check that it
   is signed with SkyRay's key:
   ```
   keytool -printcert -jarfile app/build/outputs/bundle/playRelease/app-play-release.aab | grep SHA256
   ```
   It must start with `71:74:BB:CD:A3:E8:37:8B`. Google Play refuses a bundle signed with any other
   key. If `keystore.properties` is missing, the bundle comes out unsigned.

## Upload

Play Console → SkyRay → Test and release → **Internal testing** (to try it on a phone first) or
**Production** → Create new release → upload the `.aab` → release notes in `en-US` and `fa-IR` →
Next → Save → send for review / start the rollout.

## Signing problems

| What you see | Why | Fix |
|---|---|---|
| `playRelease` shows `Config: null`, or Play says the bundle is not signed | `keystore.properties` is not in `V2rayNG/` | Put it there, next to `skyray-release.jks` |
| Build stops: `SkyRay upload key: … not found next to …` | `storeFile` names a file that is not next to `keystore.properties` | Put `skyray-release.jks` next to it, or fix `storeFile` |
| `Keystore was tampered with, or password was incorrect` / `Cannot recover key` | A wrong `storePassword` / `keyPassword` / `keyAlias` | Copy the values again, exactly as received (no spaces) |
| Play: "signed with the wrong key" | Built with another key (a debug key, or a key Android Studio created) | Run the signing report; build only with the command above |
| Play: "Version code … has already been used" | `versionCode` not raised | Raise it above every versionCode on Play |

Never create a new key and never change this one. Keep the two files backed up in a safe place. If
the key is lost or leaks, the owner requests an upload key reset in Play Console (App integrity →
Play app signing); Google keeps the app signing key, so people's installs are not affected.

In Android Studio instead of the command line: Build → Generate Signed App Bundle → Android App
Bundle → choose `skyray-release.jks` and the alias and passwords from `keystore.properties` → build
variant `playRelease`. The result must pass the same `keytool` check.

## Rules

- The Play app is this repository: package `com.allion.skyray`, versionCodes `4000xxx`. Never release
  the older app in `v2rayproject/android` over it.
- This key signs **only the Play bundle**. The direct APKs (the download page and Telegram links)
  are signed with the developer's key by the developer's own pipeline (`ethavpn-app` CI → GitHub
  release → the server's publish job). Never sign or publish direct APKs with this key: they would
  not install over the version people already have.
- Android direct downloads come **only** from `https://fra.mobileiphonez.org/dl/`.

## Rebuilding the Xray core (only when it changes)

`AndroidLibXrayLite` (commit `9cc28ff`, which adds `RegisterSocketProtector`, required by the Play
build), with `geoip.dat`, `geosite.dat` and `geoip-only-cn-private.dat` in its `assets/`. Needs Go,
gomobile (`gomobile init`) and Android NDK 28:

```
cd AndroidLibXrayLite
gomobile bind -androidapi 24 -target android -trimpath -ldflags='-s -w -buildid= -checklinkname=0' \
  -o ../V2rayNG/app/libs/libv2ray.aar ./
```
