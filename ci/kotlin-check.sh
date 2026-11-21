#!/usr/bin/env bash
# VM-side Kotlin compile check for yt-client-android (Yosuke, 2026-11-21).
#
# Run this on the Muse VM BEFORE pushing, so compile errors are caught
# locally in ~30 s instead of after a full CI round trip. This replaces
# routine use of the full emulator scope: [test: all] is reserved for
# minor-version releases (0.2.0 class); ordinary changes use player /
# feed / none as appropriate, gated by this check.
#
# How it works: kotlinc (2.0.20, same as the build) compiles every app
# Kotlin source against android.jar (API 35) plus the real dependency
# jars (appcompat / recyclerview / activity / lifecycle / chaquopy_java,
# extracted from their AARs) and a stub BuildConfig with the two fields
# the app reads. No Gradle, no APK — type/syntax/reference errors only,
# which is exactly the class of failure this gate exists for.
#
# Dependency jars live OUTSIDE the repo at
#   ~/workspace/toolchains/yt-client-kcheck/jars/
# (override with KCHECK_LIBS). If they are missing, the script prints the
# download recipe and exits 2.
set -u

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TC="$HOME/workspace/toolchains"
export JAVA_HOME="${JAVA_HOME:-$TC/jdk-21}"
export PATH="$JAVA_HOME/bin:$PATH"
KOTLINC="$TC/kotlinc/bin/kotlinc"
ANDROID_JAR="$TC/android-sdk/platforms/android-35/android.jar"
LIBS="${KCHECK_LIBS:-$TC/yt-client-kcheck/jars}"

if [ ! -d "$LIBS" ] || [ -z "$(ls "$LIBS"/*.jar 2>/dev/null)" ]; then
    echo "kotlin-check: dependency jars not found in $LIBS" >&2
    echo "Fetch the AARs from dl.google.com/dl/android/maven2 (appcompat 1.7.0," >&2
    echo "recyclerview 1.3.2, activity 1.9.3, core 1.13.1, fragment 1.8.3," >&2
    echo "lifecycle-runtime-android / -viewmodel-android / -common-jvm 2.8.4," >&2
    echo "savedstate 1.2.1, annotation-jvm 1.8.2, collection-jvm 1.4.2," >&2
    echo "arch core-common 2.2.0), extract each classes.jar, and add" >&2
    echo "chaquopy_java-16.0.0.jar from repo1.maven.org/com/chaquo/python/runtime/." >&2
    exit 2
fi

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$WORK/stub/app/ytclient"
cat > "$WORK/stub/app/ytclient/BuildConfig.java" <<'EOF'
package app.ytclient;
public final class BuildConfig {
    public static final String GEMINI_API_KEY = "";
    public static final String GEMINI_MODEL = "gemini-3.5-flash-lite";
}
EOF

CP="$(ls "$LIBS"/*.jar | tr '\n' ':')$ANDROID_JAR"
START=$(date +%s)
"$KOTLINC" -classpath "$CP" -d "$WORK/out" \
    "$ROOT"/app/src/main/java/app/ytclient/*.kt \
    "$WORK/stub/app/ytclient/BuildConfig.java" > "$WORK/log.txt" 2>&1
RC=$?
ELAPSED=$(( $(date +%s) - START ))
if [ $RC -eq 0 ]; then
    echo "kotlin-check: PASS (${ELAPSED}s)"
else
    echo "kotlin-check: FAIL (${ELAPSED}s) — first errors:"
    grep "error:" "$WORK/log.txt" | head -20
fi
exit $RC
