package app.revanced.patches.rif.comments

import app.revanced.patcher.extensions.ExternalLabel
import app.revanced.patcher.extensions.InstructionExtensions.addInstructions
import app.revanced.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.revanced.patcher.extensions.InstructionExtensions.getInstruction
import app.revanced.patcher.extensions.InstructionExtensions.instructions
import app.revanced.patcher.fingerprint
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.patcher.patch.PatchException
import app.revanced.patcher.patch.resourcePatch
import app.revanced.patches.rif.settings.RIF_PACKAGE
import app.revanced.patches.rif.settings.addRevancedPreferenceCategory
import app.revanced.patches.rif.settings.checkBoxPreference
import app.revanced.patches.rif.settings.revancedSettingsPatch
import app.revanced.patches.rif.settings.revancedSettingsResourcePatch
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction

private const val EXTENSION = "Lapp/revanced/extension/rif/InlineImages;"

// Adds the "Inline comment images" category (two checkboxes) to the ReVanced screen.
// "Scale inline images to fit" is greyed out when "Inline images" is off.
val inlineImagesSettingsResourcePatch = resourcePatch(
    description = "Adds the Inline comment images settings.",
) {
    compatibleWith(RIF_PACKAGE)
    dependsOn(revancedSettingsResourcePatch)

    execute {
        addRevancedPreferenceCategory("Inline comment images") { doc, category ->
            category.appendChild(doc.checkBoxPreference("INLINE_IMAGES", "Inline images"))
            category.appendChild(
                doc.checkBoxPreference(
                    "INLINE_IMAGES_SCALE",
                    "Scale inline images to fit",
                    dependency = "INLINE_IMAGES",
                ),
            )
        }
    }
}

// CommentThing.e(SpannableStringBuilder) is rif's i0 render callback: it receives
// the fully-rendered comment body (link spans already applied) on a background
// thread and caches it for display. Injecting at its entry lets our extension
// embed images into the spannable before it is ever measured/shown.
internal val commentRenderedBodyFingerprint = fingerprint {
    custom { method, classDef ->
        classDef.type == "Lcom/andrewshu/android/reddit/things/objects/CommentThing;" &&
            method.name == "e" &&
            method.returnType == "V" &&
            method.parameterTypes.size == 1 &&
            method.parameterTypes.first().toString() == "Landroid/text/SpannableStringBuilder;"
    }
}

// n2.o.h(m, CommentThing, Fragment) is the comment ViewHolder body bind. Right
// after `bodyTextView.setText(body)` we attach() so any animated (GIF) drawable
// in the spannable gets its callback wired to that TextView and is started; this
// is the main thread, so animation can run. h() has exactly one setText.
internal val commentBodyBindFingerprint = fingerprint {
    custom { method, classDef ->
        classDef.type == "Ln2/o;" &&
            method.name == "h" &&
            method.parameterTypes.size == 3 &&
            method.parameterTypes[1].toString() ==
                "Lcom/andrewshu/android/reddit/things/objects/CommentThing;"
    }
}

// ThreadThing.e(SpannableStringBuilder) is the i0 render callback for a text post's
// selftext body — same shape as CommentThing.e. Embed images here too.
internal val threadSelftextEmbedFingerprint = fingerprint {
    custom { method, classDef ->
        classDef.type == "Lcom/andrewshu/android/reddit/things/objects/ThreadThing;" &&
            method.name == "e" &&
            method.returnType == "V" &&
            method.parameterTypes.size == 1 &&
            method.parameterTypes.first().toString() == "Landroid/text/SpannableStringBuilder;"
    }
}

// e5.g binds the post header; its selftext-bind method (the one reading
// ThreadThing.C0()) sets the selftext on a TextView. We attach() after that
// setText so selftext GIFs animate, mirroring the comment bind.
internal val selftextBindFingerprint = fingerprint {
    custom { method, classDef ->
        classDef.type == "Le5/g;" &&
            method.implementation?.instructions?.any { insn ->
                insn is ReferenceInstruction &&
                    insn.reference.toString() ==
                    "Lcom/andrewshu/android/reddit/things/objects/ThreadThing;->C0()Ljava/lang/CharSequence;"
            } == true
    }
}

// RedditBodyLinkSpan.onClick(View) opens a tapped comment link (and is what fires
// when the inline album cover is tapped). rif's internal imgur album/gallery viewer
// crashes, so we intercept those links and open them in a browser instead.
internal val redditBodyLinkClickFingerprint = fingerprint {
    custom { method, classDef ->
        classDef.type == "Lcom/andrewshu/android/reddit/comments/spans/RedditBodyLinkSpan;" &&
            method.name == "onClick"
    }
}

@Suppress("unused")
val inlineCommentImagesPatch = bytecodePatch(
    name = "Inline comment images",
    description = "Renders image links in comment and text-post bodies as embedded inline images (static + animated GIFs, common hosts).",
) {
    compatibleWith(RIF_PACKAGE)
    dependsOn(inlineImagesSettingsResourcePatch, revancedSettingsPatch)

    // Bring our extension (InlineImages) into the app.
    extendWith("extensions/extension.rve")

    execute {
        // 1) Embed images into the comment + selftext spannables (background, before
        // display). p1 = the SpannableStringBuilder argument.
        for (fingerprint in listOf(commentRenderedBodyFingerprint, threadSelftextEmbedFingerprint)) {
            fingerprint.method.addInstructions(
                0,
                "invoke-static { p1 }, $EXTENSION->embed(Landroid/text/SpannableStringBuilder;)V",
            )
        }

        // 2) Start GIF animation once the body TextView is bound (main thread): inject
        // attach(textView) right after the body setText, in both the comment ViewHolder
        // bind (n2.o.h) and the selftext bind (e5.g). Each has one TextView.setText.
        for (bind in listOf(commentBodyBindFingerprint.method, selftextBindFingerprint.method)) {
            val setTextIndex = bind.instructions.indexOfFirst { insn ->
                insn.opcode == Opcode.INVOKE_VIRTUAL &&
                    (insn as? ReferenceInstruction)?.reference?.toString() ==
                    "Landroid/widget/TextView;->setText(Ljava/lang/CharSequence;)V"
            }
            if (setTextIndex == -1) {
                throw PatchException("body setText not found in ${bind.definingClass}")
            }
            val textViewRegister =
                (bind.instructions.elementAt(setTextIndex) as FiveRegisterInstruction).registerC
            bind.addInstructions(
                setTextIndex + 1,
                "invoke-static { v$textViewRegister }, $EXTENSION->attach(Landroid/widget/TextView;)V",
            )
        }

        // 3) Intercept imgur album/gallery link clicks and open them in a browser
        // (rif's internal viewer crashes on them). p0 = the span (a URLSpan),
        // p1 = the clicked View.
        val onClick = redditBodyLinkClickFingerprint.method
        onClick.addInstructionsWithLabels(
            0,
            """
                invoke-static { p0, p1 }, $EXTENSION->handleAlbumLink(Landroid/text/style/URLSpan;Landroid/view/View;)Z
                move-result v0
                if-eqz v0, :original
                return-void
            """,
            ExternalLabel("original", onClick.getInstruction(0)),
        )
    }
}
