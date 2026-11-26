plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}
android {
    namespace = "com.castpro.zero.phone"
    compileSdk = libs.versions.compileSdk.get().toInt()
    defaultConfig { applicationId = "com.castpro.zero.phone"; minSdk = 34; targetSdk = libs.versions.targetSdk.get().toInt(); versionCode = 2; versionName = "1.1.0" }
    buildFeatures { compose = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
kotlin { jvmToolchain(17) }
dependencies {
    implementation(project(":core-protocol"))
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation("androidx.core:core:1.17.0")
    implementation("androidx.camera:camera-camera2:1.5.1")
    implementation("androidx.camera:camera-lifecycle:1.5.1")
    implementation("androidx.camera:camera-view:1.5.1")
    implementation("com.google.zxing:core:3.5.4")
    debugImplementation(libs.androidx.compose.ui.tooling)
    testImplementation(libs.junit)
}
