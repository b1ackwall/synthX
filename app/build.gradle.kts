plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.kotlin.serialization)
}

val productionMigrationDbAssetName = "production-v11-migration.db"
val productionMigrationDbPath = providers.gradleProperty("productionDbPath")
val productionMigrationDbAssetDir =
    layout.buildDirectory.dir("generated/productionMigrationDbTestAssets")

val stageProductionMigrationDb = tasks.register("stageProductionMigrationDb") {
    group = "verification"
    description = "Stages an external production DB copy for the opt-in migration test"
    inputs.property("productionDbPath", productionMigrationDbPath.orElse(""))
    outputs.dir(productionMigrationDbAssetDir)
    outputs.upToDateWhen { false }

    doLast {
        val outputDir = productionMigrationDbAssetDir.get().asFile
        val destination = outputDir.resolve(productionMigrationDbAssetName)
        if (destination.exists()) {
            check(destination.delete()) { "Could not remove stale production migration fixture" }
        }

        val suppliedPath = productionMigrationDbPath.orNull ?: return@doLast
        val source = file(suppliedPath)
        require(source.isFile) { "productionDbPath is not a file: $suppliedPath" }

        check(outputDir.mkdirs() || outputDir.isDirectory) {
            "Could not create production migration staging directory"
        }
        require(source.canonicalFile != destination.canonicalFile) {
            "Production migration source and staging destination must differ"
        }
        source.inputStream().use { input ->
            destination.outputStream().use(input::copyTo)
        }
    }
}

android {
    namespace = "com.v1per.synthx"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.v1per.synthx"
        minSdk = 32
        targetSdk = 36
        versionCode = 11
        versionName = "2.5.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            resValue("string", "app_name", "synthX dev")
        }
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            resValue("string", "app_name", "synthX")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        compose = true
        resValues = true
        buildConfig = true
    }

    testOptions {
        unitTests {
            // android.util.Log is unmocked in local JVM unit tests by default and throws on
            // any call; retryOnFailure (P1-T6) is the first unit-tested code path that logs
            // via Log.w, so let unmocked android.* calls return their default value instead.
            isReturnDefaultValues = true
        }
    }

    sourceSets.getByName("androidTest").assets.srcDir("schemas")
    sourceSets.getByName("androidTest").assets.srcDir(productionMigrationDbAssetDir.get().asFile)
}

kotlin {
    compilerOptions {
        // Pinned: Gradle's default module name ("synthX:app") contains a ':',
        // which Windows silently truncates in compose_reports output filenames,
        // producing empty report files.
        moduleName.set("synthX-app")
    }
}

tasks.matching {
    it.name.startsWith("merge") && it.name.endsWith("AndroidTestAssets")
}.configureEach {
    dependsOn(stageProductionMigrationDb)
}

composeCompiler {
    metricsDestination = layout.buildDirectory.dir("compose_metrics")
    reportsDestination = layout.buildDirectory.dir("compose_reports")
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.animation)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)

    implementation(libs.androidx.navigation.compose)

    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.session)
    implementation(libs.androidx.media3.common)

    implementation(libs.accompanist.permissions)
    implementation(libs.coil.compose)
    implementation(libs.androidx.documentfile)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.reorderable)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.collections.immutable)

    // JUnit4 variant named explicitly: built-in Kotlin doesn't infer it from kotlin("test").
    testImplementation(kotlin("test-junit"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")

    androidTestImplementation("androidx.test:core-ktx:1.6.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit-ktx:1.2.1")
    androidTestImplementation("androidx.room:room-testing:2.8.4")
    androidTestImplementation("androidx.work:work-testing:2.9.1")
    // Shell-level Compose assertions (RestoreShellInstrumentedTest); versioned by the BOM above.
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    // ui-test-junit4 drags in espresso-core 3.5.0 transitively, whose Espresso.onIdle() reflects on
    // InputManager.getInstance() — removed on recent platform releases (API 37 emulator: every
    // Compose UI test died with NoSuchMethodException). Pin a release that no longer does.
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")

    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}
