#!/bin/sh
# Times the syntax tree's reading of the given shader files and its
# re-reading after one key, on this machine's JVM (TreeTimes). Needs the
# Android SDK in ANDROID_HOME and a JDK 17.
set -e
cd "$(dirname "$0")/../.."
./gradlew -q :app:compileDebugUnitTestJavaWithJavac
build=app/build/intermediates
java -cp "$build/javac/debug/compileDebugJavaWithJavac/classes:\
$build/javac/debugUnitTest/compileDebugUnitTestJavaWithJavac/classes" \
	de.markusfisch.android.shadereditor.tree.TreeTimes "$@"
