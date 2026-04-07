plugins {
    alias(libs.plugins.android.application)
}

val appVersion = "0.7.1"
val buildNumberFile = file("build-number.txt")
val buildNumber = if (buildNumberFile.exists()) buildNumberFile.readText().trim().toInt() else 0

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

tasks.register("incrementBuildNumber") {
    doLast {
        val current = if (buildNumberFile.exists()) buildNumberFile.readText().trim().toInt() else 0
        buildNumberFile.writeText((current + 1).toString())
        println("Build number incremented to ${current + 1}")
    }
}

tasks.matching { it.name == "assembleDebug" }.configureEach {
    dependsOn("incrementBuildNumber")
}

dependencies {
    implementation("su.grinev:jbson:0.6.4")
    compileOnly("org.projectlombok:lombok:1.18.30")
    annotationProcessor("org.projectlombok:lombok:1.18.30")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.constraintlayout)
    implementation(libs.navigation.fragment)
    implementation(libs.navigation.ui)
    testImplementation(libs.junit)
    testImplementation("org.mockito:mockito-core:5.11.0")
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)
}
