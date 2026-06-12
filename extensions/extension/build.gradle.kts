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
    // Compile-only stub of rif's RifBaseSettingsFragment; provided by rif at runtime.
    compileOnly(files("libs/rif-stubs.jar"))
}
