# revanced-rif-patches

Custom [ReVanced](https://revanced.app) patch bundle for **rif is fun for Reddit** 5.6.22 — both the free app (`com.andrewshu.android.reddit`) and **rif is fun golden platinum** (`com.andrewshu.android.redditdonation`).

This bundle contains many small, independently-toggleable patches. Options for each patch are under **Settings → ReVanced** in the patched app.

## Patches

| Patch                             | Description                                                                                                                                    |
| --------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------- |
| **Disable ads**                   | Removes all ads. *Not needed for rif golden platinum.*                                                                                         |
| **Inline comment images**         | Shows images, GIFs and videos linked in comments and text posts inline.                                                                        |
| **Fix comment video links**       | Makes videos posted in comments play in rif's video player instead of failing to load.                                                         |
| **Fix imgur albums**              | Fixes imgur albums crashing or failing to load.                                                                                                |
| **Fix YouTube videos**            | Fixes the built-in YouTube player.                                                                                                             |
| **YouTube player controls**       | Double-tap to seek, hold for 2x speed, and speed and captions controls in the YouTube player.                                                  |
| **Fix share links**               | Opens Reddit share links (reddit.com/r/…/s/…) directly in rif instead of bouncing through the browser, and fixes reddit.com/media image links. |
| **Repair broken gallery images**  | Shows the images of galleries that fail to load because Reddit marks some of their images as failed.                                           |
| **Keep reply box above keyboard** | Keeps the reply box unobscured above the keyboard.                                                                                             |
| **Fix broken markdown links**     | Shows links like `[name](u/name)` as links instead of raw text.                                                                                |
| **Hide blocked mod comments**     | Adds a setting to hide mod comments from blocked users. Off by default.                                                                        |

For details on how each patch works and its settings, see [PATCHES.md](PATCHES.md).

**Galleries or "load more comments" not working?** Since October 2026, Reddit blocks the requests rif uses for them. Patch with [notBrian2/revanced-rif-fixes](https://github.com/notBrian2/revanced-rif-fixes) alongside this bundle (see below): it fixes both, plus Redgifs and more imgur album features. "Fix Reddit galleries" in notBrian2's bundle and "Repair broken gallery images" in this bundle fix different issues and should be used together. 

## Use with ReVanced Manager

1. In ReVanced Manager, go to **Patches → Pencil button** (bottom right) → **+ Add patches → Enter URL** and add this as the patches URL:

   ```
   https://raw.githubusercontent.com/MojiRS/revanced-rif-patches/main/bundles/stable.json
   ```

   It always points at the newest release, so ReVanced Manager's auto-update keeps the bundle current. To also get release candidates, use `bundles/latest.json` instead.

   (You can also download `patches.rvp` from [Releases](https://github.com/MojiRS/revanced-rif-patches/releases) and add it as a local file.)

2. Add [notBrian2/revanced-rif-fixes](https://github.com/notBrian2/revanced-rif-fixes) in the same way (strongly recommended; fixes galleries and loading more comments):

   ```
   https://raw.githubusercontent.com/notBrian2/revanced-rif-fixes/main/bundles/stable.json
   ```

3. Go to **Apps** and patch **rif is fun 5.6.22** (free or golden platinum; its final release, and the only version these patches support), selecting:
   - **ReVanced Patches - Spoof client** patch
   - **rif patches** (this bundle)
   - **rif fixes** ([notBrian2/revanced-rif-fixes](https://github.com/notBrian2/revanced-rif-fixes))

4. Open the patched app and log in. Reddit blocks all content for logged out users and shows an `error: forbidden` popup in rif.

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

After publishing a release, run `python scripts/update_bundle_json.py` and commit the updated `bundles/*.json` (the Manager links above point at them).

Pinned versions: ReVanced Patcher `22.0.0`, `app.revanced.patches` Gradle plugin `1.0.0-dev.11`, Gradle `9.1.0`.

## License

GNU General Public License v3.0
