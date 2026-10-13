plugins {
    alias(libs.plugins.android.test)
    // gradle.properties keeps android.builtInKotlin=false, so Kotlin is applied explicitly.
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.v1per.synthx.macrobenchmark"
    compileSdk = 37

    defaultConfig {
        minSdk = 32
        targetSdk = 36
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        // Pairs with :app's benchmark build type. The test APK itself may be debuggable.
        create("benchmark") {
            isDebuggable = true
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
        }
    }

    targetProjectPath = ":app"
    experimentalProperties["android.experimental.self-instrumenting"] = true

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
    }
}

// Only the benchmark variant exists, so nothing here can target the debug app.
androidComponents {
    beforeVariants(selector().all()) { variant ->
        variant.enable = variant.buildType == "benchmark"
    }
}

dependencies {
    implementation(libs.androidx.benchmark.macro.junit4)
    implementation(libs.androidx.test.uiautomator)
    implementation(libs.androidx.test.ext.junit)
}
