package app.revanced.patches.rif

import app.revanced.patcher.patch.Patch
import app.revanced.patches.rif.ads.platinumAdSlotFixPatch
import java.io.File
import java.lang.reflect.Modifier
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every named (user-visible) patch must reach the hidden [platinumAdSlotFixPatch] through its
 * dependencies, directly or via the shared settings patch. Otherwise applying only that patch to
 * Platinum under free rif's package name brings the feed-refresh crash back. The patcher has no
 * "global dependency", so this rule is only enforced here.
 *
 * Patches are found by reflection: every static Patch-typed field (top-level `val`s compile to
 * private static fields of the file's `...Kt` class) in the compiled app.revanced.patches.rif
 * package, so a new patch file is checked without touching this test.
 */
class PlatinumAdSlotFixDependencyTest {
    private fun allPatches(): List<Patch> {
        // Any class of the module under test locates the compiled classes directory.
        val anchor = Class.forName("app.revanced.patches.rif.ads.PlatinumAdSlotFixKt")
        val root = File(
            anchor.protectionDomain.codeSource.location.toURI(),
        ).also { check(it.isDirectory) { "expected a classes directory, got $it" } }
        val loader = anchor.classLoader
        val found = LinkedHashMap<Patch, String>() // Patch has no equals, so keys are by identity
        root.resolve("app/revanced/patches/rif").walkTopDown()
            .filter { it.isFile && it.extension == "class" }
            .map { it.relativeTo(root).path.removeSuffix(".class").replace(File.separatorChar, '.') }
            .forEach { name ->
                val cls = Class.forName(name, false, loader)
                for (f in cls.declaredFields) {
                    if (!Modifier.isStatic(f.modifiers) || !Patch::class.java.isAssignableFrom(f.type)) continue
                    f.isAccessible = true
                    found.putIfAbsent(f.get(null) as Patch, "$name.${f.name}")
                }
            }
        return found.keys.toList()
    }

    private fun reaches(patch: Patch, target: Patch, seen: MutableSet<Patch> = HashSet()): Boolean {
        if (!seen.add(patch)) return false
        return patch.dependencies.any { it === target || reaches(it, target, seen) }
    }

    @Test
    fun everyNamedPatchReachesPlatinumAdSlotFix() {
        val named = allPatches().filter { it.name != null }
        println("PlatinumAdSlotFixDependencyTest: ${named.size} named patches: " + named.joinToString { it.name!! })
        assertTrue("found no named patches: patch discovery is broken", named.size >= 10)
        val offenders = named.filterNot { reaches(it, platinumAdSlotFixPatch) }.map { it.name }
        assertTrue(
            "Named patches that don't reach platinumAdSlotFixPatch (add dependsOn(platinumAdSlotFixPatch) " +
                "or dependsOn(revancedSettingsPatch)): $offenders",
            offenders.isEmpty(),
        )
    }
}
