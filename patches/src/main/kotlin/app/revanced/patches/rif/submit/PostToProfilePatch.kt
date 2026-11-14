package app.revanced.patches.rif.submit

import app.revanced.patcher.extensions.InstructionExtensions.addInstructions
import app.revanced.patcher.extensions.InstructionExtensions.instructions
import app.revanced.patcher.fingerprint
import app.revanced.patcher.patch.PatchException
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.patcher.patch.resourcePatch
import app.revanced.patches.rif.ads.platinumAdSlotFixPatch
import app.revanced.patches.rif.shared.RIF_COMPATIBILITY
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import org.w3c.dom.Element

private const val EXTENSION = "Lapp/revanced/extension/rif/PostToProfile;"
private const val FRAGMENT = "Lcom/andrewshu/android/reddit/submit/m;"
private const val STRING = "Ljava/lang/String;"

private const val MY_PROFILE_STRING = "rif_my_profile"
private const val MY_PROFILE_TEXT = "my profile"

private val postToProfileResourcePatch = resourcePatch(
    description = "Adds the MY PROFILE button beside PICK SUBREDDIT on the submit page.",
) {
    compatibleWith(*RIF_COMPATIBILITY)

    execute {
        document("res/values/strings.xml").use { doc ->
            val added = doc.createElement("string")
            added.setAttribute("name", MY_PROFILE_STRING)
            added.textContent = MY_PROFILE_TEXT
            doc.documentElement.appendChild(added)
        }

        // PICK SUBREDDIT (onClick="pickReddit") sits alone under the subreddit field. It moves into
        // a row with a copy of itself, hidden until the extension finds an account: MY PROFILE on
        // the left, PICK SUBREDDIT still on the right.
        for (layout in listOf("res/layout/submit_fragment.xml", "res/layout-v22/submit_fragment.xml")) {
            document(layout).use { doc ->
                val buttons = doc.getElementsByTagName("Button")
                val picks = (0 until buttons.length)
                    .map { buttons.item(it) as Element }
                    .filter { it.getAttribute("android:onClick") == "pickReddit" }
                if (picks.size != 1) {
                    throw PatchException("$layout: expected 1 pickReddit button, found ${picks.size}")
                }
                val pick = picks.single()
                val parent = pick.parentNode

                val mine = pick.cloneNode(false) as Element
                mine.removeAttribute("android:onClick")
                mine.removeAttribute("android:layout_gravity")
                mine.removeAttribute("android:layout_marginEnd")
                mine.setAttribute("android:layout_marginStart", "4.0dip")
                mine.setAttribute("android:gravity", "start|center")
                mine.setAttribute("android:id", "@+id/rif_my_profile_button")
                mine.setAttribute("android:text", "@string/$MY_PROFILE_STRING")
                mine.setAttribute("android:visibility", "gone")

                val spacer = doc.createElement("View")
                spacer.setAttribute("android:layout_width", "0.0dip")
                spacer.setAttribute("android:layout_height", "0.0dip")
                spacer.setAttribute("android:layout_weight", "1.0")

                val row = doc.createElement("LinearLayout")
                row.setAttribute("android:orientation", "horizontal")
                row.setAttribute("android:layout_width", "fill_parent")
                row.setAttribute("android:layout_height", "wrap_content")

                parent.insertBefore(row, pick)
                parent.removeChild(pick)
                row.appendChild(mine)
                row.appendChild(spacer)
                row.appendChild(pick)
            }
        }
    }
}

private fun com.android.tools.smali.dexlib2.iface.Method.params() = parameterTypes.map { it.toString() }

private fun com.android.tools.smali.dexlib2.iface.Method.hasString(wanted: String) =
    implementation?.instructions?.any {
        it.opcode == Opcode.CONST_STRING && (it as ReferenceInstruction).reference.toString() == wanted
    } == true

// The submit fragment (kept class name in both builds)'s onCreateView (free O2 / Platinum D2).
internal val submitViewFingerprint = fingerprint {
    custom { method, classDef ->
        classDef.type == FRAGMENT && method.returnType == "Landroid/view/View;" &&
            method.params() == listOf(
                "Landroid/view/LayoutInflater;", "Landroid/view/ViewGroup;", "Landroid/os/Bundle;",
            ) && method.hasString("com.andrewshu.android.reddit.KEY_SUBMIT_KIND")
    }
}

@Suppress("unused")
val postToProfilePatch = bytecodePatch(
    name = "Post to profile",
    description = "Adds a button to the submit page that posts to your profile.",
) {
    compatibleWith(*RIF_COMPATIBILITY)
    dependsOn(postToProfileResourcePatch, platinumAdSlotFixPatch)
    extendWith("extensions/extension.rve")

    execute {
        val onCreateView = submitViewFingerprint.method
        val mutableClass = proxy(submitViewFingerprint.classDef).mutableClass
        val methods = mutableClass.methods

        // The method the subreddit picker's result calls (free L5 / Platinum A5): package-private
        // (String)V that just calls the private (String, boolean) setter with true. It has to be
        // public for the extension's stub to call it.
        val fillCandidates = methods.filter { m ->
            m.params() == listOf(STRING) && m.returnType == "V" && m.accessFlags == 0 &&
                m.instructions.map { it.opcode }.toList() ==
                listOf(Opcode.CONST_4, Opcode.INVOKE_DIRECT, Opcode.RETURN_VOID) &&
                ((m.instructions.elementAt(1) as ReferenceInstruction).reference as MethodReference).let {
                    it.definingClass == FRAGMENT && it.parameterTypes.map { p -> p.toString() } == listOf(STRING, "Z")
                }
        }
        if (fillCandidates.size != 1) {
            throw PatchException("submit fragment: expected 1 subreddit fill method, found ${fillCandidates.size}")
        }
        val fill = fillCandidates.single()
        fill.accessFlags = AccessFlags.PUBLIC.value

        // The "posting as" writer (free Y5 / Platinum N5): private (String)V that sets the
        // person line's text. onCreateView calls it as Y5(l4().q0()); the l4 and q0 calls just
        // before it give the active account's name.
        val postingAsCandidates = methods.filter { m ->
            AccessFlags.PRIVATE.isSet(m.accessFlags) && m.params() == listOf(STRING) && m.returnType == "V" &&
                m.instructions.map { it.opcode }.toList() == listOf(
                    Opcode.IGET_OBJECT, Opcode.IGET_OBJECT, Opcode.INVOKE_VIRTUAL, Opcode.RETURN_VOID,
                ) &&
                ((m.instructions.elementAt(2) as ReferenceInstruction).reference as MethodReference).name == "setText"
        }
        if (postingAsCandidates.size != 1) {
            throw PatchException("submit fragment: expected 1 posting-as method, found ${postingAsCandidates.size}")
        }
        val postingAs = postingAsCandidates.single()

        val code = onCreateView.instructions.toList()
        val callAt = code.indexOfFirst {
            it.opcode == Opcode.INVOKE_DIRECT &&
                (it as ReferenceInstruction).reference.toString() ==
                "$FRAGMENT->${postingAs.name}($STRING)V"
        }
        if (callAt < 4) throw PatchException("submit fragment: posting-as call not found in onCreateView")
        val q0 = (code[callAt - 2] as? ReferenceInstruction)?.reference as? MethodReference
        val l4 = (code[callAt - 4] as? ReferenceInstruction)?.reference as? MethodReference
        if (code[callAt - 2].opcode != Opcode.INVOKE_VIRTUAL || code[callAt - 4].opcode != Opcode.INVOKE_VIRTUAL ||
            code[callAt - 1].opcode != Opcode.MOVE_RESULT_OBJECT || code[callAt - 3].opcode != Opcode.MOVE_RESULT_OBJECT ||
            q0 == null || l4 == null || q0.returnType != STRING || q0.parameterTypes.isNotEmpty() ||
            l4.parameterTypes.isNotEmpty() || l4.returnType != q0.definingClass
        ) {
            throw PatchException("submit fragment: expected l4().q0() before the posting-as call")
        }

        // l4() is protected, so the stub can't call it; its body gets the settings from a static
        // no-argument getter (free k4.v.C / Platinum l4.v.C), which is public.
        val l4Method = classes.firstOrNull { it.type == l4.definingClass }?.methods
            ?.firstOrNull { it.name == l4.name && it.parameterTypes.isEmpty() && it.returnType == l4.returnType }
            ?: throw PatchException("submit fragment: ${l4.definingClass}->${l4.name} not found")
        val getters = l4Method.instructions.filter { it.opcode == Opcode.INVOKE_STATIC }
            .map { (it as ReferenceInstruction).reference as MethodReference }
            .filter { it.parameterTypes.isEmpty() && it.returnType == l4.returnType }
            .map { it.toString() }.toSet()
        if (getters.size != 1) throw PatchException("settings getter: expected 1 in l4(), found ${getters.size}")
        val settingsGetter = getters.single()

        // Stubs in the extension: write their bodies with rif's types.
        val stubs = proxy(classes.first { it.type == EXTENSION }).mutableClass.methods
        val accountName = stubs.firstOrNull { it.name == "accountName" } ?: throw PatchException("PostToProfile.accountName not found")
        accountName.addInstructions(
            0,
            """
                invoke-static { }, $settingsGetter
                move-result-object p0
                invoke-virtual { p0 }, $q0
                move-result-object p0
                return-object p0
            """,
        )
        val fillStub = stubs.firstOrNull { it.name == "fill" } ?: throw PatchException("PostToProfile.fill not found")
        fillStub.addInstructions(
            0,
            """
                check-cast p0, $FRAGMENT
                invoke-virtual { p0, p1 }, $FRAGMENT->${fill.name}($STRING)V
            """,
        )

        // Hook 1: the account changed (and onCreateView's own call): show or hide the button.
        // Nothing is written, so no scratch register is needed.
        postingAs.addInstructions(0, "invoke-static { p0 }, $EXTENSION->accountChanged(Ljava/lang/Object;)V")

        // Hook 2: end of onCreateView, the returned root view in hand: wire the button.
        val returns = code.withIndex().filter { it.value.opcode == Opcode.RETURN_OBJECT }
        if (returns.isEmpty()) throw PatchException("submit fragment: no return in onCreateView")
        for ((index, insn) in returns.reversed()) {
            val register = (insn as OneRegisterInstruction).registerA
            onCreateView.addInstructions(
                index,
                "invoke-static { v$register, p0 }, $EXTENSION->install(Landroid/view/View;Ljava/lang/Object;)V",
            )
        }
    }
}
