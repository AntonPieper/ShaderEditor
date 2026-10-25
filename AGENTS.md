# Working in this repository

Follow [CONTRIBUTING.md](CONTRIBUTING.md) and `.editorconfig` for code style.

Android commands, from the repository root:

- `./gradlew assembleDebug` builds the debug APK at
  `app/build/outputs/apk/debug/app-debug.apk`.
- `./gradlew lintDebug` runs Android lint.
- `make install start` installs and launches the debug app on an attached device
  or emulator. Plain `make` also installs and launches; it requires a device.

The build was validated with a full JDK 21, including `javac`. Java source
compatibility is a separate setting in `app/build.gradle.kts`. Use the Gradle
wrapper; tool and dependency versions live in `gradle/wrapper/gradle-wrapper.properties`
and `gradle/libs.versions.toml`, and SDK requirements in `app/build.gradle.kts`.
Debug builds do not require release signing credentials.

There are currently no unit or instrumentation test suites. A successful build
and lint run do not validate shader rendering, sensors, or wallpaper behavior;
those require running the app on a device or emulator.

For documentation changes, run `npm ci` and `npm run build` in `docs-site/`.
`npm run dev` serves the site under `/ShaderEditor/`, as configured in
`docs-site/astro.config.mjs`.
