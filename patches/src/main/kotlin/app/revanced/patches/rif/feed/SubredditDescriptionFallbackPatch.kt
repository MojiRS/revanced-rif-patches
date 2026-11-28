package app.revanced.patches.rif.feed

import app.revanced.patcher.extensions.InstructionExtensions.addInstructions
import app.revanced.patcher.fingerprint
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.patches.rif.ads.platinumAdSlotFixPatch
import app.revanced.patches.rif.shared.REDDIT_API_EXTENSION
import app.revanced.patches.rif.shared.RIF_COMPATIBILITY
import app.revanced.patches.rif.shared.requireScratchRegister
import app.revanced.patches.rif.shared.rifUserAgentMethod
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val EXTENSION = "Lapp/revanced/extension/rif/SubredditDescription;"

// The sidebar loader's response parser (free s4.o$a.e0, Platinum t4.o$a.e0): (InputStream) ->
// RedditThing for /r/<name>/about.json, run on a pool thread. Matched by shape: the only method
// of that signature that parses with LoganSquare.parse(InputStream, Class) and looks up a string
// resource with Context.getText(int) (the "no Old Reddit description" placeholder). The other
// (InputStream)->RedditThing parsers, the subreddit-about loader's (c4.n.i0 / d4.n.i0) and the
// submit rules loader's (submit.b.e0, same LoganSquare call: the first version of this
// fingerprint hooked it), don't pick a placeholder.
internal val sidebarParserFingerprint = fingerprint {
    returns("Lcom/andrewshu/android/reddit/things/objects/RedditThing;")
    parameters("Ljava/io/InputStream;")
    custom { method, _ ->
        fun calls(definingClass: String, name: String, params: List<String>) =
            method.implementation?.instructions?.any {
                val ref = (it as? ReferenceInstruction)?.reference as? MethodReference
                    ?: return@any false
                (it.opcode == Opcode.INVOKE_STATIC || it.opcode == Opcode.INVOKE_VIRTUAL) &&
                    ref.definingClass == definingClass && ref.name == name &&
                    ref.parameterTypes.map { p -> p.toString() } == params
            } == true
        calls("Lcom/bluelinelabs/logansquare/LoganSquare;", "parse",
            listOf("Ljava/io/InputStream;", "Ljava/lang/Class;")) &&
            calls("Landroid/content/Context;", "getText", listOf("I"))
    }
}

@Suppress("unused")
val subredditDescriptionFallbackPatch = bytecodePatch(
    name = "Subreddit description fallback",
    description = "Shows the new Reddit description and rules in a subreddit's info when it has no Old Reddit sidebar.",
) {
    compatibleWith(*RIF_COMPATIBILITY)
    extendWith("extensions/extension.rve")
    dependsOn(platinumAdSlotFixPatch)

    execute {
        val parser = sidebarParserFingerprint.method
        // v0 is a local the original code writes first (const-class), so it's free at entry.
        requireScratchRegister(parser)
        val uaMethod = rifUserAgentMethod()
        // rif's user agent for the rules request, then p1 = the response stream (the method has 5
        // registers, so p1 is v4 and fits invoke-static). The extension hands back a stream with the
        // description (and rules) filled in when the description is null.
        parser.addInstructions(
            0,
            """
                invoke-static { }, $uaMethod
                move-result-object v0
                invoke-static { v0 }, $REDDIT_API_EXTENSION->setUserAgent(Ljava/lang/String;)V
                invoke-static { p1 }, $EXTENSION->withFallback(Ljava/io/InputStream;)Ljava/io/InputStream;
                move-result-object p1
            """,
        )
    }
}
