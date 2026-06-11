package app.revanced.patches.rif.comments

import app.revanced.patcher.extensions.InstructionExtensions.addInstructions
import app.revanced.patcher.fingerprint
import app.revanced.patcher.patch.bytecodePatch

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

@Suppress("unused")
val inlineCommentImagesPatch = bytecodePatch(
    name = "Inline comment images",
    description = "Renders direct image links in comments as embedded inline images.",
) {
    compatibleWith("com.andrewshu.android.reddit")

    // Bring our extension (InlineImages) into the app.
    extendWith("extensions/extension.rve")

    execute {
        // p1 = the SpannableStringBuilder argument. Embed images in place before
        // it is cached; the call runs on rif's comment-render worker thread.
        commentRenderedBodyFingerprint.method.addInstructions(
            0,
            "invoke-static { p1 }, " +
                "Lapp/revanced/extension/rif/InlineImages;->embed(Landroid/text/SpannableStringBuilder;)V",
        )
    }
}
