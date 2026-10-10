# revanced-rif-patches

Custom [ReVanced](https://revanced.app) patch bundle for **rif is fun for Reddit** 5.6.22 — both the free app (`com.andrewshu.android.reddit`) and **rif is fun golden platinum** (`com.andrewshu.android.redditdonation`).

A bundle is a container for many small, independently-toggleable patches. Options for each patch are under **Settings → ReVanced** in the patched app.

## Patches

| Patch | Description |
|-------|-------------|
| **Disable ads** | Removes and does not render all ads. *Not needed for rif golden platinum.* |
| **Inline comment images** | Shows images, GIFs and videos linked in comments and text posts inline. |
| **Fix comment video links** | Makes videos posted in comments play in rif's video player instead of failing with "error retrieving Reddit video metadata". |
| **Fix imgur albums** | Fixes imgur albums crashing or failing to load when patched alongside the official ReVanced rif patches. |

For how each patch works and its settings, see [PATCHES.md](PATCHES.md).

## Use with ReVanced Manager

1. In ReVanced Manager, go to **Settings → Patch bundles → Add (+)** and add this as a remote source:

   ```
   https://raw.githubusercontent.com/MojiRS/revanced-rif-patches/main/bundles/stable.json
   ```

   It always points at the newest release, so Manager's auto-update keeps the bundle current. To also get release candidates, use `bundles/latest.json` instead.

   (Manager needs this JSON link; a link to the `.rvp` itself doesn't work as a remote source. You can also download `patches.rvp` from [Releases](https://github.com/MojiRS/revanced-rif-patches/releases) and add it as a local file.)
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

After publishing a release, run `python scripts/update_bundle_json.py` and commit the updated `bundles/*.json` (the Manager links above point at them).

Pinned versions: ReVanced Patcher `22.0.0`, `app.revanced.patches` Gradle plugin `1.0.0-dev.11`, Gradle `9.1.0`.

## License

GNU General Public License v3.0
