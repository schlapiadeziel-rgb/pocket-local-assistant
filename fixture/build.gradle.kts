plugins { id("com.android.application") }
android {
    namespace = "cn.pocketai.fixture"
    compileSdk = 36
    defaultConfig {
        applicationId = "cn.pocketai.fixture"
        minSdk = 30
        targetSdk = 35
        versionCode = 1
        versionName = "0.1-test"
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
