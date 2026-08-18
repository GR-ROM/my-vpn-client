plugins {
    alias(libs.plugins.android.application)
}

val appVersion = "0.9.0"   // real app version — reported in HELLO; server meters logins by platform+version
val buildNumberFile = file("build-number.txt")
// Bump the build number at configuration time when an actual build is requested, so the number
// baked into the APK matches build-number.txt. (The old separate task bumped at EXECUTION time —
// after the version was already read at configuration — so the APK lagged the file by one.)
// Plain reads (Android Studio sync, `./gradlew tasks`) don't bump.
val buildNumber: Int = run {
    val current = if (buildNumberFile.exists()) buildNumberFile.readText().trim().toInt() else 0
    val assembling = gradle.startParameter.taskNames.any {
        it.contains("assemble", ignoreCase = true) ||
        it.contains("bundle", ignoreCase = true) ||
        it.contains("install", ignoreCase = true)
    }
    if (assembling) {
        val next = current + 1
        buildNumberFile.writeText(next.toString())
        println("Build number -> $next")
        next
    } else {
        current
    }
}

android {
    namespace = "su.grinev.myvpn"
    compileSdk = 36

    defaultConfig {
        applicationId = "su.grinev.myvpn"
        minSdk = 29
        targetSdk = 36

        versionCode = buildNumber
        versionName = appVersion

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Account backend + the Google *Web* OAuth client id (not the Android one): it is the
        // audience billing verifies the id_token against. Override in local.properties or CI
        // rather than committing a real id.
        buildConfigField("String", "BILLING_BASE_URL",
            "\"${project.findProperty("billingBaseUrl") ?: "https://billing.grinev.su"}\"")
        buildConfigField("String", "GOOGLE_SERVER_CLIENT_ID",
            "\"${project.findProperty("googleServerClientId") ?: ""}\"")
        // Google Cloud project number backing Play Integrity; 0 disables the attestation call.
        buildConfigField("long", "INTEGRITY_CLOUD_PROJECT",
            "${project.findProperty("integrityCloudProject") ?: 0}L")
    }

    signingConfigs {
        create("release") {
            storeFile = file("${System.getProperty("user.home")}/.android/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            versionNameSuffix = "-snapshot.$buildNumber"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
}

dependencies {
    implementation("su.grinev:jbson-jdk21:0.9.0-24") // Java 21, no FFM, fresh pool — Android-10-safe (allocateDirect); 0.9.0 = record + @Size/@Range/@Pattern validation + enum UNKNOWN-fallback
    compileOnly("org.projectlombok:lombok:1.18.30")
    annotationProcessor("org.projectlombok:lombok:1.18.30")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.constraintlayout)
    implementation(libs.navigation.fragment)
    implementation(libs.navigation.ui)
    // Google Sign-In goes through Credential Manager; the legacy GoogleSignInClient is retired.
    implementation(libs.credentials)
    implementation(libs.credentials.play.services.auth)
    implementation(libs.googleid)
    // Refresh token and device JWT are bearer credentials — not for plain SharedPreferences.
    implementation(libs.security.crypto)
    // Attests at enroll that this is a genuine Play build on a genuine device (anti trial-farm).
    implementation(libs.play.integrity)
    testImplementation(libs.junit)
    testImplementation("org.mockito:mockito-core:5.11.0")
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)
}
