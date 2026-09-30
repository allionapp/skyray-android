# Releasing SkyRay for Android

## Once: keys and secrets (the operator, off the server)

1. **Release keystore** — never reuse anything, never keep it on the server:
   ```bash
   keytool -genkeypair -v -keystore ethavpn-release.jks -alias ethavpn -keyalg RSA -keysize 4096 -validity 10000
   base64 -w0 ethavpn-release.jks > ethavpn-release.jks.b64
   ```
   A lost keystore means a new package id for every customer; a stolen one means trojan updates.
   Back it up encrypted (`age -p ethavpn-release.jks > ethavpn-release.jks.age`) somewhere that
   is not the server.
2. **Release GPG key** (signs the APKs, `latest.json` and identifies the download page's files):
   ```bash
   gpg --quick-generate-key "EthaVPN release <release@example.invalid>" ed25519 sign 5y
   gpg --armor --export-secret-keys <fingerprint> > ethavpn-release-gpg.asc     # → secret GPG_PRIVATE_KEY
   gpg --armor --export <fingerprint> > ethavpn-release-key.asc                 # published with every release
   ```
   Publish the fingerprint in the channel once; the server pins it (`/etc/ethavpn-app.fpr`).
3. **Repository secrets** (Settings → Secrets and variables → Actions): `APP_KEYSTORE_BASE64`
   (the .b64 file's content), `APP_KEYSTORE_PASSWORD`, `APP_KEYSTORE_ALIAS` (`ethavpn`),
   `APP_KEY_PASSWORD`, the Allion LLC keystore as `ALLION_KEYSTORE_BASE64`, `ALLION_KEYSTORE_PASSWORD`,
   `ALLION_KEY_ALIAS` (`skyray`), `ALLION_KEY_PASSWORD` (see **Signing keys** below), `GPG_PRIVATE_KEY`
   (the armored secret key), and `GPG_PASSPHRASE` when the GPG key has one (leave it out for a key
   without a passphrase).
4. **Deploy key** for the server's working copy (`/opt/ethavpn-app`, remote `github`), like the
   server's other repos: `ssh-keygen -t ed25519 -f /root/.ssh/gh-ethavpn-app -N ''`, add the public
   key as a deploy key with write access, alias `gh-ethavpn-app` in `/root/.ssh/config`.

## Every release

1. In `V2rayNG/app/build.gradle.kts` bump `versionName` (semver) and `versionCode` (4000000 + the version's
   digits: 1.2.3 → 4000123; it must also be above anything ever uploaded to Google Play). If old
   versions must stop working (a server-side change they cannot follow), raise
   `min_supported.txt` to the lowest version that still works — the app then insists on the
   update. Write two lines in `RELEASE_NOTES.md` (shown in the update dialog).
2. Commit and push `main`; the push build must be green (unit tests + APKs).
3. Tag and run the release workflow:
   ```bash
   git tag vX.Y.Z && git push github vX.Y.Z
   ```
   Actions → **Build APK and Play bundle** → Run workflow → `release_tag` = `vX.Y.Z`. The release gets
   `SkyRay_X.Y.Z_<abi>.apk` (+ `.sig`), `SkyRay_X.Y.Z_play.aab` (+ `.sig`, the Google Play bundle — see
   below), `latest.json` (+ `.sig`), `ethavpn-release-key.asc`,
   `release-key-fingerprint.txt`, `signing-cert-sha256.txt`.
4. On the server: `ethavpn-app-publish vX.Y.Z` (verifies the signatures against the pinned key
   and every sha256, installs under `/var/www/html/dl/`, prints the channel post). The bot picks
   the new APK up by itself; phones learn about it within a day.
5. `signing-cert-sha256.txt` has two lines, the Ethavpn and the Allion certificate (see **Signing keys**):
   both must be in `/var/www/html/.well-known/assetlinks.json` (App Links), with Play's App signing key —
   see the server runbook (`/opt/staging/app-launch/APPLY.md`, step 5).

## Signing keys

- **Allion LLC key** (`ALLION_*`, alias `skyray`, SHA-256 `71:74:BB:CD:…:6B:36:53`): signs the Play bundle
  (Google Play's upload key) and is the direct APKs' signer on Android 9+.
- **Ethavpn key** (`APP_KEYSTORE_*`, SHA-256 `F7:CB:08:E1:…:23:F6:FC`): signed every direct APK up to 1.1.9.
  Phones that have one of those accept an update only from the same key, so the build **rotates**: `apksigner
  rotate` writes the proof that the Ethavpn key hands over to the Allion key, and every direct APK is
  re-signed with `--next-signer` (Allion), `--lineage` and `--rotation-min-sdk-version 28`. Android 9+
  updates in place and trusts the Allion key from then on; Android 7–8 (API 24–27) cannot rotate and keep
  seeing the Ethavpn signature. The build fails unless every APK shows exactly that.
- **Keep both keystores and their passwords forever** (encrypted, off the server). The Ethavpn key still
  signs for Android 7–8 and the proof: losing it breaks updates on those phones. Never rotate back.
- **1.2.2 (2026-09-29) was the first rotated release.** Before any release, install the push build's arm64 APK
  (Actions → the run → `release-files`) over the served version on a phone: it must offer **Update** and keep
  the imported link.
- **If Play answers "signed with the wrong key"**, Play still expects the old upload key: Play Console →
  App integrity → App signing → **Request upload key reset**, with the Allion certificate
  (`keytool -export -rfc -keystore skyray-release.jks -alias skyray -file upload_certificate.pem`, on
  the operator's machine). Never upload or share the keystore itself.

## Google Play

Every release also carries `SkyRay_X.Y.Z_play.aab`, the `play` flavor as an Android App Bundle signed
with the Allion LLC key (Play's upload key; see **Signing keys**): no in-app updater and no `REQUEST_INSTALL_PACKAGES` (Play's policy), the
update row opens the Play listing, the same versionCode as the APKs (4000000 + build number) so a phone
can move between a direct install and a Play install. Nothing on the server touches it: download it from
the GitHub release page and upload it in the Play Console (Release → a testing track or Production →
Create new release). Play App Signing re-signs what it installs with Google's key, so Play installs carry
Google's certificate, not the upload key's: its SHA-256 (the App signing page, or read off a Play-installed
phone) must be in `/var/www/html/.well-known/assetlinks.json` next to the Ethavpn and Allion ones, or the link
stops opening the Play-installed app. Listing texts, graphics and the console checklist: `store/play/`.

## Rolling back

`ethavpn-app-publish vX.Y.(Z-1)` on the server: the previous files are still there, the
symlinks and `latest.json` point back. Phones that already updated keep the newer version
(Android does not downgrade); make the next release fix forward.

## Rebasing on upstream

```bash
git remote add upstream https://github.com/2dust/v2rayNG.git
git fetch upstream --tags
git rebase <new tag>          # resolve conflicts in the files README.md lists, run the unit tests
git submodule update --init --recursive
```
Upstream's `master` moved to a Compose UI after 2.2.6; the fork's `HomeActivity` is plain
ViewBinding and does not depend on `MainActivity`'s internals, only on `MainViewModel`,
`CoreServiceManager` and `AngConfigManager`.
