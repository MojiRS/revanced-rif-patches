package app.revanced.patches.rif.links

import app.revanced.patcher.extensions.InstructionExtensions.addInstructions
import app.revanced.patcher.extensions.InstructionExtensions.instructions
import app.revanced.patcher.fingerprint
import app.revanced.patcher.patch.BytecodePatchContext
import app.revanced.patcher.patch.PatchException
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.patches.rif.settings.revancedSettingsPatch
import app.revanced.patches.rif.shared.RIF_COMPATIBILITY
import app.revanced.patches.rif.shared.requireScratchRegister
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val EXTENSION = "Lapp/revanced/extension/rif/ShareLinks;"
private const val MAIN_ACTIVITY = "Lcom/andrewshu/android/reddit/MainActivity;"

// The parameter types of ShareLinks.route/intercept: rif's own types are passed as Object.
private val STUB_PARAMS = listOf(
    "Ljava/lang/String;", "Ljava/lang/String;", "Ljava/lang/Object;", "Ljava/lang/String;",
    "Ljava/lang/String;", "Z", "Ljava/lang/Object;", "Ljava/lang/Object;",
    "Landroid/content/Context;", "Ljava/lang/Object;",
)

private fun ClassDef.constStrings(): Set<String> =
    methods.flatMap { m ->
        m.implementation?.instructions
            ?.filter { it.opcode == Opcode.CONST_STRING }
            ?.map { (it as ReferenceInstruction).reference.toString() }
            .orEmpty()
    }.toSet()

// rif's link router (free l3.g.q / Platinum m3.g.q): public static
// q(String url, String, NoOpenType, String, String, boolean, WhitelistStatus,
//   FragmentManager, Context, BackStackSource) -> void. Every link tap in rif ends here. Matched by
// shape, in the class holding both "ACTION_COPY_URL" and "com.android.browser.application_id"
// (a pair unique to the router class in both builds).
internal val linkRouterFingerprint = fingerprint {
    custom { method, classDef ->
        AccessFlags.PUBLIC.isSet(method.accessFlags) &&
            AccessFlags.STATIC.isSet(method.accessFlags) &&
            method.returnType == "V" &&
            method.parameterTypes.size == 10 &&
            method.parameterTypes[0].toString() == "Ljava/lang/String;" &&
            method.parameterTypes[7].toString() == "Landroidx/fragment/app/FragmentManager;" &&
            method.parameterTypes[8].toString() == "Landroid/content/Context;" &&
            classDef.constStrings().let {
                "com.andrewshu.android.reddit.ACTION_COPY_URL" in it &&
                    "com.android.browser.application_id" in it
            }
    }
}

// MainActivity.l1()V (same name in both builds): dispatches the VIEW intent to a fragment.
private val mainActivityIntentFingerprint = fingerprint {
    custom { method, classDef ->
        classDef.type == MAIN_ACTIVITY &&
            method.name == "l1" &&
            method.returnType == "V" &&
            method.parameterTypes.isEmpty() &&
            method.implementation?.instructions?.any {
                it.opcode == Opcode.CONST_STRING &&
                    (it as ReferenceInstruction).reference.toString() == "reddit url type %s"
            } == true
    }
}

private fun ClassDef.isPublic() = AccessFlags.PUBLIC.isSet(accessFlags)

private fun Instruction.methodRef(): MethodReference? =
    if (opcode == Opcode.INVOKE_STATIC || opcode == Opcode.INVOKE_STATIC_RANGE) {
        (this as ReferenceInstruction).reference as? MethodReference
    } else {
        null
    }

private fun MethodReference.sig() = "$definingClass->$name(${parameterTypes.joinToString("")})$returnType"

/**
 * Writes ShareLinks.opensExternally(Object uri, Object mode)Z: rif's own decision, in the router's
 * openUri (free l3.g.g / Platinum m3.g.g), whether a link it can't classify goes to the external
 * browser: mode FORCE_EXTERNAL_ALWAYS ("External browser URLs", always) -> yes; links rif keeps in
 * the app (InAppMediaPolicy.shouldOpenInApp) -> no; mode ALLOW_IN_APP_OVERRIDE (a rule without
 * "always") -> yes; else "Prefer external browser" (InAppMediaPolicy.c). The references are read
 * from openUri by shape, in order: the Uri normaliser, the two open-mode constants and the two
 * (Uri)Z checks. A private check is replaced by the public method it wraps.
 */
private fun BytecodePatchContext.writeOpensExternally(router: Method) {
    val openUriRef = router.implementation!!.instructions.mapNotNull { it.methodRef() }.firstOrNull {
        it.definingClass == router.definingClass && it.parameterTypes.size == 10 &&
            it.parameterTypes[0].toString() == "Landroid/net/Uri;" && it.returnType == "V"
    } ?: throw PatchException("router's openUri call not found")
    fun find(ref: MethodReference): Method? = classes.firstOrNull { it.type == ref.definingClass }?.methods
        ?.firstOrNull { it.name == ref.name && it.parameterTypes.map(Any::toString) == ref.parameterTypes.map(Any::toString) && it.returnType == ref.returnType }
    val openUri = find(openUriRef) ?: throw PatchException("openUri ${openUriRef.sig()} not found")
    val insns = openUri.implementation!!.instructions.toList()
    val modeType = router.parameterTypes[2].toString()
    val calls = insns.mapNotNull { it.methodRef() }
    val normalise = calls.firstOrNull {
        it.parameterTypes.map(Any::toString) == listOf("Landroid/net/Uri;") && it.returnType == "Landroid/net/Uri;"
    } ?: throw PatchException("openUri: Uri normaliser not found")
    val checks = calls.filter {
        it.parameterTypes.map(Any::toString) == listOf("Landroid/net/Uri;") && it.returnType == "Z"
    }
    val modes = insns.filter { it.opcode == Opcode.SGET_OBJECT }
        .map { (it as ReferenceInstruction).reference as FieldReference }
        .filter { it.type == modeType }
    if (checks.size != 2 || modes.size != 2) {
        throw PatchException("openUri: expected 2 (Uri)Z checks and 2 open modes, found ${checks.size} and ${modes.size}")
    }
    // A check our extension can't call (private) is replaced by the one public call it makes.
    fun callable(ref: MethodReference): MethodReference {
        val def = find(ref) ?: throw PatchException("${ref.sig()} not found")
        val owner = classes.first { it.type == ref.definingClass }
        if (AccessFlags.PUBLIC.isSet(def.accessFlags) && owner.isPublic()) return ref
        val inner = def.implementation?.instructions?.mapNotNull { it.methodRef() }
            ?.singleOrNull() ?: throw PatchException("${ref.sig()} isn't public and doesn't wrap one call")
        if (inner.parameterTypes.map(Any::toString) != ref.parameterTypes.map(Any::toString) || inner.returnType != ref.returnType) {
            throw PatchException("${ref.sig()} wraps ${inner.sig()}, not the same check")
        }
        return callable(inner)
    }
    val inApp = callable(checks[0]).sig()
    val preferExternal = callable(checks[1]).sig()
    val norm = callable(normalise).sig()
    val (force, allow) = modes.map { "${it.definingClass}->${it.name}:${it.type}" }

    val stub = proxy(classes.first { it.type == EXTENSION }).mutableClass.methods
        .firstOrNull { it.name == "opensExternally" } ?: throw PatchException("ShareLinks.opensExternally not found")
    // p0 = uri, p1 = mode, p2 = always null: the working register (d8 gives the stub no
    // locals of its own; its `return false` reuses p0).
    stub.addInstructions(
        0,
        """
            check-cast p0, Landroid/net/Uri;
            invoke-static { p0 }, $norm
            move-result-object p0
            sget-object p2, $force
            if-eq p1, p2, :external
            invoke-static { p0 }, $inApp
            move-result p2
            if-nez p2, :inapp
            sget-object p2, $allow
            if-eq p1, p2, :external
            invoke-static { p0 }, $preferExternal
            move-result p2
            return p2
            :external
            const/4 p2, 0x1
            return p2
            :inapp
            const/4 p2, 0x0
            return p2
        """,
    )
}

@Suppress("unused")
val fixShareLinksPatch = bytecodePatch(
    name = "Fix share links",
    description = "Opens Reddit share links (reddit.com/r/…/s/…) directly in rif instead of bouncing " +
        "through the browser, and fixes reddit.com/media image links and poll links.",
) {
    compatibleWith(*RIF_COMPATIBILITY)
    // A widget can hand the router a null Context; the extension falls back to the app's.
    dependsOn(revancedSettingsPatch)
    extendWith("extensions/extension.rve")

    execute {
        // Hook 1: the router. A share link is resolved in the background and the router is
        // called again with the permalink.
        val router = linkRouterFingerprint.method
        val routerTypes = router.parameterTypes.map { it.toString() }
        requireScratchRegister(router)
        router.addInstructions(
            0,
            """
                invoke-static/range { p0 .. p9 }, $EXTENSION->intercept(${STUB_PARAMS.joinToString("")})Z
                move-result v0
                if-eqz v0, :orig
                return-void
                :orig
                nop
            """,
        )

        // ShareLinks.route is an empty stub with the router's parameters as Objects; make it
        // cast them to the router's real types and call it (types differ per build).
        val stub = proxy(classes.first { it.type == EXTENSION }).mutableClass.methods
            .firstOrNull { it.name == "route" } ?: throw PatchException("ShareLinks.route not found")
        val casts = routerTypes.indices
            .filter { routerTypes[it] != STUB_PARAMS[it] }
            .joinToString("\n") { "check-cast p$it, ${routerTypes[it]}" }
        stub.addInstructions(
            0,
            """
                $casts
                invoke-static/range { p0 .. p9 }, ${router.definingClass}->${router.name}(${routerTypes.joinToString("")})V
            """,
        )

        writeOpensExternally(router)

        // Hook 2: a share link opened from another app (VIEW intent) would otherwise start a
        // MainActivity that opens the in-app browser, which starts a second one.
        val dispatch = mainActivityIntentFingerprint.method
        requireScratchRegister(dispatch)
        dispatch.addInstructions(
            0,
            """
                invoke-static { p0 }, $EXTENSION->interceptIntent(Landroid/app/Activity;)Z
                move-result v0
                if-eqz v0, :go
                return-void
                :go
                nop
            """,
        )
    }
}
