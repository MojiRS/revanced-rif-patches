package app.revanced.patches.rif.ads

import app.revanced.patcher.extensions.InstructionExtensions.addInstructions
import app.revanced.patcher.fingerprint
import app.revanced.patcher.patch.bytecodePatch

private const val ADS_PACKAGE = "Lcom/andrewshu/android/reddit/ads/"

/**
 * Matches a method by its declaring class' descriptor + method name.
 * rif's ad code is unobfuscated, so a name match is stable and precise.
 */
private fun adsMethod(classSimpleName: String, methodName: String) = fingerprint {
    custom { method, classDef ->
        classDef.type == "$ADS_PACKAGE$classSimpleName;" && method.name == methodName
    }
}

// --- The five ad choke points (all inside com.andrewshu.android.reddit.ads) ---

// Master gate for native feed ads (also triggers the GDPR consent dialog).
internal val adViewHelperGateFingerprint =
    adsMethod("AdViewHelper", "isAdsEnabledAndUnblocked")

// Master gate for image-album (image viewer) ads.
internal val imageAlbumGateFingerprint =
    adsMethod("ImageAlbumAdViewHelper", "isAdsEnabledAndUnblocked")

// Actual loader for native feed/thread ads.
internal val nativeAdLoaderFingerprint =
    adsMethod("RifNativeAdLoaderWaitListManager", "loadAds")

// Banner ad loader (header/footer MaxAdView banner).
internal val bannerLoadFingerprint =
    adsMethod("BannerAdViewHelper", "loadAd")

// Image-album ad load trigger (inline-gated; no-op it entirely).
internal val imageAlbumLoadTriggerFingerprint =
    adsMethod("RifAppLovinImageAlbumRecyclerAdapter", "initLoadAdsIfNeeded")

// Feed ad-SLOT gate. e5.e0 is the thread-list loader; its d0() override decides
// whether to insert a NativeAdThreadThing placeholder row into the feed (b5.y0
// is the only insertion site, gated solely by this method). Forcing it false
// means the slot is never reserved, so no empty "Loading ad..." box renders.
// Names are obfuscated but stable: rif is fun is discontinued (5.6.22 is final).
internal val feedAdSlotGateFingerprint = fingerprint {
    custom { method, classDef ->
        classDef.type == "Le5/e0;" && method.name == "d0" && method.returnType == "Z"
    }
}

@Suppress("unused")
val disableAdsPatch = bytecodePatch(
    name = "Disable ads",
    description = "Removes AppLovin native feed ads, banner ads, and image-viewer ads from rif is fun.",
) {
    // Unpinned so the patch survives minor rif version bumps; the ad code has
    // been structurally stable. Pin a version here if a future rif build moves it.
    compatibleWith("com.andrewshu.android.reddit")

    execute {
        // Force the ad gates to return false. The feed slot gate (d0) stops the
        // empty placeholder row from being inserted; the two isAdsEnabledAndUnblocked
        // gates stop consent flows and load triggers.
        listOf(
            feedAdSlotGateFingerprint,
            adViewHelperGateFingerprint,
            imageAlbumGateFingerprint,
        ).forEach { fp ->
            fp.method.addInstructions(
                0,
                """
                    const/4 v0, 0x0
                    return v0
                """,
            )
        }

        // No-op the three ad loaders so no ad network request is ever made.
        listOf(
            nativeAdLoaderFingerprint,
            bannerLoadFingerprint,
            imageAlbumLoadTriggerFingerprint,
        ).forEach { fp ->
            fp.method.addInstructions(0, "return-void")
        }
    }
}
