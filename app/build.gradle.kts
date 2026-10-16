import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

// Client ID resolution order: local.properties -> gradle.properties -> empty (set it in-app).
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun prop(name: String): String = (localProps.getProperty(name) ?: project.findProperty(name) as String? ?: "").trim()
val oneDriveClientId = prop("NOTESPLUS_CLIENT_ID")
val googleClientId = prop("NOTESPLUS_GOOGLE_CLIENT_ID")

// CI supplies the build number and a release keystore through the environment.
val ciVersionCode = System.getenv("NOTESPLUS_VERSION_CODE")?.toIntOrNull()
val ciVersionName = System.getenv("NOTESPLUS_VERSION_NAME")
val releaseKeystorePath = System.getenv("NOTESPLUS_KEYSTORE_PATH")

android {
    namespace = "com.notesplus.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.notesplus.app"
        minSdk = 26
        targetSdk = 35
        versionCode = ciVersionCode ?: 1
        versionName = ciVersionName ?: "1.0.0-dev"
        buildConfigField("String", "ONEDRIVE_CLIENT_ID", "\"$oneDriveClientId\"")
        buildConfigField("String", "ONEDRIVE_REDIRECT_URI", "\"notesplus://auth\"")
        buildConfigField("String", "GOOGLE_CLIENT_ID", "\"$googleClientId\"")
        // Google installed-app clients accept a custom scheme in reverse-DNS form of the package.
        buildConfigField("String", "GOOGLE_REDIRECT_URI", "\"com.notesplus.app:/oauth2redirect\"")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (releaseKeystorePath != null) {
            create("release") {
                storeFile = file(releaseKeystorePath)
                storePassword = System.getenv("NOTESPLUS_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("NOTESPLUS_KEY_ALIAS")
                keyPassword = System.getenv("NOTESPLUS_KEY_PASSWORD")
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
        debug {
            applicationIdSuffix = ".debug"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf("-opt-in=kotlin.RequiresOptIn")
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources {
            excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "META-INF/DEPENDENCIES")
        }
    }
    lint {
        lintConfig = file("lint.xml")
        abortOnError = true
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.incremental", "true")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.browser)
    implementation(libs.androidx.documentfile)
    implementation(libs.androidx.input.motionprediction)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.pdfbox.android)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
