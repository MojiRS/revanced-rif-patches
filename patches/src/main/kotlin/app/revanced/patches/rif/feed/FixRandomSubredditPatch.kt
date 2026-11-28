package app.revanced.patches.rif.feed

import app.revanced.patcher.extensions.InstructionExtensions.addInstructions
import app.revanced.patcher.extensions.InstructionExtensions.instructions
import app.revanced.patcher.extensions.InstructionExtensions.replaceInstruction
import app.revanced.patcher.fingerprint
import app.revanced.patcher.patch.BytecodePatchContext
import app.revanced.patcher.patch.PatchException
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.patcher.patch.resourcePatch
import app.revanced.patches.rif.settings.addRevancedPreferenceCategory
import app.revanced.patches.rif.settings.checkBoxPreference
import app.revanced.patches.rif.settings.revancedSettingsPatch
import app.revanced.patches.rif.settings.revancedSettingsResourcePatch
import app.revanced.patches.rif.shared.RIF_COMPATIBILITY
import app.revanced.patches.rif.shared.requireScratchRegister
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

private const val EXTENSION = "Lapp/revanced/extension/rif/RandomSubreddit;"
private const val URI = "Landroid/net/Uri;"

// The request base's background step `doInBackground(Void...)Object` (free h3.c.H, Platinum
// i3.c.H): runs on a worker thread for every Reddit request (also for the listing loader
// h3.a$a, whose override calls super) and reads the request URI from the base's `Uri` field
// at every use. The class is the one the `Y(Response, ...)` error handler lives in, found by
// its shape and "com.reddit" const-string (as Explain Reddit blocks finds it). Not by name: other
// bundles that hook the handler rename it (Patcheddit 2.5.0: `morpheOriginal_Y_0`).
internal val requestBackgroundFingerprint = fingerprint {
    returns("Ljava/lang/Object;")
    parameters("[Ljava/lang/Void;")
    custom { method, classDef ->
        !AccessFlags.SYNTHETIC.isSet(method.accessFlags) && !AccessFlags.BRIDGE.isSet(method.accessFlags) &&
            classDef.methods.any { it.parameterTypes.size == 4 && it.hasConst("com.reddit") }
    }
}

private fun Method.hasConst(value: String) = implementation?.instructions?.any {
    it.opcode == Opcode.CONST_STRING && ((it as ReferenceInstruction).reference as StringReference).string == value
} == true

// The subreddit picker adapter's `onBindViewHolder(Holder, int)V` (free c4.i.p, Platinum d4.i.V):
// the only method with all four special-row labels as const-strings. Matched by shape, not name.
private fun Method.isPickerBind() = returnType == "V" && parameterTypes.size == 2 &&
    parameterTypes[1] == "I" && !AccessFlags.SYNTHETIC.isSet(accessFlags) &&
    listOf("popular", "all", "random", "mod").all { hasConst(it) }

internal val pickerBindFingerprint = fingerprint {
    custom { method, _ -> method.isPickerBind() }
}

// The same adapter's no-argument `r()V` (free c4.i.r, Platinum d4.i.X) that refills the list of
// row ids: it clears an ArrayList, adds Integers, and ends with notifyDataSetChanged (renamed in
// Platinum) and return-void.
internal val pickerRowsFingerprint = fingerprint {
    returns("V")
    parameters()
    custom { method, classDef ->
        !AccessFlags.STATIC.isSet(method.accessFlags) && classDef.methods.any { it.isPickerBind() } &&
            method.implementation?.instructions?.let { code ->
                code.count { it.opcode == Opcode.INVOKE_VIRTUAL && it.isCall("Ljava/util/ArrayList;", "add") } >= 4 &&
                    code.any { it.opcode == Opcode.INVOKE_VIRTUAL && it.isCall("Ljava/util/ArrayList;", "clear") }
            } == true
    }
}

private fun Instruction.isCall(definingClass: String, name: String) =
    ((this as? ReferenceInstruction)?.reference as? MethodReference)
        ?.let { it.definingClass == definingClass && it.name == name } == true

/**
 * Two hooks in the subreddit picker's adapter for the optional randnsfw row (row id 5). rif
 * opens `/r/<row text>` on a tap, so the label is all the row needs.
 */
private fun BytecodePatchContext.addPickerRow() {
    // The row ids: before r() ends with notifyDataSetChanged, the extension adds id 5 to the
    // adapter's ArrayList (when the setting is on). Only that call and return-void follow, so any
    // local register is free; requireScratchRegister proves v0 is one.
    val rows = pickerRowsFingerprint.method
    val rowCode = rows.implementation!!.instructions.toList()
    val notify = rowCode.lastIndex - 1
    val notifyCall = rowCode.getOrNull(notify) as? FiveRegisterInstruction
    if (rowCode.last().opcode != Opcode.RETURN_VOID || notifyCall == null ||
        notifyCall.opcode != Opcode.INVOKE_VIRTUAL || notifyCall.registerCount != 1 || notifyCall.registerC == 0 ||
        ((notifyCall as ReferenceInstruction).reference as MethodReference).let { it.returnType != "V" || it.parameterTypes.isNotEmpty() }
    ) {
        throw PatchException("notifyDataSetChanged call not found at the end of ${rows.definingClass}->${rows.name}")
    }
    val listField = rowCode.firstNotNullOfOrNull {
        ((it as? ReferenceInstruction)?.reference as? FieldReference)
            ?.takeIf { f -> it.opcode == Opcode.IGET_OBJECT && f.type == "Ljava/util/ArrayList;" }
    } ?: throw PatchException("row id list not found in ${rows.definingClass}->${rows.name}")
    requireScratchRegister(rows)
    // The notify call is a branch target (the mod-row check jumps straight to it), and
    // instructions added at an index stay before the label, so a jump would skip them. Replace
    // the call with our first instruction (the label stays on it) and re-add the call after ours.
    val notifyRef = (notifyCall as ReferenceInstruction).reference as MethodReference
    rows.replaceInstruction(
        notify,
        "iget-object v0, p0, ${listField.definingClass}->${listField.name}:Ljava/util/ArrayList;",
    )
    rows.addInstructions(
        notify + 1,
        """
            invoke-static { v0 }, $EXTENSION->addPickerRow(Ljava/util/ArrayList;)V
            invoke-virtual { v${notifyCall.registerC} }, ${notifyRef.definingClass}->${notifyRef.name}()V
        """,
    )

    // The label: just before onBindViewHolder sets it on the row's TextView, let the extension swap
    // in "randnsfw" for id 5 (rif's chain gives null for an unknown id). The label register is the
    // one the "random" const-string loads (the chain's join point); the id register is where
    // Integer.intValue() lands.
    val bind = pickerBindFingerprint.method
    val code = bind.implementation!!.instructions.toList()
    val labelRegister = code.firstNotNullOfOrNull {
        if (it.opcode == Opcode.CONST_STRING &&
            ((it as ReferenceInstruction).reference as StringReference).string == "random"
        ) {
            (it as OneRegisterInstruction).registerA
        } else {
            null
        }
    } ?: throw PatchException("random label not found in ${bind.definingClass}->${bind.name}")
    val intValue = code.indexOfFirst { it.opcode == Opcode.INVOKE_VIRTUAL && it.isCall("Ljava/lang/Integer;", "intValue") }
    val idMove = code.getOrNull(intValue + 1)
    if (intValue < 0 || idMove?.opcode != Opcode.MOVE_RESULT) {
        throw PatchException("row id not found in ${bind.definingClass}->${bind.name}")
    }
    val idRegister = (idMove as OneRegisterInstruction).registerA
    val setText = code.indexOfFirst {
        it.opcode == Opcode.INVOKE_VIRTUAL && it is FiveRegisterInstruction && it.registerCount == 2 &&
            it.registerD == labelRegister &&
            (((it as ReferenceInstruction).reference as MethodReference).let { m ->
                m.name == "setText" && m.parameterTypes.toList() == listOf("Ljava/lang/CharSequence;")
            })
    }
    if (setText <= intValue) throw PatchException("label setText not found in ${bind.definingClass}->${bind.name}")
    // The id must still be in its register where the label is set.
    if (code.subList(intValue + 2, setText).any { it.opcode.setsRegister() && (it as OneRegisterInstruction).registerA == idRegister }) {
        throw PatchException("row id register overwritten before the label is set in ${bind.definingClass}->${bind.name}")
    }
    bind.addInstructions(
        setText,
        """
            invoke-static { v$labelRegister, v$idRegister }, $EXTENSION->pickerLabel(Ljava/lang/String;I)Ljava/lang/String;
            move-result-object v$labelRegister
        """,
    )
}

/** Adds the "Show randnsfw in sidebar" checkbox to the rif patches screen. Off by default. */
val fixRandomSubredditSettingsResourcePatch = resourcePatch(
    description = "Adds the randnsfw sidebar setting.",
) {
    compatibleWith(*RIF_COMPATIBILITY)
    dependsOn(revancedSettingsResourcePatch)

    execute {
        addRevancedPreferenceCategory("Random subreddit") { doc, category ->
            category.appendChild(
                doc.checkBoxPreference(
                    "RANDNSFW_SIDEBAR",
                    "Show randnsfw in sidebar",
                    default = false,
                ),
            )
        }
    }
}

@Suppress("unused")
val fixRandomSubredditPatch = bytecodePatch(
    name = "Fix random subreddit",
    description = "Makes Random and r/randnsfw open random subreddits again, sourced from redditrand.com. " +
        "Optional randnsfw row in the sidebar (off by default).",
) {
    compatibleWith(*RIF_COMPATIBILITY)
    dependsOn(fixRandomSubredditSettingsResourcePatch, revancedSettingsPatch)
    extendWith("extensions/extension.rve")

    execute {
        addPickerRow()

        val method = requestBackgroundFingerprint.method
        val base = requestBackgroundFingerprint.classDef

        // The request URI: the base's only (protected final) Uri instance field. Not final, so
        // the hook can replace it.
        val uriField = base.fields.singleOrNull {
            it.type == URI && !AccessFlags.STATIC.isSet(it.accessFlags)
        } ?: throw PatchException("request URI field not found in ${base.type}")
        val mutableBase = proxy(base).mutableClass
        mutableBase.fields.first { it.name == uriField.name && it.type == URI }.let {
            it.accessFlags = it.accessFlags and AccessFlags.FINAL.value.inv()
        }

        // At the top of doInBackground (registers: this = p0, the Void[] = p1, which the method
        // never reads, so p1 is scratch): `this.uri = RandomSubreddit.resolve(this.uri)`. The
        // extension returns the same Uri unless it is a random listing and redditrand answered.
        method.addInstructions(
            0,
            """
                iget-object p1, p0, ${base.type}->${uriField.name}:$URI
                invoke-static { p1 }, $EXTENSION->resolve($URI)$URI
                move-result-object p1
                iput-object p1, p0, ${base.type}->${uriField.name}:$URI
            """,
        )
    }
}
