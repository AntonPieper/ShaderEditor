#!/bin/sh
# Asks Mesa's offline compiler which lines of the cases of DirectiveCases it
# compiles, and records the verdicts in
# app/src/test/resources/directives/mesa.txt, which DirectiveRunOracleTest
# checks without Mesa. Needs glsl_compiler (brew install mesa), the Android
# SDK in ANDROID_HOME and a JDK 17. Prints the totals.
set -e
cd "$(dirname "$0")/../.."
./gradlew -q :app:compileDebugUnitTestJavaWithJavac
cd app
jar() {
	find "$HOME/.gradle/caches/modules-2" -name "$1" | head -1
}
sdk=${ANDROID_HOME:-$HOME/Library/Android/sdk}
build=build/intermediates
java -cp "$build/javac/debug/compileDebugJavaWithJavac/classes:\
$build/javac/debugUnitTest/compileDebugUnitTestJavaWithJavac/classes:\
src/test/resources:$(jar 'junit-4.13*.jar'):$(jar 'hamcrest-core-1.3.jar'):\
$(jar 'json-2018*.jar'):$(ls -d "$sdk"/platforms/android-*/android.jar | tail -1):\
$build/compile_and_runtime_r_class_jar/debug/processDebugResources/R.jar" \
	de.markusfisch.android.shadereditor.project.DirectiveOracle \
	src/test/resources/directives/mesa.txt
