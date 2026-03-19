plugins {
    alias(libs.plugins.android.application)
}

val appVersion = "0.5.3"
val buildNumberFile = file("build-number.txt")
val buildNumber = if (buildNumberFile.exists()) buildNumberFile.readText().trim().toInt() else 0

android {
    namespace = "su.grinev.myvpn"
    compileSdk = 36

    defaultConfig {
        applicationId = "su.grinev.myvpn"
        minSdk = 29
        targetSdk = 34

        versionCode = buildNumber
        versionName = appVersion

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            versionNameSuffix = "-snapshot.$buildNumber"
        }
        release {
            isMinifyEnabled = false
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
    implementation("su.grinev:jbson:0.5.3-compat")
    compileOnly("org.projectlombok:lombok:1.18.30")
    annotationProcessor("org.projectlombok:lombok:1.18.30")
    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.constraintlayout)
    implementation(libs.navigation.fragment)
    implementation(libs.navigation.ui)
    testImplementation(libs.junit)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)
}
