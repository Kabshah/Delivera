import java.io.File

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

// ── Node engine asset pruning note ───────────────────────────────────────────
// The engine's node_modules are installed by npm on whatever HOST builds the
// app (Windows dev box / Ubuntu CI). npm then drops sharp's native image-codec
// binaries for THAT host into node_modules/@img — none of them can ever load
// under nodejs-mobile on Android. On-device, sharp resolves to the pure-WASM
// build (@img/sharp-wasm32) via its loader fallback, so pruning every other
// platform variant changes zero runtime behaviour while removing ~18 MB of
// dead weight from the APK. Same story for @types/* and package-shipped
// tests/docs/benchmarks. See the pruneNodeModules task below.

android {
    namespace = "com.kabshah.delivra"
    compileSdk = 35
    buildToolsVersion = "35.0.0"

    defaultConfig {
        applicationId = "com.kabshah.delivra"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "1.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true
        externalNativeBuild {
            cmake {
                cppFlags += ""
                arguments("-DANDROID_STL=c++_shared")
            }
        }
    }

    // ── APK size optimization ────────────────────────────────────────────────
    // One APK per ABI instead of one fat universal APK. Each split bundles only
    // its own libnode.so (~47 MB stripped); together they cover every real
    // phone from 2017 onwards (arm64-v8a = everything modern, armeabi-v7a =
    // legacy 32-bit budget devices). x86/x86_64 are emulator-only ABIs and are
    // deliberately not shipped.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a")
            isUniversalApk = false
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
        }
    }

    externalNativeBuild {
        cmake {
            path("CMakeLists.txt")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    androidResources {
        // The UI ships English-only strings; strip AndroidX/Material locale
        // tables for the ~80 languages we never localized into.
        localeFilters += listOf("en")
    }

    packaging {
        jniLibs {
            // Prevent duplicate .so conflicts from nodejs-mobile-android
            pickFirsts += setOf("**/libnode.so", "**/libc++_shared.so")
        }
    }
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.2")

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.material)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)

    // Room
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // WorkManager
    implementation(libs.androidx.work.runtime.ktx)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)

    // Coroutines
    implementation(libs.kotlinx.coroutines.android)

    // DataStore
    implementation(libs.androidx.datastore.preferences)

    // Accompanist
    implementation(libs.accompanist.permissions)
    
    // (JitPack dependency removed — we now use local Node.js binaries & CMake)

    debugImplementation(libs.androidx.ui.tooling)
}

// ── pruneNodeModules ─────────────────────────────────────────────────────────
// Physically removes files that can never execute on Android from the Node
// engine's node_modules BEFORE they get merged into the APK assets:
//   1. sharp's native image-codec binaries for every platform except the
//      pure-WASM build (@img/sharp-wasm32, which is what actually loads under
//      nodejs-mobile). npm re-installs these for whatever OS runs the build
//      (win32-x64 on this dev box, linux-x64 on CI), so they must be pruned at
//      packaging time — deleting them once is not enough.
//   2. @types/node (TypeScript definitions; protobufjs lists it as a dep but
//      never requires it at runtime).
//   3. Package-shipped tests / docs / benchmarks / coverage reports.
// Runs before every merge*Assets task, so assembleDebug/assembleRelease always
// package a clean tree regardless of npm state.
val nodeModulesDir = file("src/main/assets/nodejs-project/node_modules")

val pruneNodeModules = tasks.register("pruneNodeModules") {
    description = "Removes host-only sharp binaries and package junk that can never run on Android."
    doLast {
        var freed = 0L

        fun dirSize(root: File): Long =
            root.walkTopDown().filter { it.isFile }.sumOf { it.length() }

        fun removePath(path: File) {
            if (!path.exists()) return
            freed += if (path.isDirectory) dirSize(path) else path.length()
            path.deleteRecursively()
        }

        // 1. Everything under @img except colour + sharp-wasm32
        val imgDir = File(nodeModulesDir, "@img")
        imgDir.listFiles()?.forEach { child ->
            if (child.name != "colour" && child.name != "sharp-wasm32") removePath(child)
        }

        // 2. Type definitions
        removePath(File(nodeModulesDir, "@types/node"))

        // 2b. Sourcemaps + TypeScript definitions + markdown docs — never read
        //     at runtime (~10 MB combined here). LICENSE/NOTICE files stay.
        nodeModulesDir.walkTopDown().filter { it.isFile }.forEach { f ->
            val legal = f.name.startsWith("LICENSE", ignoreCase = true) ||
                    f.name.startsWith("NOTICE", ignoreCase = true)
            val isJunk = f.extension == "map" ||
                    f.name.endsWith(".d.ts") ||
                    ((f.extension == "md" || f.extension == "markdown") && !legal)
            if (isJunk) {
                freed += f.length()
                f.delete()
            }
        }

        // 2c. protobufjs ships three builds (full / light / minimal); the whole
        //     tree only ever imports "protobufjs/minimal.js". Keep minimal.
        listOf(
            "protobufjs/dist/light",
            "protobufjs/dist/protobuf.js",
            "protobufjs/dist/protobuf.js.map",
            "protobufjs/dist/protobuf.min.js",
            "protobufjs/dist/protobuf.min.js.map",
            "protobufjs/google",
            "protobufjs/ext",
            "protobufjs/scripts"
        ).forEach { rel -> removePath(File(nodeModulesDir, rel)) }

        // 2d. axios' browser bundles (runtime uses lib/ via package main)
        removePath(File(nodeModulesDir, "axios/dist"))

        // 2e. The minifier toolchain itself (terser + deps) — build-time only,
        //     pulled in by the postinstall hook, never needed on device.
        listOf("terser", "acorn", "source-map", "source-map-support").forEach { pkg ->
            removePath(File(nodeModulesDir, pkg))
        }

        // 3. Tests / docs / benchmarks inside packages (paths relative to node_modules)
        listOf(
            "pino/test", "pino/benchmarks", "pino/docs", "pino/docsify", "pino/examples", "pino/.github",
            "pino-abstract-transport/test", "pino-abstract-transport/.github", "pino-abstract-transport/.husky",
            "pino-std-serializers/test", "pino-std-serializers/.github",
            "@pinojs/redact/test", "@pinojs/redact/benchmarks", "@pinojs/redact/scripts", "@pinojs/redact/.github",
            "pngjs/coverage",
            "thread-stream/test", "thread-stream/.github", "thread-stream/.husky", "thread-stream/.claude",
            "sonic-boom/test", "sonic-boom/types", "sonic-boom/fixtures",
            "process-warning/test", "process-warning/benchmarks", "process-warning/examples", "process-warning/types",
            "axios/dist/browser", "axios/dist/esm"
        ).forEach { rel -> removePath(File(nodeModulesDir, rel)) }

        println("[pruneNodeModules] removed ${"%.1f".format(freed / 1048576.0)} MB of host-only/junk assets")
    }
}

tasks.whenTaskAdded {
    if (name.startsWith("merge") && name.endsWith("Assets")) {
        dependsOn(pruneNodeModules)
    }
}
