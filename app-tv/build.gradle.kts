plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}
android {
    namespace = "com.castpro.zero.tv"
    compileSdk = libs.versions.compileSdk.get().toInt()
    defaultConfig { applicationId = "com.castpro.zero.tv"; minSdk = 28; targetSdk = libs.versions.targetSdk.get().toInt(); versionCode = 3; versionName = "1.0.0" }
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
    implementation("com.google.zxing:core:3.5.4")
    debugImplementation(libs.androidx.compose.ui.tooling)
    testImplementation(libs.junit)
}
