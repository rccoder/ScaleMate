plugins { id("com.android.application"); id("org.jetbrains.kotlin.android"); id("org.jetbrains.kotlin.plugin.compose") }
val releaseKeystore = providers.environmentVariable("ANDROID_KEYSTORE_PATH").orNull
val releaseStorePassword = providers.environmentVariable("ANDROID_KEYSTORE_PASSWORD").orNull
val releaseKeyAlias = providers.environmentVariable("ANDROID_KEY_ALIAS").orNull
val releaseKeyPassword = providers.environmentVariable("ANDROID_KEY_PASSWORD").orNull
val releaseSigningReady = listOf(releaseKeystore, releaseStorePassword, releaseKeyAlias, releaseKeyPassword).all { !it.isNullOrBlank() }
android {
    namespace = "app.scalemate"
    compileSdk = 36
    buildToolsVersion = "36.0.0"
    defaultConfig {
        manifestPlaceholders["appLabel"] = if (providers.gradleProperty("foodDev").isPresent) "称伴 · 饮食开发版" else "@string/app_name"
        applicationId = if (providers.gradleProperty("foodDev").isPresent) "app.scalemate.fooddev" else "app.scalemate"
        minSdk = 34
        targetSdk = 36
        versionCode = 6
        versionName = "0.3.0-preview.1"
        buildConfigField("String", "UPDATE_PUBLIC_KEY_BASE64", "\"MIIBojANBgkqhkiG9w0BAQEFAAOCAY8AMIIBigKCAYEAtf6kkga/AnvxC0Cf9EMX/J+KZKPU5G4Uu1nW7U/jNMqAT1n+F+QM+FG10v8nTWlAud9K8DgoQyKqTtZ8r78QE02VD0ZC5lBzNxzjiZQK5Bm0VeERXcLDwKRzCCMwwVjV0QupPgikRUpzBTGUkkv66oCy8EpISQubbRK/aTm8HIGms0P0MpsOKapQwkDc+Ud41JCVEBNqA2YLlESmiNedS3/VM0f40di/Z5Ap68wXHp6dNnUXp3tTXXT0zq6VVCp11LIU4A1VhBm14cpxNkiU8nkFsjiikbD1R1wTZA7D16vpoB0zaFfsnL0pMlZbUqVSsR153c6nL53/L/bpNXMnCp70PfUj1ZsQF/3x+lEPJJ2mEC3CeyXRcakarKk47DvEbhVjqNVCLxvL4+56pYp3J3eoKTf7boxtDt5wHe6kuBKGJtGUl4SwEmcEIMH2QC6pDIvWC9QmcquM3vPIXy459yab3gXZ9gnYtMWTcl4BH3cX6aQbkQnWOpacIbkbv2QBAgMBAAE=\"")
        testApplicationId = "app.scalemate.test"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    signingConfigs {
        if (releaseSigningReady) create("release") {
            storeFile = file(releaseKeystore!!)
            storePassword = releaseStorePassword
            keyAlias = releaseKeyAlias
            keyPassword = releaseKeyPassword
        }
    }
    buildTypes {
        create("prototype") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".prototype"
            versionNameSuffix = "-ai-preview"
            matchingFallbacks += listOf("debug")
        }
        getByName("release") {
            isDebuggable = false
            if (releaseSigningReady) signingConfig = signingConfigs.getByName("release")
        }
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
gradle.taskGraph.whenReady {
    if (allTasks.any { it.project.path == ":app" && it.name in listOf("assembleRelease", "bundleRelease", "packageRelease") }) {
        check(releaseSigningReady) { "Release signing requires all four ANDROID_KEYSTORE_* / ANDROID_KEY_* environment variables." }
    }
}
dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.12.01"))
    implementation("androidx.activity:activity-compose:1.12.2")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
}
