#!/bin/sh
# Run the analyser on every result file (Mac; needs the app classes from
# ./gradlew :app:compileDebugUnitTestJavaWithJavac and Mesa's glsl_compiler).
set -e
cd "$(dirname "$0")/../.."
B=app/build/intermediates
SDK=${ANDROID_HOME:-$HOME/Library/Android/sdk}
JARS=$(find ~/.gradle/caches/modules-2 -name 'json-20180813.jar' | head -1):$(find ~/.gradle/caches/modules-2 -name 'annotation-jvm-1.8.1.jar' | head -1)
CP=$JARS:$B/javac/debug/compileDebugJavaWithJavac/classes:$B/compile_and_runtime_r_class_jar/debug/processDebugResources/R.jar:$SDK/platforms/android-37.0/android.jar
OUT=$(mktemp -d)
javac -nowarn -d "$OUT" -cp "$CP" typo-probe/jvm/src/exact/*.java typo-probe/jvm/src/de/markusfisch/android/shadereditor/opengl/*.java
mkdir -p typo-probe/analysis
java -cp "$OUT:$CP" de.markusfisch.android.shadereditor.opengl.TypoAnalyse typo-probe/corpus typo-probe/analysis/voices.jsonl typo-probe/results/*.jsonl
rm -rf "$OUT"
