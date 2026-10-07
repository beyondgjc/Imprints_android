import java.io.StringReader
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

// 高德 Key 只放 local.properties（.gitignore），经 Manifest 占位符注入，不进源码仓库。
val amapAndroidKey: String = run {
    val file = rootProject.file("local.properties")
    if (!file.exists()) "" else file.readText().let { text ->
        val props = Properties()
        props.load(StringReader(text))
        props.getProperty("AMAP_ANDROID_KEY", "")
    }
}

// release 签名：根目录 keystore.properties（gitignore），不进仓库
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val hasReleaseSigning = keystoreProps.getProperty("storeFile")?.isNotBlank() == true

android {
    namespace = "com.beyondguo.imprints"
    compileSdk {
        version = release(37) {
            minorApiLevel = 2
        }
    }

    defaultConfig {
        applicationId = "com.beyondguo.imprints"
        minSdk = 29
        targetSdk = 36
        versionCode = 4
        versionName = "1.3"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // 占位符供 AndroidManifest 的 com.amap.api.v2.apikey 使用
        manifestPlaceholders["amapApiKey"] = amapAndroidKey
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // R8：收缩 + 优化 + 混淆（开源仓；mapping 与 APK 一并归档）
            optimization {
                enable = true
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
    // 上架前自检：release 构建必须过 lint（缺权限说明/硬编码文案等问题会直接报）
    lint {
        abortOnError = true
        checkReleaseBuilds = true
        warningsAsErrors = false
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation("androidx.compose.material:material-icons-core:1.7.4")
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.biometric)
    // 显式升级 fragment ≥1.3.0：biometric 1.1.0 会把 fragment 钉在 1.2.5，
    // 其 FragmentActivity 对 requestCode 强校验「仅低 16 位」，与 Activity Result API
    // registry 的高位随机 requestCode 冲突，选图/音频/权限 launch 必现 crash。
    // 与 Penly 一致钉 fragment-ktx 1.9.0（1.3.0+ 已移除该校验）。
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.amap.combo)
    implementation("com.beyondguo.penly:crypto-core:0.1.0")
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
