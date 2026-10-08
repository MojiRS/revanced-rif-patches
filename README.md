# revanced-rif-patches

Custom [ReVanced](https://revanced.app) patch bundle for **rif is fun for Reddit** 5.6.22 — both the free app (`com.andrewshu.android.reddit`) and **rif is fun golden platinum** (`com.andrewshu.android.redditdonation`).

A bundle is a container for many small, independently-toggleable patches. Options for each patch are under **Settings → ReVanced** in the patched app.

## Patches

| Patch | Apps | What it does |
|-------|------|--------------|
| **Disable ads** | free | Removes AppLovin native feed ads, banner ads, and image/album-viewer ads. Forces the ad-slot gate and `isAdsEnabledAndUnblocked()` checks to `false`, no-ops the ad loaders, and keeps the album viewer from wrapping its images in the ad-inserting adapter, so no ad slots render and no ad-network requests are made. (Golden Platinum has no ads, so it doesn't need this.) Toggle: *Block ads*. |
| **Inline comment images** | free, platinum | Renders image **and video** links in comments **and text-post bodies** inline. Handles direct links (i.redd.it / `.jpg` `.png` `.webp` `.gif` ...), animated **GIFs and WebP**, **Giphy** (id → animated gif), and resolves common hosts (imgur pages/albums, reddit galleries, redgifs/gfycat, Tenor) via their `og:image`. Bare URLs — and Reddit-app `[gif]` markers — are replaced inline; `[text](url)` links keep their text and show the image on the line below. Multi-image **imgur albums** show an "n/x" badge and ◀ ▶ buttons: tap the left/right third of the image to cycle through the album, or the middle to open it in rif's album viewer. **Videos** (comment videos, v.redd.it, imgur `.gifv`/`.mp4`) play inline below their link, muted and looping (or tap-to-play). Tap a video for controls like rif's player's: back to start, play/pause, repeat (on by default), sound, seek, and full screen in rif's player. Images and videos shrink to fit indented replies. Long pressing an image selects its comment. Toggles: *Inline images*, *Scale inline images to fit*, *Inline album navigation* (off = no arrows; tapping an album opens it, the badge stays), *Inline videos*, *Autoplay inline videos*, *Long press image to select comment* (with a *Long press delay* slider). |
| **Fix comment video links** | free, platinum | Makes videos posted in comments play in rif's video player instead of failing with "error retrieving Reddit video metadata". |
| **Fix imgur albums** | free, platinum | Fixes imgur albums crashing or failing to load when patched alongside the official ReVanced rif patches. Does nothing without them. |

## Use with ReVanced Manager

1. In ReVanced Manager, go to **Settings → Patch bundles → Add (+)** and add one of these as a custom source:

   ```
   https://raw.githubusercontent.com/MojiRS/revanced-rif-patches/main/patches.rvp
   ```

   (a direct, non-redirecting link — works best with Manager's downloader), or

   ```
   https://github.com/MojiRS/revanced-rif-patches/releases/latest/download/patches.rvp
   ```

   Both always point at the newest release, so Manager's auto-update keeps the bundle current.
2. Patch **rif is fun 5.6.22** (free or golden platinum; its final release, and the only version these patches support), selecting:
   - the official ReVanced bundle's **Spoof client** patch — rif can't load anything from Reddit without it (its default options work), and
   - whichever patches from this bundle you want.

## Building from source

Requires JDK 17 and a GitHub token with `read:packages` scope (ReVanced publishes its Gradle plugin and patcher to GitHub Packages). Put credentials in `~/.gradle/gradle.properties`:

```properties
gpr.user=<github-username>
gpr.key=<token>
githubPackagesUsername=<github-username>
githubPackagesPassword=<token>
```

Then:

```bash
./gradlew buildAndroid
# output: patches/build/libs/patches-<version>.rvp
```

> **`buildAndroid` is required.** Plain `./gradlew build` produces a JVM-only bundle (`.class` files) that the desktop CLI can load but **ReVanced Manager (Android) cannot** — it loads patches from a `classes.dex` inside the bundle and otherwise fails with `EmptyMultiDexContainerException`. The `buildAndroid` task D8-dexes the patches and injects `classes.dex` into the `.rvp` in place.

Notes:
- rif is R8-obfuscated, and the free and golden platinum builds are obfuscated **independently** (same code, different member and package names). Per-build names live in `RIF_BUILDS` (`patches/src/main/kotlin/app/revanced/patches/rif/shared/Rif.kt`).
- The extension compiles against a compile-only stub of one rif class, `extensions/extension/libs/rif-stubs.jar`. Its source and rebuild command are in `extensions/extension/stubs/`.

Pinned versions: ReVanced Patcher `22.0.0`, `app.revanced.patches` Gradle plugin `1.0.0-dev.11`, Gradle `9.1.0`.

## License

GNU General Public License v3.0
