plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
    namespace = "cn.pocketai.assistant"
    compileSdk = 36
    defaultConfig {
        applicationId = "cn.pocketai.assistant"
        minSdk = 30
        targetSdk = 35
        versionCode = 6
        versionName = "0.1.5"
        ndk { abiFilters += "arm64-v8a" }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    val signingStore = providers.environmentVariable("POCKET_SIGNING_STORE").orNull
    if (signingStore != null) {
        signingConfigs.create("publicRelease") {
            storeFile = file(signingStore)
            storePassword = providers.environmentVariable("POCKET_SIGNING_PASSWORD").get()
            keyAlias = "pocket"
            keyPassword = providers.environmentVariable("POCKET_SIGNING_PASSWORD").get()
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            if (signingStore != null) signingConfig = signingConfigs.getByName("publicRelease")
        }
    }
    packaging { resources.excludes += listOf("META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*") }
    lint { abortOnError = true }
    testOptions { unitTests.isReturnDefaultValues = true }
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
dependencies {
    implementation("androidx.core:core:1.16.0")
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.18.0")
    implementation("com.google.mlkit:text-recognition-chinese:16.0.1")
    testImplementation("junit:junit:4.13.2")
}
