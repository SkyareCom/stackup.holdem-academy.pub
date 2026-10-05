plugins {
    id("com.android.application")
}

val releaseKeystore = System.getenv("STACKUP_UPLOAD_KEYSTORE")
val releaseStorePassword = System.getenv("STACKUP_UPLOAD_STORE_PASSWORD")
val releaseKeyAlias = System.getenv("STACKUP_UPLOAD_KEY_ALIAS")
val releaseKeyPassword = System.getenv("STACKUP_UPLOAD_KEY_PASSWORD")
val supabaseUrl = System.getenv("STACKUP_SUPABASE_URL") ?: "https://mzlznwnxahixoqyspsdy.supabase.co"
val supabaseAnonKey = System.getenv("STACKUP_SUPABASE_ANON_KEY") ?: "sb_publishable_E9cnM9HPU19f9hdFxzjXrg_FkD6clWQ"
val hasReleaseSigning = listOf(
    releaseKeystore,
    releaseStorePassword,
    releaseKeyAlias,
    releaseKeyPassword
).all { !it.isNullOrBlank() }

val repoRoot = rootProject.projectDir.parentFile
val academyWebAssetsDir = layout.buildDirectory.dir("generated/academyWebAssets")
val syncAcademyWebAssets = tasks.register<Sync>("syncAcademyWebAssets") {
    into(academyWebAssetsDir)
    from(repoRoot) {
        include(
            "index.html",
            "privacy.html",
            "manifest.webmanifest",
            "sw.js",
            "auth-production.js",
            "billing-production.js"
        )
    }
    from(repoRoot.resolve("icons")) {
        into("icons")
        include(
            "icon-192.png",
            "icon-512.png",
            "icon-maskable-192.png",
            "icon-maskable-512.png"
        )
    }
}

android {
    buildFeatures {
        buildConfig = true
    }

    namespace = "com.skyare.stackupacademy"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.skyare.stackupacademy"
        minSdk = 24
        targetSdk = 36
        versionCode = 221
        versionName = "2.1.8"
        buildConfigField("String", "SUPABASE_URL", "\"${supabaseUrl.replace("\\", "\\\\").replace("\"", "\\\"")}\"")
        buildConfigField("String", "SUPABASE_ANON_KEY", "\"${supabaseAnonKey.replace("\\", "\\\\").replace("\"", "\\\"")}\"")
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(releaseKeystore!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".test220"
            versionNameSuffix = "-test"
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        release {
            isMinifyEnabled = false
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    sourceSets {
        getByName("main") {
            assets.srcDir(academyWebAssetsDir.get().asFile)
        }
    }
}


tasks.named("preBuild").configure {
    dependsOn(syncAcademyWebAssets)
}


dependencies {
    implementation("androidx.webkit:webkit:1.14.0")
    implementation("com.android.billingclient:billing:9.1.0")
    implementation("androidx.credentials:credentials:1.6.0")
    implementation("androidx.credentials:credentials-play-services-auth:1.6.0")
    implementation("com.google.android.libraries.identity.googleid:googleid:1.2.1")
    implementation("androidx.biometric:biometric:1.1.0")
}
