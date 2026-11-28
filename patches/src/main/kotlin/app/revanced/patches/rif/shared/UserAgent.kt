package app.revanced.patches.rif.shared

import app.revanced.patcher.patch.BytecodePatchContext
import app.revanced.patcher.patch.PatchException
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val OAUTH2_ACTIVITY = "Lcom/andrewshu/android/reddit/login/oauth2/OAuth2Activity;"
private const val SET_UA = "Landroid/webkit/WebSettings;->setUserAgentString(Ljava/lang/String;)V"

/** The extension's holder of rif's user agent for our own Reddit requests: `setUserAgent(String)`. */
internal const val REDDIT_API_EXTENSION = "Lapp/revanced/extension/rif/RedditApi;"

/**
 * rif's user-agent method (free h3.d.g, Platinum's twin), found without its name or body: the Spoof
 * client patch rewrites it. OAuth2Activity (kept name) passes its result to setUserAgentString, so
 * it is the static no-arg String method invoked right before that call. Patches that make their
 * own Reddit requests call it at runtime and hand the result to [REDDIT_API_EXTENSION]'s
 * `setUserAgent`, so the requests carry the same user agent as rif's.
 */
internal fun BytecodePatchContext.rifUserAgentMethod(): String {
    val activity = classes.firstOrNull { it.type == OAUTH2_ACTIVITY }
        ?: throw PatchException("$OAUTH2_ACTIVITY not found")
    val found = mutableSetOf<String>()
    var calls = 0
    for (m in activity.methods) {
        val code = m.implementation?.instructions?.toList() ?: continue
        for ((i, insn) in code.withIndex()) {
            if (insn.opcode != Opcode.INVOKE_VIRTUAL || (insn as ReferenceInstruction).reference.toString() != SET_UA) continue
            calls++
            // Walk back to the move-result-object that fills the argument register.
            val arg = (insn as FiveRegisterInstruction).registerD
            for (j in i - 1 downTo maxOf(0, i - 4)) {
                val mr = code[j]
                if (mr.opcode == Opcode.MOVE_RESULT_OBJECT && (mr as OneRegisterInstruction).registerA == arg && j > 0) {
                    val call = code[j - 1]
                    if (call.opcode == Opcode.INVOKE_STATIC) {
                        val ref = (call as ReferenceInstruction).reference as MethodReference
                        if (ref.parameterTypes.isEmpty() && ref.returnType == "Ljava/lang/String;") found += ref.toString()
                    }
                    break
                }
            }
        }
    }
    if (calls != 1 || found.size != 1) {
        throw PatchException("rif user-agent method: expected 1 setUserAgentString call fed by a static String method, found $calls calls, ${found.size} methods")
    }
    return found.single()
}
