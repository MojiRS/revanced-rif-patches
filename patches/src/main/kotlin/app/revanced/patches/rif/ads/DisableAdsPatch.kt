package app.revanced.patches.rif.ads

import app.revanced.patcher.extensions.ExternalLabel
import app.revanced.patcher.extensions.InstructionExtensions.addInstruction
import app.revanced.patcher.extensions.InstructionExtensions.addInstructions
import app.revanced.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.revanced.patcher.extensions.InstructionExtensions.getInstruction
import app.revanced.patcher.extensions.InstructionExtensions.instructions
import app.revanced.patcher.extensions.InstructionExtensions.replaceInstruction
import app.revanced.patcher.fingerprint
import app.revanced.patcher.patch.PatchException
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.patcher.patch.resourcePatch
import app.revanced.patches.rif.settings.addRevancedPreferenceCategory
import app.revanced.patches.rif.settings.checkBoxPreference
import app.revanced.patches.rif.settings.revancedSettingsPatch
import app.revanced.patches.rif.settings.revancedSettingsResourcePatch
import app.revanced.patches.rif.shared.RIF_FREE_COMPATIBILITY
import app.revanced.patches.rif.shared.requireScratchRegister
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction

// Free build only: rif is fun golden platinum ships without the ad SDK or ad code.
private const val ADS_PACKAGE = "Lcom/andrewshu/android/reddit/ads/"
private const val SETTINGS = "Lapp/revanced/extension/rif/Settings;"
private const val ADS_SETTINGS = "Lapp/revanced/extension/rif/AdSettings;"

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

// The album viewer fragment's adapter factory (e2.b.L6): wraps the image list in
// RifAppLovinImageAlbumRecyclerAdapter, whose own ad placer loads ads as it binds, so
// no-op'ing initLoadAdsIfNeeded alone doesn't stop album-viewer ads. Matched as the
// only method that calls IImageAlbumAdViewHelper.newRecyclerAdapter.
internal val albumAdAdapterFactoryFingerprint = fingerprint {
    custom { method, _ ->
        method.parameterTypes.size == 1 &&
            method.returnType == "Landroidx/recyclerview/widget/RecyclerView\$h;" &&
            method.implementation?.instructions?.any { insn ->
                insn is ReferenceInstruction &&
                    insn.reference.toString().startsWith(
                        "${ADS_PACKAGE}IImageAlbumAdViewHelper;->newRecyclerAdapter(",
                    )
            } == true
    }
}

// The bottom banner strip's gate (u1.b.b): private (MainActivity)Z that ends in the theme
// manifest's ad_placement check (ThemeManifest.E()). Only loadAd was hooked, so with a
// bottom-ad theme the frame was still inflated, empty. Matched by shape.
internal val bottomBannerGateFingerprint = fingerprint {
    custom { method, _ ->
        AccessFlags.PRIVATE.isSet(method.accessFlags) &&
            method.returnType == "Z" &&
            method.parameterTypes.size == 1 &&
            method.parameterTypes[0] == "Lcom/andrewshu/android/reddit/MainActivity;" &&
            method.implementation?.instructions?.any { insn ->
                insn is ReferenceInstruction &&
                    insn.reference.toString().startsWith(
                        "Lcom/andrewshu/android/reddit/theme/manifest/ThemeManifest;->",
                    )
            } == true
    }
}

// GeneralSettingsFragment's override of the base fragment's post-inflate hook (free N4): a lone
// return-void. Matched by shape (protected ()V with nothing in it) inside the kept-name class.
internal val generalSettingsPostInflateFingerprint = fingerprint {
    custom { method, classDef ->
        classDef.type == "Lcom/andrewshu/android/reddit/settings/GeneralSettingsFragment;" &&
            AccessFlags.PROTECTED.isSet(method.accessFlags) &&
            method.returnType == "V" &&
            method.parameterTypes.isEmpty() &&
            method.implementation?.instructions?.count() == 1
    }
}

// The settings search task's doInBackground (r4.d.h): (String[])List, in the class that parses
// each screen's XML (a (Context, XmlResourceParser, String)String helper). Matched by shape.
internal val settingsSearchFingerprint = fingerprint {
    custom { method, classDef ->
        method.returnType == "Ljava/util/List;" &&
            method.parameterTypes.size == 1 &&
            method.parameterTypes[0] == "[Ljava/lang/String;" &&
            !AccessFlags.SYNTHETIC.isSet(method.accessFlags) &&
            classDef.methods.any { m ->
                m.returnType == "Ljava/lang/String;" &&
                    m.parameterTypes.size == 3 &&
                    m.parameterTypes[1] == "Landroid/content/res/XmlResourceParser;"
            }
    }
}

// RedditIsFunApplication.onCreate (also hooked by revancedSettingsPatch for Settings.init).
internal val adsApplicationOnCreateFingerprint = fingerprint {
    custom { method, classDef ->
        classDef.type == "Lcom/andrewshu/android/reddit/RedditIsFunApplication;" &&
            method.name == "onCreate" &&
            method.parameterTypes.isEmpty()
    }
}

// Adds the "Disable ads" category (Block ads checkbox) to the rif patches screen.
val disableAdsSettingsResourcePatch = resourcePatch(
    description = "Adds the Disable ads settings.",
) {
    compatibleWith(RIF_FREE_COMPATIBILITY)
    dependsOn(revancedSettingsResourcePatch)

    execute {
        addRevancedPreferenceCategory("Disable ads") { doc, category ->
            category.appendChild(
                doc.checkBoxPreference(
                    "BLOCK_ADS",
                    "Block ads",
                    summary = "Hides rif's ad settings and keeps its pro features on.",
                ),
            )
        }

        // rif's pro rows say "Requires ads enabled", but while Block ads is on ADS_ENABLED stays
        // true and its row is hidden (AdSettings), so the text points at a switch nobody sees.
        // Edited statically (also fixes settings-search summaries). An empty summary hides the
        // summary line.
        val changed = mutableSetOf<String>()
        document("res/values/strings.xml").use { doc ->
            val strings = doc.getElementsByTagName("string")
            for (i in 0 until strings.length) {
                val node = strings.item(i)
                val name = (node as org.w3c.dom.Element).getAttribute("name")
                val replacement = PRO_SUMMARIES[name] ?: continue
                node.textContent = replacement
                changed += name
            }
        }
        // The "Cards for images" dialog repeats the phrase in its option descriptions.
        document("res/values/arrays.xml").use { doc ->
            val items = doc.getElementsByTagName("item")
            for (i in 0 until items.length) {
                val node = items.item(i)
                if (node.textContent.contains(REQUIRES_ADS)) {
                    node.textContent = node.textContent.replace(REQUIRES_ADS, "")
                    changed += "arrays:$i"
                }
            }
        }
        val missing = PRO_SUMMARIES.keys - changed
        if (missing.isNotEmpty()) throw PatchException("rif strings not found: $missing")
    }
}

private const val REQUIRES_ADS = "Requires ads enabled. "

// rif free's "Requires ads enabled" summaries without that phrase (empty where it was all there was).
private val PRO_SUMMARIES = mapOf(
    "pref_dualpane_summary" to "Tablet recommended.",
    "pref_native_image_albums_summary" to "For Imgur albums and Reddit galleries.",
    "pref_reddit_visited_enabled_summary" to "Requires reddit premium (reddit.com/premium).",
    "pref_synccit_enabled_summary" to "synccit.com.",
    "pref_threads_cards_summary" to "",
    "pref_enable_new_post_notifications_summary" to "",
    "pref_moderator_enabled_summary" to "",
)

@Suppress("unused")
val disableAdsPatch = bytecodePatch(
    name = "Disable ads",
    description = "Removes all ads.",
) {
    compatibleWith(RIF_FREE_COMPATIBILITY)
    dependsOn(disableAdsSettingsResourcePatch, revancedSettingsPatch)
    extendWith("extensions/extension.rve")

    execute {
        // Each gate runs its original logic unless "Block ads" is enabled, in which
        // case it short-circuits (no ad shown / no ad request). v0 is used as scratch,
        // so each method must have a free local (checked, not assumed).
        val booleanGates = listOf(
            feedAdSlotGateFingerprint,
            adViewHelperGateFingerprint,
            imageAlbumGateFingerprint,
            bottomBannerGateFingerprint,
        )
        for (fingerprint in booleanGates) {
            val method = fingerprint.method
            requireScratchRegister(method)
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

        // Album viewer: hand back the plain image adapter so the ad-inserting wrapper is
        // never created (rif's own no-ads path; the helper's other callbacks all check
        // `instanceof` the ad adapter first). p1 = the adapter to wrap.
        val albumAdapterFactory = albumAdAdapterFactoryFingerprint.method
        requireScratchRegister(albumAdapterFactory)
        albumAdapterFactory.addInstructionsWithLabels(
            0,
            """
                invoke-static {}, $SETTINGS->blockAds()Z
                move-result v0
                if-eqz v0, :original
                return-object p1
            """,
            ExternalLabel("original", albumAdapterFactory.getInstruction(0)),
        )

        val voidLoaders = listOf(
            nativeAdLoaderFingerprint,
            bannerLoadFingerprint,
            imageAlbumLoadTriggerFingerprint,
        )
        for (fingerprint in voidLoaders) {
            val method = fingerprint.method
            requireScratchRegister(method)
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

        // Rif's own ad switches while Block ads is on (AdSettings): keep ADS_ENABLED (rif's pro
        // flag) true from launch, hide "Ads & pro features" and "Hide ads (reddit premium)" in
        // General settings, and keep both out of settings search.
        // Index 0 of onCreate: p0 is `this` (a Context); the helper also runs Settings.init, so
        // the order against revancedSettingsPatch's own insert doesn't matter.
        adsApplicationOnCreateFingerprint.method.addInstructions(
            0,
            "invoke-static { p0 }, $ADS_SETTINGS->keepProEnabled(Landroid/content/Context;)V",
        )

        // p0 = the fragment; the method is one return-void, so it has only p0.
        generalSettingsPostInflateFingerprint.method.addInstructions(
            0,
            "invoke-static { p0 }, $ADS_SETTINGS->hideAdRows(Ljava/lang/Object;)V",
        )

        // Just before the final return-object (the result ArrayList) of the search task.
        val search = settingsSearchFingerprint.method
        val searchCode = search.implementation!!.instructions.toList()
        val lastReturn = searchCode.indexOfLast { it.opcode == Opcode.RETURN_OBJECT }
        val resultRegister = (searchCode.getOrNull(lastReturn) as? OneRegisterInstruction)?.registerA
        if (resultRegister == null || resultRegister > 15) {
            throw PatchException("settings search result return not found in ${search.definingClass}->${search.name}")
        }
        // The loop exit branches to that return-object, and instructions inserted at its index
        // aren't on the branch's path: replace it with our call and put the return after it.
        search.replaceInstruction(
            lastReturn,
            "invoke-static { v$resultRegister }, $ADS_SETTINGS->filterSearch(Ljava/util/List;)V",
        )
        search.addInstruction(lastReturn + 1, "return-object v$resultRegister")
    }
}
