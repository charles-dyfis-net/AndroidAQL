# Release & Google Play Publishing Guide

Maintainer reference for building signed releases, updating Play Store screenshots and metadata, and publishing to Google Play via Nix and Fastlane.

---

## 1. Development & Release Shell (Nix Flake)

[`flake.nix`](../flake.nix) provides a reproducible shell containing `fastlane` (with its own Nix-managed Ruby), Python 3 + Pillow, `ffmpeg`, `uv`, `oxipng`, `pngquant`, `resvg`, and DejaVu fonts—no OS Ruby installation is required:

```bash
nix develop
```

Or run single commands directly without entering an interactive shell:

```bash
nix develop --command fastlane lanes
```

---

## 2. Fastlane Release & Play Store Workflow

[`fastlane/Appfile`](../fastlane/Appfile) and [`fastlane/Fastfile`](../fastlane/Fastfile) orchestrate Play Store asset generation, validation, signed `.aab` builds, uploads, and track promotions for `net.dyfis.androidaql`.

### Lane Overview & Dependencies

In the common case, you only need the **Day-to-Day** lanes—they invoke asset compositing (`prepare_assets` equivalent) and release bundle building (`build_release` equivalent) automatically when needed:

| Lane | Purpose | Runs Implicitly | Needed in Common Case? |
| :--- | :--- | :--- | :--- |
| **`deploy`** | Build & upload release `.aab` to a track | Signed release `.aab` build (unless `aab:` given); Play asset refresh if `upload_metadata:true` | **Yes** — new builds (`internal` / `alpha`) |
| **`promote`** | Promote an existing release between tracks | Play asset refresh if `upload_metadata:true` | **Yes** — advancing tested builds |
| **`validate_metadata`** | Dry-run store listing & asset validation | Play asset refresh if missing/stale (`--only play_assets`) | **Yes** — pre-flight listing check |
| **`upload_metadata`** | Publish listing text, icon & screenshots | Play asset refresh if missing/stale (`--only play_assets`) | **Yes** — store listing updates |
| **`rebuild_screenshots`** | Recapture raw phone/tablet UI screenshots across AVDs | Debug APK build + all Play asset compositing (`--build`) | **Only when UI changes** (never run implicitly) |
| **`build_release`** | Build signed `.aab` locally without uploading | — | **No** (`deploy` builds automatically unless `aab:` is passed) |
| **`prepare_assets`** | Force re-render/compress Play PNGs without AVDs | — | **No** (`validate_metadata` / `upload_metadata` / `deploy upload_metadata:true` auto-refresh when stale) |

### Day-to-Day Release & Listing Lanes

1. **Validate metadata, graphics, and screenshots without publishing:**

   ```bash
   nix develop --command fastlane android validate_metadata
   nix develop --command fastlane android validate_metadata track:alpha
   ```

2. **Upload listing text, icon, feature graphic, and captioned screenshots:**

   ```bash
   nix develop --command fastlane android upload_metadata
   ```

3. **Build and upload a release `.aab` (and ProGuard/R8 mapping) to a Play track:**

   ```bash
   # Default track is "internal"; metadata/images are skipped unless upload_metadata:true
   nix develop --command fastlane android deploy track:internal
   nix develop --command fastlane android deploy track:alpha upload_metadata:true
   ```

4. **Promote an existing release between Play tracks without rebuilding:**

   ```bash
   nix develop --command fastlane android promote from:internal to:alpha
   nix develop --command fastlane android promote from:alpha to:production rollout:0.25
   ```

To attach release notes for a build, add `fastlane/metadata/android/en-US/changelogs/default.txt` (or `<versionCode>.txt`).

### Explicit / Standalone Lanes

* **Recapture raw UI screenshots across phone & tablet AVDs** (run manually after visible UI changes; emulator booting is never triggered implicitly by release/metadata lanes):

  ```bash
  nix develop --command fastlane android rebuild_screenshots
  ```

* **Build a signed `.aab` without uploading** (only needed for local inspection or manual `deploy aab:...`; `deploy` builds automatically when `aab:` is omitted):

  ```bash
  nix develop --command fastlane android build_release
  ```

* **Force Play asset compositing/compression locally** (only needed to inspect generated PNGs before committing or validating; `validate_metadata`, `upload_metadata`, and `deploy`/`promote` with `upload_metadata:true` run this automatically whenever outputs are missing or stale):

  ```bash
  nix develop --command fastlane android prepare_assets
  ```

### Track Policy & Tag Guardrail

* **`internal` (default):** Used for snapshot (`tag+<commits>`) builds (even `versionCode`, low bit `0`).
* **`alpha` (and `beta` / `production`):** Reserved for clean exact-tag builds (odd `versionCode`, low bit `1`, `versionName` with no `+<commits>` or `-dirty` suffix).
* **`deploy`** checks for a clean working tree on an exact git tag before invoking GPG or Gradle when targeting `alpha`, `beta`, or `production` (`skip_tag_check:true` overrides for pre-built tagged `.aab` uploads).
* **`promote`** moves an already-uploaded artifact between tracks without rebuilding (recommended when advancing an identical build from `internal` → `alpha` → `production`) and refuses to promote an even (untagged/dirty) `versionCode` into `alpha`/`beta`/`production` unless `skip_tag_check:true` is passed (e.g., for legacy pre-tag-bit builds).
* **`validate_metadata` / `upload_metadata`** automatically probe `production`, `beta`, `alpha`, `internal` via `google_play_track_version_codes` and select the latest `version_code` on the first populated track—no manual `track:` or `version_code:` required (though both can still be passed explicitly).

### Screenshots & Play Store Assets

Generated Play Store assets (`fastlane/metadata/android/*/images/` and `docs/play_store_screenshots/`) are intentionally gitignored so large binary assets stay out of git history. Tracked sources are:

* Adaptive launcher vector drawables ([`ic_launcher_background.xml`](../app/src/main/res/drawable/ic_launcher_background.xml) and [`ic_launcher_foreground.xml`](../app/src/main/res/drawable/ic_launcher_foreground.xml))
* Phone README WebPs under [`docs/screenshots/`](screenshots/)
* Play Store text metadata under [`fastlane/metadata/android/en-US/`](../fastlane/metadata/android/en-US/)

* **`fastlane android prepare_assets`** rasterizes `icon.png` (512×512 via `resvg` + lossless `oxipng`), builds `featureGraphic.png` (`docs/play_store_screenshots/feature_graphic.png`), and renders captioned PNG screenshots compressed with `pngquant` + `oxipng` from existing raw captures (tablet buckets are skipped if their gitignored raw captures are not present locally).
* **`fastlane android rebuild_screenshots`** builds the debug APK, boots/reuses Android SDK AVDs (`Pixel_7a`, `Tablet_7in`, `Tablet_10in`), recaptures all phone/tablet screenshots, and regenerates the Play Store assets.
* **Automatic Freshness Check:** `validate_metadata`, `upload_metadata`, and `deploy`/`promote` with `upload_metadata:true` automatically refresh Play assets before prompting for Play credentials whenever generated images are missing, older than their source inputs, or older than the latest git tag's commit time (override with `force_assets:true` or `skip_asset_refresh:true`). They also warn—without booting emulators—if raw captures predate the latest tag or tablet captures are absent.

---

## 3. GPG-Encrypted Credentials & Versioning

Both release keystore passwords and Google Play service-account credentials can be stored GPG-encrypted (and multi-recipient encrypted for all of your YubiKeys) so no plaintext secrets ever live on disk or enter Gradle's configuration cache.

* **GPG & YubiKey Setup:** `flake.nix` intentionally omits `gnupg` so `fastlane` and [`scripts/with-signing-env`](../scripts/with-signing-env) use your host `gpg` + `gpg-agent` (already paired with your YubiKey smartcards).
* **Encrypting `keystore.properties.gpg` to multiple YubiKeys:**

  ```bash
  # Prepare keystore.properties temporarily (or pipe via stdin), encrypt to all 3 keys, then remove plaintext
  gpg --encrypt --armor -r <KEY_1> -r <KEY_2> -r <KEY_3> \
      -o keystore.properties.gpg keystore.properties
  rm -P keystore.properties   # or srm/shred
  ```

  Supported properties inside `keystore.properties.gpg` (mapped to `ANDROID_KEYSTORE_FILE`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEYSTORE_ALIAS`, `ANDROID_KEY_PASSWORD` in memory):

  ```properties
  storeFile=release-upload.jks
  storePassword=...
  keyAlias=...
  keyPassword=...
  ```

* **Encrypting `play-service-account.json.gpg` to multiple YubiKeys:**

  ```bash
  gpg --encrypt --armor -r <KEY_1> -r <KEY_2> -r <KEY_3> \
      -o play-service-account.json.gpg play-service-account.json
  rm -P play-service-account.json
  ```

* **Fastlane & Gradle Integration:**
  * By default, Fastlane decrypts `play-service-account.json.gpg` in memory via `gpg` and passes `json_key_data` directly to `supply` (falling back to plaintext `play-service-account.json` or `PLAY_JSON_KEY_FILE` if no `.gpg` file is present).
  * For release builds (`build_release` / `deploy`), Fastlane decrypts `keystore.properties.gpg` into child-process environment variables and passes `--no-configuration-cache` to Gradle.
  * [`app/build.gradle.kts`](../app/build.gradle.kts) reads `ANDROID_KEYSTORE_*` (falling back to `keystore.properties` if present) **only** when a release/bundle task is requested, and marks release/bundle tasks `notCompatibleWithConfigurationCache` so decrypted passwords are never written under `.gradle/configuration-cache/`. Debug builds and unit tests keep the configuration cache active.
  * Override encrypted file paths or values anytime with `KEYSTORE_PROPERTIES_GPG`, `PLAY_JSON_KEY_GPG`, `PLAY_JSON_KEY_FILE`, `PLAY_JSON_KEY_DATA`, or `ANDROID_KEYSTORE_{FILE,PASSWORD,ALIAS}` / `ANDROID_KEY_PASSWORD`.

* **Versioning:**
  * `versionCode` for release/bundle tasks (`ReleaseVersionCodeSource`) uses 20-second committer-epoch buckets shifted left 1 bit plus bit 0 as an exact-tag flag (`((commit_epoch_sec / 20) * 2) + exact_tag_bit`) when the tracked working tree is clean:
    * Clean untagged commits (`tag+<commits>`) produce an **even** code (`...0`).
    * Clean exact-tag commits produce an **odd** code (`...1`), so tagging an already-uploaded commit bumps `versionCode` by `+1` cleanly on the same `~179,5xx,xxx` scale.
    * Dirty or non-git trees fall back to wall-clock time (`((now_sec / 20) * 2)`), always even (low bit `0`).
    * Override explicitly anytime with `ANDROID_VERSION_CODE=<int>`.
  * `versionName` is derived from `git describe --tags --always --dirty` (stripping a leading `v`). Tag releases (`vX.Y.Z`) before building so `versionName` is clean.

---

## 4. Direct Script Usage (Optional / Manual)

When running a specific sub-step directly outside Fastlane:

* **Manual signed Gradle release build:**

  ```bash
  ./scripts/with-signing-env ./gradlew bundleRelease --no-configuration-cache
  ```

* **Targeted screenshot/asset rebuilds** via [`scripts/update-play-store-screenshots`](../scripts/update-play-store-screenshots):

  ```bash
  nix develop --command ./scripts/update-play-store-screenshots --only phone
  nix develop --command ./scripts/update-play-store-screenshots --only tablet_7in
  nix develop --command ./scripts/update-play-store-screenshots --only tablet_10in
  nix develop --command ./scripts/update-play-store-screenshots --only feature_graphic
  nix develop --command ./scripts/update-play-store-screenshots --only play_assets
  ```
