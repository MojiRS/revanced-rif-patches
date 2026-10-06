extension {
    name = "extensions/extension.rve"
}

android {
    namespace = "app.revanced.extension"

    // Match rif's minSdk so platform APIs (LruCache, Bitmap.getByteCount, ...)
    // aren't flagged; these helpers are injected into rif, which is minSdk 21.
    defaultConfig {
        minSdk = 21
    }

    lint {
        abortOnError = false
    }
}

dependencies {
    // Compile-only stub of rif's RifBaseSettingsFragment (source: stubs/); provided by
    // rif at runtime, so it must never be bundled.
    compileOnly(files("libs/rif-stubs.jar"))
}

// The extension plugin applies Kotlin, which implicitly puts kotlin-stdlib on the runtime
// classpath. This extension is pure Java and uses none of it, yet it was ~96% of
// extension.rve (~2 MB, 1000+ kotlin.* classes merged into rif, which ships its own copy).
// Keep it out of the bundle.
configurations.matching { it.name.endsWith("RuntimeClasspath") }.configureEach {
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib")
    exclude(group = "org.jetbrains", module = "annotations")
}
