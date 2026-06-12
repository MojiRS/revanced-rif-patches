package app.revanced.patches.rif.ads

import app.revanced.patcher.extensions.ExternalLabel
import app.revanced.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.revanced.patcher.extensions.InstructionExtensions.getInstruction
import app.revanced.patcher.fingerprint
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.patcher.patch.resourcePatch
import app.revanced.patches.rif.settings.RIF_PACKAGE
import app.revanced.patches.rif.settings.addRevancedPreferenceCategory
import app.revanced.patches.rif.settings.checkBoxPreference
import app.revanced.patches.rif.settings.revancedSettingsPatch
import app.revanced.patches.rif.settings.revancedSettingsResourcePatch

private const val ADS_PACKAGE = "Lcom/andrewshu/android/reddit/ads/"
private const val SETTINGS = "Lapp/revanced/extension/rif/Settings;"

private fun adsMethod(classSimpleName: String, methodName: String) = fingerprint {
    custom { method, classDef ->
        classDef.type == "$ADS_PACKAGE$classSimpleName;" && method.name == methodName
    }
}

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

// Image-album ad load trigger.
internal val imageAlbumLoadTriggerFingerprint =
    adsMethod("RifAppLovinImageAlbumRecyclerAdapter", "initLoadAdsIfNeeded")

// Feed ad-SLOT gate (e5.e0.d0); the only override that inserts a NativeAdThreadThing.
internal val feedAdSlotGateFingerprint = fingerprint {
    custom { method, classDef ->
        classDef.type == "Le5/e0;" && method.name == "d0" && method.returnType == "Z"
    }
}

// Adds the "Disable ads" category (Block ads checkbox) to the ReVanced screen.
val disableAdsSettingsResourcePatch = resourcePatch(
    description = "Adds the Disable ads settings.",
) {
    compatibleWith(RIF_PACKAGE)
    dependsOn(revancedSettingsResourcePatch)

    execute {
        addRevancedPreferenceCategory("Disable ads") { doc, category ->
            category.appendChild(doc.checkBoxPreference("BLOCK_ADS", "Block ads"))
        }
    }
}

@Suppress("unused")
val disableAdsPatch = bytecodePatch(
    name = "Disable ads",
    description = "Removes AppLovin native feed ads, banner ads, and image-viewer ads from rif is fun.",
) {
    compatibleWith(RIF_PACKAGE)
    dependsOn(disableAdsSettingsResourcePatch, revancedSettingsPatch)
    extendWith("extensions/extension.rve")

    execute {
        // Each gate runs its original logic unless "Block ads" is enabled, in which
        // case it short-circuits (no ad shown / no ad request). v0 is a free local
        // at method entry for all of these (they all declare locals / take no params
        // that occupy v0).
        val booleanGates = listOf(
            feedAdSlotGateFingerprint,
            adViewHelperGateFingerprint,
            imageAlbumGateFingerprint,
        )
        for (fingerprint in booleanGates) {
            val method = fingerprint.method
            method.addInstructionsWithLabels(
                0,
                """
                    invoke-static {}, $SETTINGS->blockAds()Z
                    move-result v0
                    if-eqz v0, :original
                    const/4 v0, 0x0
                    return v0
                """,
                ExternalLabel("original", method.getInstruction(0)),
            )
        }

        val voidLoaders = listOf(
            nativeAdLoaderFingerprint,
            bannerLoadFingerprint,
            imageAlbumLoadTriggerFingerprint,
        )
        for (fingerprint in voidLoaders) {
            val method = fingerprint.method
            method.addInstructionsWithLabels(
                0,
                """
                    invoke-static {}, $SETTINGS->blockAds()Z
                    move-result v0
                    if-eqz v0, :original
                    return-void
                """,
                ExternalLabel("original", method.getInstruction(0)),
            )
        }
    }
}
