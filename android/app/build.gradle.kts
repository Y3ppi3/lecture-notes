plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

/** Значение из -Pимя=..., переменной окружения или значение по умолчанию. */
fun config(name: String, env: String, default: String? = null): String? =
    (project.findProperty(name) as String?) ?: System.getenv(env) ?: default

val releaseApiUrl = config("lectureApiUrl", "LECTURE_API_URL", "https://api.example.com/")!!
val keystorePath = config("lectureKeystore", "LECTURE_KEYSTORE")

android {
    namespace = "ru.lecturenotes.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "ru.lecturenotes.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 2
        versionName = "1.1.0"
    }

    signingConfigs {
        if (keystorePath != null) {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = config("lectureKeystorePassword", "LECTURE_KEYSTORE_PASSWORD")
                keyAlias = config("lectureKeyAlias", "LECTURE_KEY_ALIAS")
                keyPassword = config("lectureKeyPassword", "LECTURE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            // 10.0.2.2 — localhost компьютера из Android-эмулятора.
            buildConfigField("String", "API_BASE_URL", "\"http://10.0.2.2:8000/\"")
            buildConfigField("boolean", "ALLOW_SERVER_OVERRIDE", "true")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            require(releaseApiUrl.startsWith("https://")) { "lectureApiUrl должен начинаться с https://" }
            buildConfigField("String", "API_BASE_URL", "\"$releaseApiUrl\"")
            buildConfigField("boolean", "ALLOW_SERVER_OVERRIDE", "false")
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
        // Сборка для показа: как release (R8, только HTTPS), но подписана debug-ключом,
        // чтобы APK можно было установить без настройки ключа подписи.
        create("demo") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            versionNameSuffix = "-demo"
            matchingFallbacks += listOf("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "DebugProbesKt.bin")
    }
    lint {
        abortOnError = true
        warningsAsErrors = false
        checkReleaseBuilds = true
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.icons.extended)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.core.ktx)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.process)
    implementation(libs.navigation.compose)
    implementation(libs.datastore.preferences)
    implementation(libs.exifinterface)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.work.runtime)

    implementation(libs.retrofit)
    implementation(libs.retrofit.serialization)
    implementation(libs.okhttp)
    debugImplementation(libs.okhttp.logging)
    implementation(libs.serialization.json)
    implementation(libs.coroutines.android)

    implementation(libs.coil.compose)
    implementation(libs.coil.video)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)
}
