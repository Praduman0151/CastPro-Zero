# Build and CI

Milestone 1A is pinned to Gradle 8.13, Android Gradle Plugin 8.13.2, Kotlin 2.2.21, JDK 17, compile/target SDK 36, and Compose BOM 2025.11.00.

The repository includes the canonical Gradle Wrapper scripts, wrapper JAR, and distribution properties. Run the same validation locally with:

```sh
./gradlew :core-protocol:test :app-phone:testDebugUnitTest :app-tv:testDebugUnitTest :app-phone:assembleDebug :app-tv:assembleDebug
```

The GitHub Actions workflow runs these tasks on `ubuntu-latest` and publishes `app-phone-debug.apk` and `app-tv-debug.apk` as separate workflow artifacts.
