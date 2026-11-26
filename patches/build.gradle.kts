group = "app.revanced"

patches {
    about {
        name = "rif patches"
        description = "Custom ReVanced patches for rif is fun (Reddit is Fun)"
        source = "https://github.com/MojiRS/revanced-rif-patches"
        author = "Moji"
        contact = "contact@moji.tel"
        website = "https://github.com/MojiRS/revanced-rif-patches"
        license = "GNU General Public License v3.0"
    }
}

dependencies {
    // JVM tests over the compiled patches (src/test). Test-only, never bundled.
    testImplementation("junit:junit:4.13.2")
}

// buildAndroid doesn't run the tests by itself; make it do so (cached when nothing changed).
tasks.matching { it.name == "buildAndroid" }.configureEach { dependsOn("test") }
