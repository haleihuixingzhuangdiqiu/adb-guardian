plugins { id("com.android.application") }

android {
    namespace = "dev.local.adbguardian"
    compileSdk = 36
    defaultConfig {
        applicationId = "dev.local.adbguardian"
        minSdk = 30
        targetSdk = 36
        versionCode = 3
        versionName = "0.2.1"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    lint { abortOnError = true }
}
dependencies { testImplementation("junit:junit:4.13.2") }
