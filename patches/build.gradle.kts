group = "crimera"

patches {
    about {
        name = "SafeX Piko"
        description = "Piko with SafeX adaptive NSFW filtering for X"
        source = "git@github.com:moram225/piko.git"
        author = "moram225 (SafeX) / crimera (Piko upstream)"
        contact = "na"
        website = "https://github.com/moram225/piko"
        license = "GNU General Public License v3.0"
    }
}

dependencies {
    // Used by JsonGenerator.
    implementation(libs.gson)

    implementation(libs.morphe.patches.library)
}

tasks {
    register<JavaExec>("checkStringResources") {
        description = "Checks resource strings for invalid formatting"

        dependsOn(compileKotlin)

        classpath = sourceSets["main"].runtimeClasspath
        mainClass.set("app.morphe.util.resource.CheckStringKt")
    }

    register<JavaExec>("checkSafeXSmali") {
        description = "Compiles SafeX high-register inline smali used by the cached URT hook"

        dependsOn(compileKotlin)

        classpath = sourceSets["main"].runtimeClasspath
        mainClass.set("app.crimera.util.SafeXSmaliCheckKt")
    }

    register<JavaExec>("generatePatchesList") {
        description = "Build patch with patch list"

        dependsOn(build)

        classpath = sourceSets["main"].runtimeClasspath
        mainClass.set("app.morphe.util.PatchListGeneratorKt")
    }
    // Used by gradle-semantic-release-plugin.
    publish {
        dependsOn("generatePatchesList")
    }
}

kotlin {
    compilerOptions {
        freeCompilerArgs = listOf("-Xcontext-parameters")
    }
}
