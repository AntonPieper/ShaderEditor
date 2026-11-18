#!/bin/sh
# Run the analyser on every result file (Mac; needs the app classes from
# ./gradlew :app:compileDebugUnitTestJavaWithJavac and Mesa's glsl_compiler).
# APP=<checkout> takes the app classes from another checkout (default:
# this one); CORPUS and OUT override the corpus folder and the output.
set -e
cd "$(dirname "$0")/../.."
APP=${APP:-.}
CORPUS=${CORPUS:-typo-probe/corpus}
OUT=${OUT:-typo-probe/analysis/voices.jsonl}
B=$APP/app/build/intermediates
SDK=${ANDROID_HOME:-$HOME/Library/Android/sdk}
JARS=$(find ~/.gradle/caches/modules-2 -name 'json-20180813.jar' | head -1):$(find ~/.gradle/caches/modules-2 -name 'annotation-jvm-1.8.1.jar' | head -1)
CP=$JARS:$B/javac/debug/compileDebugJavaWithJavac/classes:$B/compile_and_runtime_r_class_jar/debug/processDebugResources/R.jar:$SDK/platforms/android-37.0/android.jar
CLASSES=$(mktemp -d)
javac -nowarn -d "$CLASSES" -cp "$CP" typo-probe/jvm/src/exact/*.java typo-probe/jvm/src/de/markusfisch/android/shadereditor/opengl/*.java
mkdir -p "$(dirname "$OUT")"
if [ -n "$GENERATE" ]; then
	java -cp "$CLASSES:$CP" de.markusfisch.android.shadereditor.opengl.TypoGenerate \
		"$APP/app/src/debug/assets/examples" "$APP/app/src/test/resources/shadertoy" "$CORPUS"
fi
java -cp "$CLASSES:$CP" de.markusfisch.android.shadereditor.opengl.TypoAnalyse "$CORPUS" "$OUT" typo-probe/results/*.jsonl
rm -rf "$CLASSES"
