plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
}
android {
    namespace = "top.brokestar.emojiexporter"
    compileSdk = 36
    defaultConfig {
        applicationId = "top.brokestar.emojiexporter"
        minSdk = 24; targetSdk = 36; versionCode = 1; versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildTypes { release { isMinifyEnabled = false } }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_11; targetCompatibility = JavaVersion.VERSION_11 }
    kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11) } }
    buildFeatures { viewBinding = true }
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
