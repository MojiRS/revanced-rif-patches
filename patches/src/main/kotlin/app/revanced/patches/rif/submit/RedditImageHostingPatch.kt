package app.revanced.patches.rif.submit

import app.revanced.patcher.extensions.InstructionExtensions.addInstruction
import app.revanced.patcher.extensions.InstructionExtensions.addInstructions
import app.revanced.patcher.extensions.InstructionExtensions.instructions
import app.revanced.patcher.extensions.InstructionExtensions.replaceInstruction
import app.revanced.patcher.fingerprint
import app.revanced.patcher.patch.BytecodePatchContext
import app.revanced.patcher.patch.PatchException
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.patcher.patch.resourcePatch
import app.revanced.patcher.util.proxy.mutableTypes.MutableMethod
import app.revanced.patches.rif.settings.revancedSettingsPatch
import app.revanced.patches.rif.shared.RIF_COMPATIBILITY
import app.revanced.patches.rif.shared.requireScratchRegister
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ThreeRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.WideLiteralInstruction

private const val EXTENSION = "Lapp/revanced/extension/rif/ImageHosting;"
private const val SUBMIT_FRAGMENT = "Lcom/andrewshu/android/reddit/submit/m;"
private const val SUBMIT_TASK = "Lcom/andrewshu/android/reddit/submit/SubmitTask;"
private const val PAIR_ARRAY = "[Landroidx/core/util/c;"
private const val URI = "Landroid/net/Uri;"

/** rif's imgur size limit (10 MiB), and ours: the most a still image is sent as is. */
private const val TEN_MB = 10485760L
private const val TWENTY_MB_HEX = "0x1400000"

// The Image tab's texts, without imgur and with the new limit. Quoted values keep their quotes
// (aapt2 reads them as quoting).
private val STRINGS = mapOf(
    "submit_image_label" to "image (hosted by Reddit)",
    "submit_image_confirmation_label" to "image hosting by Reddit",
    "submit_image_confirmation_requires_resize" to
        "\"The image size (%s bytes) is over Reddit's 20 MiB limit. RIF will upload a reduced-quality copy.\"",
    "submit_image_upload_status_success" to "uploaded.",
    "imgur_upload_error" to "Error uploading image. Please try again later.",
    "error_resizing_image" to
        "Error resizing image. Please compress your image to 20 MiB or smaller before uploading.",
)

private val redditImageHostingStringsPatch = resourcePatch(
    description = "Removes imgur from the Image tab's texts.",
) {
    compatibleWith(*RIF_COMPATIBILITY)

    execute {
        document("res/values/strings.xml").use { doc ->
            val nodes = doc.getElementsByTagName("string")
            var changed = 0
            for (i in 0 until nodes.length) {
                val node = nodes.item(i)
                val text = STRINGS[node.attributes?.getNamedItem("name")?.nodeValue] ?: continue
                node.textContent = text
                changed++
            }
            if (changed != STRINGS.size) {
                throw PatchException("expected ${STRINGS.size} Image tab strings, found $changed")
            }
        }
    }
}

private fun Method.hasString(vararg wanted: String): Boolean {
    val found = implementation?.instructions
        ?.filter { it.opcode == Opcode.CONST_STRING }
        ?.map { (it as ReferenceInstruction).reference.toString() }
        .orEmpty()
    return wanted.all { it in found }
}

private fun Method.params() = parameterTypes.map { it.toString() }

// rif's imgur uploader task body (free k3.b.H / Platinum l3.b.H): protected
// H(Void[]) -> String, the only method holding both of imgur's strings.
internal val imageUploadFingerprint = fingerprint {
    custom { method, _ ->
        method.returnType == "Ljava/lang/String;" &&
            method.params() == listOf("[Ljava/lang/Void;") &&
            method.hasString("Client-ID 4d7e2f74f1a519c", "Non-OK response code: %d")
    }
}

// SubmitTask (kept class and member names in both builds): d0() builds the form parameters,
// k0(InputStream) parses the reply.
internal val submitParamsFingerprint = fingerprint {
    custom { method, classDef ->
        classDef.type == SUBMIT_TASK && method.name == "d0" && method.parameterTypes.isEmpty() &&
            method.returnType == PAIR_ARRAY
    }
}

internal val submitResponseFingerprint = fingerprint {
    custom { method, classDef ->
        classDef.type == SUBMIT_TASK && method.name == "k0" &&
            method.params() == listOf("Ljava/io/InputStream;")
    }
}

private fun Instruction.isTenMb() =
    opcode == Opcode.CONST_WIDE_32 && (this as WideLiteralInstruction).wideLiteral == TEN_MB

private fun Method.hasTenMb() = implementation?.instructions?.any { it.isTenMb() } == true

private fun Method.callsNumberFormat() = implementation?.instructions?.any {
    it.opcode == Opcode.INVOKE_STATIC &&
        (it as ReferenceInstruction).reference.toString() ==
        "Ljava/text/NumberFormat;->getIntegerInstance(Ljava/util/Locale;)Ljava/text/NumberFormat;"
} == true

/** The one method holding the 10 MiB constant that [matches]; throws on none or several. */
private fun BytecodePatchContext.tenMbMethod(
    what: String,
    matches: (Method, ClassDef) -> Boolean,
): Pair<ClassDef, MutableMethod> {
    val found = classes.flatMap { c ->
        c.methods.filter { it.hasTenMb() && matches(it, c) }.map { c to it }
    }
    if (found.size != 1) throw PatchException("$what: expected 1 match, found ${found.size}")
    val (classDef, method) = found.single()
    val mutable = proxy(classDef).mutableClass.methods.first {
        it.name == method.name && it.params() == method.params() && it.returnType == method.returnType
    }
    return classDef to mutable
}

/**
 * Index of the 10 MiB `const-wide/32` and of the `cmp-long` a few instructions later that compares
 * against it.
 */
private fun MutableMethod.tenMbCompare(): Pair<Int, Int> {
    val code = instructions.toList()
    val at = code.indexOfFirst { it.isTenMb() }
    if (at < 0) throw PatchException("$definingClass->$name: limit not found")
    val limit = (code[at] as OneRegisterInstruction).registerA
    val cmpAt = (at + 1..minOf(at + 6, code.size - 1)).firstOrNull {
        code[it].opcode == Opcode.CMP_LONG && (code[it] as ThreeRegisterInstruction).registerC == limit
    } ?: throw PatchException("$definingClass->$name: no cmp-long against the limit")
    return at to cmpAt
}

/**
 * Replaces `size > 10 MiB` with `ImageHosting.needsResize(uri, size)` ([uriInstruction] puts the Uri
 * in the limit's register first): the limit's `const-wide/32` becomes the Uri load, the `cmp-long`
 * the call, and its result takes the cmp's place for the `if-` that follows.
 */
private fun MutableMethod.useNeedsResize(uriInstruction: (limitRegister: Int) -> String) {
    val (at, cmpAt) = tenMbCompare()
    val code = instructions.toList()
    val limit = (code[at] as OneRegisterInstruction).registerA
    val cmp = code[cmpAt] as ThreeRegisterInstruction
    val size = cmp.registerB
    replaceInstruction(at, uriInstruction(limit))
    replaceInstruction(cmpAt, "invoke-static { v$limit, v$size, v${size + 1} }, $EXTENSION->needsResize(${URI}J)Z")
    addInstruction(cmpAt + 1, "move-result v${cmp.registerA}")
}

/** Raises a 10 MiB limit to 20 MiB, keeping the instruction's register. */
private fun MutableMethod.raiseLimit() {
    val at = instructions.toList().indexOfFirst { it.isTenMb() }
    val register = (instructions.toList()[at] as OneRegisterInstruction).registerA
    replaceInstruction(at, "const-wide/32 v$register, $TWENTY_MB_HEX")
}

@Suppress("unused")
val redditImageHostingPatch = bytecodePatch(
    name = "Reddit image hosting",
    description = "Uploads images and GIFs for posts to Reddit instead of imgur and posts them as native image posts. " +
        "GIFs are never resized.",
) {
    compatibleWith(*RIF_COMPATIBILITY)
    dependsOn(redditImageHostingStringsPatch, revancedSettingsPatch)
    extendWith("extensions/extension.rve")

    execute {
        // Hook 1: rif's uploader task. Its body is replaced by a call into the extension
        // (the file's Uri and Context come from the task's own fields), which returns the asset
        // URL for the URL field, or null and an error text for rif's "Upload error" dialog.
        val upload = imageUploadFingerprint.method
        val task = upload.definingClass
        val fields = imageUploadFingerprint.classDef.fields.associate { it.name to it.type }
        val wanted = mapOf("j" to "Ljava/lang/ref/WeakReference;", "k" to "J", "l" to URI, "o" to "Ljava/lang/String;")
        for ((name, type) in wanted) {
            if (fields[name] != type) throw PatchException("$task: expected field $name:$type")
        }
        requireScratchRegister(upload)
        if (upload.implementation!!.registerCount < 6) throw PatchException("too few registers in $task->${upload.name}")
        upload.addInstructions(
            0,
            """
                iget-object v0, p0, $task->l:$URI
                iget-object v1, p0, $task->j:Ljava/lang/ref/WeakReference;
                invoke-virtual { v1 }, Ljava/lang/ref/WeakReference;->get()Ljava/lang/Object;
                move-result-object v1
                check-cast v1, Landroid/content/Context;
                invoke-static { v0, v1 }, $EXTENSION->size(${URI}Landroid/content/Context;)J
                move-result-wide v2
                iput-wide v2, p0, $task->k:J
                invoke-static { p0, v0, v1 }, $EXTENSION->upload(Ljava/lang/Object;${URI}Landroid/content/Context;)Ljava/lang/String;
                move-result-object v2
                invoke-static { }, $EXTENSION->takeError()Ljava/lang/String;
                move-result-object v3
                iput-object v3, p0, $task->o:Ljava/lang/String;
                return-object v2
            """,
        )

        // Hook 2: the 10 MiB checks. GIFs never resize; stills resize only above 20 MiB.
        // The confirm-and-upload branch (submit fragment): size > limit means "resize".
        tenMbMethod("upload/resize branch") { m, c ->
            c.type == SUBMIT_FRAGMENT && m.params() == listOf(URI, "J") && m.returnType == "V"
        }.second.useNeedsResize { limit -> "move-object v$limit, p1" }

        // The confirm dialog's "requires resize" warning (the dialog holds the Uri in its only Uri field).
        val (dialogClass, dialogText) = tenMbMethod("resize warning") { m, _ ->
            m.returnType == "V" && m.parameterTypes.isEmpty() && m.callsNumberFormat()
        }
        val uriFields = dialogClass.fields.filter { it.type == URI && !AccessFlags.STATIC.isSet(it.accessFlags) }
        if (uriFields.size != 1) throw PatchException("${dialogClass.type}: expected one Uri field, found ${uriFields.size}")
        dialogText.useNeedsResize { limit -> "iget-object v$limit, p0, ${dialogClass.type}->${uriFields.single().name}:$URI" }

        // The size query's state (both states open the same dialog) and the resize target.
        tenMbMethod("size state") { m, _ -> m.returnType == "V" && m.params() == listOf("Ljava/lang/Long;") }
            .second.raiseLimit()
        tenMbMethod("resize target") { m, _ ->
            m.name == "<init>" && m.parameterTypes.size == 2 && m.parameterTypes[0].toString() == URI
        }.second.raiseLimit()

        // Hook 3: kind=image for our asset URLs.
        val params = submitParamsFingerprint.method
        val returns = params.instructions.withIndex().filter { it.value.opcode == Opcode.RETURN_OBJECT }
        if (returns.isEmpty()) throw PatchException("no return in SubmitTask.d0")
        for ((index, insn) in returns.reversed()) {
            val register = (insn as OneRegisterInstruction).registerA
            params.addInstructions(
                index,
                """
                    invoke-static { v$register }, $EXTENSION->fixParams($PAIR_ARRAY)$PAIR_ARRAY
                    move-result-object v$register
                """,
            )
        }

        // Hook 4: the reply of an image post has no post in it; the extension finds the post.
        submitResponseFingerprint.method.addInstructions(
            0,
            """
                invoke-static { p1 }, $EXTENSION->wrapResponse(Ljava/io/InputStream;)Ljava/io/InputStream;
                move-result-object p1
            """,
        )
    }
}
