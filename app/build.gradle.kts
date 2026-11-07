plugins { id("com.android.application") }

android {
    namespace = "com.atrium.launcher"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.atrium.launcher"
        minSdk = 30
        targetSdk = 36
        versionCode = 1
        versionName = "0.2"
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.getByName("debug")
        }
    }
}
