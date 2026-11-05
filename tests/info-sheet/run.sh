#!/bin/sh
# Compiles real InfoBottomSheet/MetadataExtractor/IconDatabase against android-17.jar and STUBS of
# launcher dependencies, then runs the behavioural test. Not a platform build.
set -e
SRC=${SRC:-/tmp/launcher-info-a17/src/com/android/launcher3/customization}
STUBS=${STUBS_DIR:-/tmp/stubs-info}
JAR=${ANDROID_JAR:-/home/kit/kat-tasks/font-hardening/android-17.jar}
OUT=$(mktemp -d)
javac -nowarn -d "$OUT" -cp "$JAR" $(find "$STUBS" -name '*.java') "$SRC/InfoBottomSheet.java" "$SRC/MetadataExtractor.java" "$SRC/IconDatabase.java" "$(dirname "$0")/InfoSheetLogicTest.java"
java -cp "$OUT:$JAR" com.android.launcher3.customization.InfoSheetLogicTest
