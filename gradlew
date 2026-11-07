#!/usr/bin/env sh
# GCAT Wallet — gradlew wrapper
# Uses system gradle if wrapper JAR is not present
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
if [ -f "$SCRIPT_DIR/gradle/wrapper/gradle-wrapper.jar" ]; then
  exec "${JAVA_HOME:+$JAVA_HOME/bin/}java" -Xmx64m -classpath "$SCRIPT_DIR/gradle/wrapper/gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain "$@"
else
  exec gradle "$@"
fi
