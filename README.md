# revanced-rif-patches

Custom [ReVanced](https://revanced.app) patch bundle for **rif is fun for Reddit** (`com.andrewshu.android.reddit`).

A bundle is a container for many small, independently-toggleable patches. More rif patches will be added here over time.

## Patches

| Patch | What it does |
|-------|--------------|
| **Disable ads** | Removes AppLovin native feed ads, banner ads, and image-viewer ads. Forces the ad-slot gate and `isAdsEnabledAndUnblocked()` checks to `false` and no-ops the ad loaders, so no ad slots render and no ad-network requests are made. |
| **Inline comment images** | Renders image links in comments as embedded inline images. Handles direct links (i.redd.it / `.jpg` `.png` `.webp` `.gif` ...), animated **GIFs and WebP**, **Giphy** (id → animated gif), and resolves common hosts (imgur pages/albums, reddit galleries, redgifs/gfycat, Tenor) via their `og:image`. Bare URLs — and Reddit-app `[gif]` markers — are replaced inline; `[text](url)` links keep their text and show the image on the line below. Toggleable via **Settings → ReVanced** (Inline images / Scale inline images to fit). Imgur album/gallery links open in the browser (rif's internal album viewer crashes). |

## Use with ReVanced Manager (auto-updating)

1. In ReVanced Manager, go to **Settings → Patch bundles → Add (+)** and add this URL as a custom source:

   ```
   https://github.com/MojiRS/revanced-rif-patches/releases/latest/download/patches.rvp
   ```

   This link always resolves to the newest release, so Manager's auto-update keeps it current.
2. Patch rif is fun, selecting **both**:
   - the official **Change OAuth client ID** patch (needed for rif to authenticate with Reddit — supply a working client ID, e.g. a RedReader app id), and
   - **Disable ads** from this bundle.

> The standalone patched APK on its own contains only the ad patch and will not authenticate with Reddit without the client-ID patch.

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
./gradlew build buildAndroid
# output: patches/build/libs/patches-<version>.rvp
```

> **`buildAndroid` is required.** Plain `./gradlew build` produces a JVM-only bundle (`.class` files) that the desktop CLI can load but **ReVanced Manager (Android) cannot** — it loads patches from a `classes.dex` inside the bundle and otherwise fails with `EmptyMultiDexContainerException`. The `buildAndroid` task D8-dexes the patches and injects `classes.dex` into the `.rvp` in place.

Pinned versions: ReVanced Patcher `22.0.0`, `app.revanced.patches` Gradle plugin `1.0.0-dev.11`, Gradle `9.1.0`.

## License

GNU General Public License v3.0
