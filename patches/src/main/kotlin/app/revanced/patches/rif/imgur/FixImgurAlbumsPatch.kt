package app.revanced.patches.rif.imgur

import app.revanced.patcher.extensions.InstructionExtensions.addInstructions
import app.revanced.patcher.extensions.InstructionExtensions.instructions
import app.revanced.patcher.extensions.InstructionExtensions.replaceInstruction
import app.revanced.patcher.patch.BytecodePatchContext
import app.revanced.patcher.patch.PatchException
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.patches.rif.shared.RIF_PACKAGES
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

// String.concat is an instance method; a static call to it can never resolve
// (NoSuchMethodError at runtime).
private const val BROKEN_CONCAT =
    "Ljava/lang/String;->concat(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;"
private const val CONCAT = "Ljava/lang/String;->concat(Ljava/lang/String;)Ljava/lang/String;"

// The official patch's injected imgur v3 endpoint; marks the album URL builder.
private const val IMGUR_V3_ALBUM = "https://api.imgur.com/3/album/"

// rif's own imgur API client ID, already shipped in the app and sent by its other imgur
// requests (k3.a / k3.b in the free build).
private const val RIF_IMGUR_CLIENT_ID = "Client-ID 4d7e2f74f1a519c"

// rif's imgur album/gallery API URL builder: static (String albumId, boolean isGallery) -> Uri
// (free g2.c.x / Platinum h2.c.x), after the official rif patch has prepended its imgur v3
// API block. Matched by shape + that block's endpoint string, so it works in both builds and
// never touches the other (String, boolean) -> Uri method (r0.H).
private fun Method.isImgurAlbumUriBuilder() =
    returnType == "Landroid/net/Uri;" &&
        parameterTypes.size == 2 &&
        parameterTypes[0].toString() == "Ljava/lang/String;" &&
        parameterTypes[1].toString() == "Z" &&
        implementation?.instructions?.any {
            it.opcode == Opcode.CONST_STRING &&
                (it as ReferenceInstruction).reference.toString() == IMGUR_V3_ALBUM
        } == true

private fun Instruction.isBrokenConcat() =
    opcode == Opcode.INVOKE_STATIC &&
        (this as ReferenceInstruction).reference.toString() == BROKEN_CONCAT

// The album loader's request-header hook (free g2.c$a.B / Platinum h2.c$a.B):
// (settings, okhttp3.Request.Builder, String, Uri) -> void.
private fun Method.isAlbumRequestHeaderHook() =
    returnType == "V" &&
        parameterTypes.size == 4 &&
        parameterTypes[1].toString().startsWith("Lokhttp3/") &&
        parameterTypes[2].toString() == "Ljava/lang/String;" &&
        parameterTypes[3].toString() == "Landroid/net/Uri;"

@Suppress("unused")
val fixImgurAlbumsPatch = bytecodePatch(
    name = "Fix imgur albums",
    description = "Fixes imgur albums crashing or failing to load when patched alongside the " +
        "official ReVanced rif patches. Does nothing without them.",
) {
    compatibleWith(*RIF_PACKAGES)

    // The code being fixed is injected by another bundle's patch, and patch order across
    // bundles isn't guaranteed. finalize runs after every patch's execute (in Manager and
    // the CLI alike), so the injected code is already there when we look for it.
    finalize {
        val builderClasses = classes.filter { classDef -> classDef.methods.any { it.isImgurAlbumUriBuilder() } }
        for (classDef in builderClasses) {
            fixBrokenConcat(classDef.type)
            addClientIdHeader(classDef.type)
        }
    }
}

/** Rewrites the invalid static String.concat call(s) in the URL builder of [builderClass]. */
private fun BytecodePatchContext.fixBrokenConcat(builderClass: String) {
    val classDef = classes.first { it.type == builderClass }
    for (method in proxy(classDef).mutableClass.methods) {
        if (!method.isImgurAlbumUriBuilder()) continue
        // Collect first: replacing while iterating the live list isn't safe.
        val broken = method.instructions.withIndex()
            .filter { it.value.isBrokenConcat() }
            .map { it.index to (it.value as FiveRegisterInstruction) }
        for ((index, call) in broken) {
            // Same 35c format/size, so no branch offsets move; the following
            // move-result-object stays valid. v{C} = receiver, v{D} = argument.
            method.replaceInstruction(
                index,
                "invoke-virtual { v${call.registerC}, v${call.registerD} }, $CONCAT",
            )
        }
    }
}

/**
 * rif built album requests for its own proxy (api.redditisfun.com), which added imgur's
 * client ID server-side; rif itself only sends an `x-redditisfun-key` header. Rerouted to
 * api.imgur.com, the request is anonymous, which imgur's legacy API rejects unpredictably
 * (e.g. 429). Add `Authorization: Client-ID …` next to rif's existing header in the album
 * loader (an inner class of [builderClass]).
 */
private fun BytecodePatchContext.addClientIdHeader(builderClass: String) {
    val innerPrefix = builderClass.removeSuffix(";") + "$"
    val loaders = classes.filter { c ->
        c.type.startsWith(innerPrefix) && c.methods.any { it.isAlbumRequestHeaderHook() }
    }
    for (loader in loaders) {
        for (method in proxy(loader).mutableClass.methods) {
            if (!method.isAlbumRequestHeaderHook()) continue
            val builderType = method.parameterTypes[1].toString()
            // rif's own builder.header(name, value) call: invoke-virtual {builder, name, value}.
            val (index, call) = method.instructions.withIndex().lastOrNull { (_, insn) ->
                insn.opcode == Opcode.INVOKE_VIRTUAL &&
                    ((insn as ReferenceInstruction).reference as? MethodReference)?.let { ref ->
                        ref.definingClass == builderType &&
                            ref.parameterTypes.map { it.toString() } ==
                            listOf("Ljava/lang/String;", "Ljava/lang/String;")
                    } == true
            } ?: continue
            val header = call as FiveRegisterInstruction
            val reference = (call as ReferenceInstruction).reference.toString()
            // Its name/value registers are dead after the call because the method returns
            // right after it (true in both builds), so reuse them for a second header.
            if (method.instructions.getOrNull(index + 1)?.opcode != Opcode.RETURN_VOID) {
                throw PatchException(
                    "${method.definingClass}->${method.name}: expected return-void after the header call",
                )
            }
            method.addInstructions(
                index + 1,
                """
                    const-string v${header.registerD}, "Authorization"
                    const-string v${header.registerE}, "$RIF_IMGUR_CLIENT_ID"
                    invoke-virtual { v${header.registerC}, v${header.registerD}, v${header.registerE} }, $reference
                """,
            )
        }
    }
}
