package app.revanced.patches.rif.settings

import app.revanced.patcher.extensions.InstructionExtensions.addInstructions
import app.revanced.patcher.fingerprint
import app.revanced.patcher.patch.ResourcePatchContext
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.patcher.patch.resourcePatch
import org.w3c.dom.Document
import org.w3c.dom.Element

internal const val RIF_PACKAGE = "com.andrewshu.android.reddit"
private const val REVANCED_PREFS = "res/xml/revanced_preferences.xml"

/**
 * Shared framework patch: creates an empty "ReVanced" preference screen and adds
 * a top-of-list entry that opens it. Feature patches depend on this and add their
 * own [PreferenceCategory][addRevancedPreferenceCategory] to the screen.
 *
 * The screen is displayed by our extension's RevancedSettingsFragment (which
 * extends rif's own base settings fragment), so it gets rif's native styling.
 */
val revancedSettingsResourcePatch = resourcePatch(
    description = "Adds a ReVanced settings screen to rif's settings.",
) {
    compatibleWith(RIF_PACKAGE)

    execute {
        // Create the (initially empty) ReVanced preference screen.
        get(REVANCED_PREFS, false).writeText(
            """<?xml version="1.0" encoding="utf-8"?>
<PreferenceScreen xmlns:android="http://schemas.android.com/apk/res/android" xmlns:app="http://schemas.android.com/apk/res-auto" />
""",
        )

        // Add a "ReVanced" entry at the very top of the root settings list.
        document("res/xml/root_preferences.xml").use { doc ->
            val screen = doc.documentElement
            val entry = doc.createElement("Preference")
            entry.setAttribute("android:fragment", "app.revanced.extension.rif.RevancedSettingsFragment")
            entry.setAttribute("app:title", "ReVanced")
            screen.insertBefore(entry, screen.firstChild)
        }
    }
}

// rif's Application; we hook onCreate to hand the app Context to our extension so
// preference reads never rely on hidden-API ActivityThread reflection.
private val rifApplicationOnCreateFingerprint = fingerprint {
    custom { method, classDef ->
        classDef.type == "Lcom/andrewshu/android/reddit/RedditIsFunApplication;" &&
            method.name == "onCreate" &&
            method.parameterTypes.isEmpty()
    }
}

/**
 * Bytecode side of the settings framework: brings the extension into the app and
 * initializes its Context from Application.onCreate. Feature patches depend on this
 * so their settings reads work.
 */
val revancedSettingsPatch = bytecodePatch(
    description = "Initializes the ReVanced settings framework.",
) {
    compatibleWith(RIF_PACKAGE)
    dependsOn(revancedSettingsResourcePatch)
    extendWith("extensions/extension.rve")

    execute {
        rifApplicationOnCreateFingerprint.method.addInstructions(
            0,
            "invoke-static { p0 }, Lapp/revanced/extension/rif/Settings;->init(Landroid/content/Context;)V",
        )
    }
}

/**
 * Appends a titled `<PreferenceCategory>` (a subheader + its preferences) to the
 * shared ReVanced settings screen. Call from a feature patch's resource patch
 * that depends on [revancedSettingsResourcePatch].
 */
internal fun ResourcePatchContext.addRevancedPreferenceCategory(
    title: String,
    buildPreferences: (doc: Document, category: Element) -> Unit,
) {
    document(REVANCED_PREFS).use { doc ->
        val category = doc.createElement("PreferenceCategory")
        category.setAttribute("app:title", title)
        buildPreferences(doc, category)
        doc.documentElement.appendChild(category)
    }
}

/** Builds a default-on `<CheckBoxPreference>`, optionally greyed out via [dependency]. */
internal fun Document.checkBoxPreference(
    key: String,
    title: String,
    dependency: String? = null,
): Element = createElement("CheckBoxPreference").apply {
    setAttribute("android:key", key)
    setAttribute("app:title", title)
    setAttribute("android:defaultValue", "true")
    if (dependency != null) setAttribute("android:dependency", dependency)
}
