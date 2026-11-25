#!/bin/sh
# Asks Mesa's offline compiler whether the syntax tree is right about what
# the names in the cases of MeaningOracle mean, and records the verdicts in
# app/src/test/resources/tree/mesa.txt, which MeaningsOracleTest checks
# without Mesa. Needs glsl_compiler (brew install mesa), the Android SDK in
# ANDROID_HOME and a JDK 17. Prints the totals.
#
# Further arguments are files or folders of shaders to judge without
# recording them; DEFINE=NAME defines NAME in each (libretro: FRAGMENT).
set -e
cd "$(dirname "$0")/../.."
root=$(pwd)
./gradlew -q :app:compileDebugUnitTestJavaWithJavac
cd app
jar() {
	find "$HOME/.gradle/caches/modules-2" -name "$1" | head -1
}
sdk=${ANDROID_HOME:-$HOME/Library/Android/sdk}
build=build/intermediates
java ${DEFINE:+-Ddefine=$DEFINE} -cp "$build/javac/debug/compileDebugJavaWithJavac/classes:\
$build/javac/debugUnitTest/compileDebugUnitTestJavaWithJavac/classes:\
src/test/resources:$(jar 'junit-4.13*.jar'):$(jar 'hamcrest-core-1.3.jar'):\
$(jar 'json-2018*.jar'):$(ls -d "$sdk"/platforms/android-*/android.jar | tail -1):\
$build/compile_and_runtime_r_class_jar/debug/processDebugResources/R.jar" \
	de.markusfisch.android.shadereditor.tree.MeaningOracle \
	src/test/resources/tree/mesa.txt "$@"
