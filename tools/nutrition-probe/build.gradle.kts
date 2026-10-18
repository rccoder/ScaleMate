plugins { id("com.android.application") version "8.13.2" }
android {
    namespace = "app.scalemate.nutritionprobe"
    compileSdk = 36
    buildToolsVersion = "36.0.0"
    defaultConfig { applicationId = "app.scalemate.nutritionprobe"; minSdk = 34; targetSdk = 36; versionCode = 1; versionName = "1.0" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
