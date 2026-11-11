#!/usr/bin/env bash
# Build and run the simulator on Linux/macOS.   ./run.sh [all|train|eval|faults|sensors|quick] [options]
# Requires JDK 21+ (set JAVA_HOME if java on PATH is older).
set -euo pipefail
cd "$(dirname "$0")"
JAVA_BIN="${JAVA_HOME:+$JAVA_HOME/bin/}"
CP="$(ls lib/*.jar | tr '\n' ':')"
mkdir -p build/classes
find src/main/java -name '*.java' > build/sources.txt
"${JAVA_BIN}javac" -encoding UTF-8 -d build/classes -cp "$CP" @build/sources.txt
"${JAVA_BIN}java" -Xmx2g -cp "build/classes:$CP" carbonfog.Main "$@"
