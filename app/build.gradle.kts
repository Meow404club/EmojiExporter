import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
}

// 读取签名配置（keystore.properties，不入库）；缺失时 release 构建走 debug 签名兜底
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties()
if (keystorePropsFile.exists()) {
    keystoreProps.load(FileInputStream(keystorePropsFile))
}

android {
    namespace = "top.brokestar.emojiexporter"
    compileSdk = 36
    defaultConfig {
        applicationId = "top.brokestar.emojiexporter"
        minSdk = 24; targetSdk = 36; versionCode = 1; versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    signingConfigs {
        if (keystoreProps.containsKey("storeFile")) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
                enableV1Signing = true   // JAR 签名（兼容旧系统）
                enableV2Signing = true   // APK 签名方案 v2
                enableV3Signing = true   // APK 签名方案 v3（密钥轮转）
                enableV4Signing = true   // APK 签名方案 v4（增量安装）
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true         // 代码混淆/压缩
            isShrinkResources = true       // 资源压缩
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // 签名配置：有 release keystore 用之，否则退回 debug 签名（保证 release 永远能产出 APK）
            signingConfig = if (signingConfigs.findByName("release") != null) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_11; targetCompatibility = JavaVersion.VERSION_11 }
    kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11) } }
    buildFeatures { viewBinding = true }
    packaging {
        resources.excludes += setOf(
            "META-INF/AL2.0", "META-INF/LGPL2.1",
            "META-INF/DEPENDENCIES", "META-INF/LICENSE", "META-INF/LICENSE.txt",
            "META-INF/license.txt", "META-INF/NOTICE", "META-INF/NOTICE.txt",
            "META-INF/notice.txt", "META-INF/ASL2.0", "META-INF/*.kotlin_module"
        )
    }
}
dependencies {
    implementation(libs.androidx.core.ktx); implementation(libs.androidx.appcompat); implementation(libs.material)
    implementation(libs.okhttp); implementation(libs.libsu)
    implementation(libs.kotlinx.coroutines.android); implementation(libs.androidx.activity.ktx)
    // libxposed 102 API（compileOnly：由 LSPosed 框架在运行时提供；避开 minCompileSdk=37 检查）
    compileOnly(libs.libxposed.api)
    compileOnly(libs.libxposed.itfc)
    compileOnly(libs.libxposed.service)
    implementation(libs.yukihookapi.api)
    ksp(libs.yukihookapi.ksp.xposed)
    implementation(libs.kavaref.core)
    implementation(libs.kavaref.extension)
    implementation(libs.nanohttpd)
    implementation(libs.coil)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit); androidTestImplementation(libs.androidx.espresso.core)
}
