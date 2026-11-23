import java.util.Base64

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.smartphonecare.app"
    compileSdk = 34

    defaultConfig {
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    // Two build flavors from ONE shared codebase, matching the two APKs
    // the web app already expects: SC-Shop.apk (shop's own phone, role
    // "owner") and SC-Reseller.apk (financed customer/reseller phone,
    // the one that gets locked/tracked).
    flavorDimensions += "role"
    productFlavors {
        create("shop") {
            dimension = "role"
            applicationId = "com.smartphonecare.owner"
            resValue("string", "app_label", "SC Shop")
            buildConfigField("String", "ROLE", "\"owner\"")
            buildConfigField("String", "HUB_URL", "\"https://repairshop.smartdukkanpos.com/\"")
        }
        create("reseller") {
            dimension = "role"
            applicationId = "com.smartphonecare.reseller"
            resValue("string", "app_label", "SC Reseller")
            buildConfigField("String", "ROLE", "\"reseller\"")
            buildConfigField("String", "HUB_URL", "\"https://repairhub.smartdukkanpos.com/\"")
        }
    }

    // Must come before buildTypes below: buildTypes.release looks up
    // signingConfigs.getByName("release"), and this android {} block is
    // evaluated top to bottom, so the signingConfigs entry has to already
    // exist by the time buildTypes reaches for it (was declared after it
    // before, which failed every CI build with "SigningConfig with name
    // 'release' not found").
    signingConfigs {
        create("release") {
            val ksPath = System.getenv("SC_KEYSTORE_PATH")
            if (ksPath != null) {
                storeFile = file(ksPath)
                storePassword = System.getenv("SC_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("SC_KEY_ALIAS")
                keyPassword = System.getenv("SC_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Signing is applied by the CI workflow (see .github/workflows/build-apk.yml),
            // which decodes the keystore from a GitHub secret. Building locally in
            // Android Studio without that secret still works — Android Studio will
            // just ask you to pick/create a local keystore for a release build, or
            // use `./gradlew assembleDebug` to skip signing entirely for a test install.
            if (System.getenv("SC_KEYSTORE_PATH") != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        debug {
            applicationIdSuffix = ".debug"
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.gms:play-services-location:21.3.0")

    val camerax = "1.3.4"
    implementation("androidx.camera:camera-core:$camerax")
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
}
