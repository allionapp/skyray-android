import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    id("com.jaredsburrows.license")
}

// The Play bundle is signed with SkyRay's upload key — the key Google Play knows, the same the owner
// signs with — whenever its settings file is present: V2rayNG/keystore.properties, or the file named
// by -Pskyray.keystore=<path>. Keys: storeFile (next to that file), storePassword, keyAlias,
// keyPassword. Neither file is ever in git. The direct APKs stay with the developer's key (their
// CI), so a direct update keeps installing over the one people already have.
val uploadKeyFile: File? = ((findProperty("skyray.keystore") as String?)?.let { file(it) }
    ?: rootProject.file("keystore.properties")).takeIf { it.exists() }
val uploadKey: Properties? = uploadKeyFile?.let { f -> Properties().apply { f.inputStream().use { load(it) } } }

android {
    signingConfigs {
        if (uploadKey != null) {
            create("upload") {
                val store = uploadKey.getProperty("storeFile")
                // Next to the settings file; app/ too, where the owner's older project keeps it.
                storeFile = listOf(File(store), File(uploadKeyFile!!.parentFile, store), File(uploadKeyFile.parentFile, "app/$store"))
                    .firstOrNull { it.isAbsolute && it.exists() }
                    ?: error("SkyRay upload key: $store not found next to $uploadKeyFile")
                storePassword = uploadKey.getProperty("storePassword")
                keyAlias = uploadKey.getProperty("keyAlias")
                keyPassword = uploadKey.getProperty("keyPassword")
            }
        }
    }

    // Only the languages the app itself is translated into. Libraries bring dozens more, and
    // Google Play refuses a bundle whose "he" it cannot handle for translation.
    androidResources {
        localeFilters += listOf("en", "ar", "bn", "bqi-rIR", "fa", "ru", "vi", "zh-rCN", "zh-rTW")
    }
    namespace = "com.v2ray.ang"
    compileSdk = 37

    defaultConfig {
        // EthaVPN: its own id (both apps can be installed side by side); the code keeps the
        // upstream namespace so the fork stays a small diff.
        applicationId = "com.allion.skyray"   // the id registered on Google Play (and the App Store bundle id)
        minSdk = 24
        targetSdk = 37
        // 4000000 + the build number: the same code in every ABI split and in the Play bundle, so a
        // phone can move between the direct APK and the Play install (the updater compares versionName).
        versionCode = 4000124
        versionName = "1.2.4"
        multiDexEnabled = true
        manifestPlaceholders["subHost"] = "fra.skyrayconfig.org"   // AppConfig.ETHA_SUB_HOST: the only link address

        val abiFilterList = (properties["ABI_FILTERS"] as? String)?.split(';')
        splits {
            abi {
                isEnable = true
                reset()
                if (!abiFilterList.isNullOrEmpty()) {
                    include(*abiFilterList.toTypedArray())
                } else {
                    include(
                        "arm64-v8a",
                        "armeabi-v7a",
                        "x86_64",
                        "x86"
                    )
                }
                isUniversalApk = abiFilterList.isNullOrEmpty()
            }
        }

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    // Two distributions of the same code: direct = the APKs from the service's own download page
    // and the bot (with the in-app updater); play = the Google Play bundle (updates come from Play,
    // no installer permission — src/play/AndroidManifest.xml).
    flavorDimensions.add("distribution")
    productFlavors {
        create("direct") {
            dimension = "distribution"
            buildConfigField("String", "DISTRIBUTION", "\"Direct\"")
        }
        create("play") {
            dimension = "distribution"
            buildConfigField("String", "DISTRIBUTION", "\"Play\"")
            if (uploadKey != null) signingConfig = signingConfigs.getByName("upload")
        }
    }

    sourceSets {
        getByName("main") {
            jniLibs.srcDirs("libs")
        }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    applicationVariants.all {
        val variant = this
        // The file names are what latest.json and ethavpn-app-publish expect (every split keeps
        // the one versionCode from defaultConfig).
        variant.outputs
            .map { it as com.android.build.gradle.internal.api.ApkVariantOutputImpl }
            .forEach { output ->
                val abi = output.getFilter("ABI") ?: "universal"
                output.outputFileName = "SkyRay_${variant.versionName}_${abi}.apk"
            }
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

}

dependencies {
    // The Google Play build's ad (AdsGate in src/play); the direct build has none.
    implementation("com.google.android.gms:play-services-ads:23.6.0")
    implementation("com.google.android.ump:user-messaging-platform:3.1.0")
    // The ads SDK brings Guava at runtime only, which leaves WorkManager's ListenableFuture
    // resolved to Guava's empty stub at compile time; the same version, made visible.
    implementation("com.google.guava:guava:31.1-android")
    // Core Libraries
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar", "*.jar"))))

    // AndroidX Core Libraries
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.preference.ktx)
    implementation(libs.recyclerview)
    implementation(libs.androidx.swiperefreshlayout)
    implementation(libs.androidx.viewpager2)
    implementation(libs.androidx.fragment)

    // UI Libraries
    implementation(libs.material)
    implementation(libs.toasty)
    implementation(libs.editorkit)
    implementation(libs.flexbox)

    // Data and Storage Libraries
    implementation(libs.mmkv.static)
    implementation(libs.gson)
    implementation(libs.okhttp)

    // Reactive and Utility Libraries
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.core)

    // Language and Processing Libraries
    implementation(libs.language.base)
    implementation(libs.language.json)

    // Intent and Utility Libraries
    implementation(libs.quickie.foss)
    implementation(libs.core)

    // AndroidX Lifecycle and Architecture Components
    implementation(libs.lifecycle.viewmodel.ktx)
    implementation(libs.lifecycle.livedata.ktx)
    implementation(libs.lifecycle.runtime.ktx)

    // Background Task Libraries
    implementation(libs.work.runtime.ktx)
    implementation(libs.work.multiprocess)

    // Multidex Support
    implementation(libs.multidex)

    // Testing Libraries
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    testImplementation(libs.org.mockito.mockito.inline)
    testImplementation(libs.mockito.kotlin)
    coreLibraryDesugaring(libs.desugar.jdk.libs)
}
