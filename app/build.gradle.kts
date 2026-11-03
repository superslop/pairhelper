plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "io.github.superslop.pairhelper"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.superslop.pairhelper"
        minSdk = 30 // Android 11 — required for Wireless debugging
        targetSdk = 35
        versionCode = 2
        versionName = "0.1.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // PairHelper ships no translations of its own; this drops the locales
        // AppCompat and Material carry for their framework strings.
        resourceConfigurations += listOf("en")
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
    }

    packaging {
        resources {
            // R8 strips unreachable Bouncy Castle classes but not their Java
            // resources. libadb only reaches SHA-256, AES-GCM, HKDF and
            // Base64, so the post-quantum tables and cert-path catalogues
            // carried by its transitive dependencies are dead weight.
            excludes += listOf(
                "org/bouncycastle/pqc/**",
                "org/bouncycastle/x509/CertPathReviewerMessages*.properties",
                "DebugProbesKt.bin",
                "META-INF/*.version",
                "META-INF/**/LICENSE.txt",
                "kotlin/**",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    dependenciesInfo {
        // Google-encrypted dependency blob; only Google can read it. Strip it
        // so F-Droid / IzzyOnDroid scanners don't flag an opaque block.
        includeInApk = false
        includeInBundle = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.lifecycle.runtime.ktx)

    // LibADB Android
    implementation(libs.libadb.android)

    // Custom Conscrypt. Required, not merely recommended: pairing derives
    // the SPAKE2 secret from the TLS exporter, and the platform fallback is a
    // hidden com.android.org.conscrypt class whose reflective lookup is
    // blocked on Android 11+, so pairing fails without this.
    implementation(libs.conscrypt.android)

    // RSA key generation compatible with ADB authentication
    implementation(libs.sun.security.android)

    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
}
