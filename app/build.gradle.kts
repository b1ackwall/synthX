plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.v1per.synthx"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.v1per.synthx"
        minSdk = 32
        targetSdk = 36
        versionCode = 12
        versionName = "2.6"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        resValue("string", "app_name", "synthX")
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
                "proguard-rules.pro",
            )
        }
        // Macrobenchmark target: release code and R8, debug-signed, profileable through
        // src/benchmark/AndroidManifest.xml. Its own package, so it never replaces production.
        create("benchmark") {
            initWith(getByName("release"))
            applicationIdSuffix = ".benchmark"
            versionNameSuffix = "-benchmark"
            isDebuggable = false
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
            resValue("string", "app_name", "synthX bench")
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
            // android.util.Log is unmocked in local JVM unit tests and throws on any call;
            // retryOnFailure logs via Log.w, so let unmocked android.* calls return their
            // default value instead.
            isReturnDefaultValues = true
        }
    }

    // Part of scripts/check.sh: any Lint finding above hint level (checkAllWarnings, tests included) fails the build.
    // app/lint.xml holds the recorded ignores; there is no baseline.
    lint {
        checkAllWarnings = true
        checkTestSources = true
        warningsAsErrors = true
        abortOnError = true
    }

    sourceSets.getByName("androidTest").assets.directories.add("schemas")
}

kotlin {
    compilerOptions {
        // Pinned: Gradle's default module name ("synthX:app") contains a ':',
        // which Windows silently truncates in compose_reports output filenames,
        // producing empty report files.
        moduleName.set("synthX-app")
        // Part of scripts/check.sh: a new compiler warning in main, test or androidTest sources fails the build.
        allWarningsAsErrors.set(true)
    }
}

// Fails a benchmark build before it starts if its package could replace production.
androidComponents {
    onVariants(selector().withBuildType("benchmark")) { variant ->
        val taskSuffix = variant.name.replaceFirstChar { it.uppercase() }
        val applicationId = variant.applicationId
        val checkApplicationId = tasks.register("check${taskSuffix}ApplicationId") {
            group = "verification"
            description = "Verifies the benchmark applicationId is not the production one"
            inputs.property("applicationId", applicationId)
            doLast {
                val id = applicationId.get()
                check(id != "com.v1per.synthx" && id.endsWith(".benchmark")) {
                    "Benchmark build applicationId is '$id'; it must end in '.benchmark' and " +
                        "never be the production 'com.v1per.synthx'"
                }
            }
        }
        tasks.matching { it.name == "pre${taskSuffix}Build" }.configureEach {
            dependsOn(checkApplicationId)
        }
    }
}

// Compose compiler reports and metrics are written only with -PcomposeReports (any value but "false"),
// one folder per Kotlin compilation: build/compose-reports/<compilation>/ (debug, debugUnitTest, ...).
val composeReports = providers.gradleProperty("composeReports").map { it != "false" }.getOrElse(false)
if (composeReports) {
    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
        val compilation = name.removePrefix("compile").removeSuffix("Kotlin").replaceFirstChar { it.lowercase() }
        val dir = layout.buildDirectory.dir("compose-reports/$compilation").get().asFile
        compilerOptions.freeCompilerArgs.addAll(
            "-P",
            "plugin:androidx.compose.compiler.plugins.kotlin:reportsDestination=$dir",
            "-P",
            "plugin:androidx.compose.compiler.plugins.kotlin:metricsDestination=$dir",
        )
    }
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
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.concurrent.futures.ktx)
    implementation(libs.reorderable)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.collections.immutable)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.tracing)

    // JUnit4 variant named explicitly: built-in Kotlin doesn't infer it from kotlin("test").
    testImplementation(kotlin("test-junit"))
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(libs.androidx.test.core.ktx)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit.ktx)
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(libs.androidx.work.testing)
    // Shell-level Compose assertions (RestoreShellInstrumentedTest); versioned by the BOM above.
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    // ui-test-junit4 drags in espresso-core 3.5.0 transitively, whose Espresso.onIdle() reflects on
    // InputManager.getInstance() — removed on recent platform releases (API 37 emulator: every
    // Compose UI test died with NoSuchMethodException). Pin a release that no longer does.
    androidTestImplementation(libs.androidx.test.espresso.core)

    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}
