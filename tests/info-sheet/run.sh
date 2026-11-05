#!/bin/sh
# Compiles the real InfoBottomSheet/MetadataExtractor/IconDatabase against an Android jar and
# STUBS of the launcher dependencies, then runs the behavioural test. Not a platform build.
#
# Required: ANDROID_JAR (path to android.jar) and stubs, either STUBS_DIR or a "stubs" folder
# next to this script. Optional: SRC (customization source dir; defaults to this repo's).
set -eu
DIR=$(cd "$(dirname "$0")" && pwd)
SRC=${SRC:-$DIR/../../src/com/android/launcher3/customization}
STUBS=${STUBS_DIR:-$DIR/stubs}
[ -n "${ANDROID_JAR:-}" ] && [ -f "$ANDROID_JAR" ] || { echo "error: set ANDROID_JAR to an android.jar path" >&2; exit 2; }
[ -d "$STUBS" ] || { echo "error: set STUBS_DIR (launcher stub sources) or place them in $DIR/stubs" >&2; exit 2; }
[ -f "$SRC/InfoBottomSheet.java" ] || { echo "error: sources not found in $SRC, set SRC" >&2; exit 2; }
OUT=$(mktemp -d)
trap 'rm -rf "$OUT"' EXIT
javac -nowarn -d "$OUT" -cp "$ANDROID_JAR" $(find "$STUBS" -name '*.java') "$SRC/InfoBottomSheet.java" "$SRC/MetadataExtractor.java" "$SRC/IconDatabase.java" "$DIR/InfoSheetLogicTest.java"
java -cp "$OUT:$ANDROID_JAR" com.android.launcher3.customization.InfoSheetLogicTest
