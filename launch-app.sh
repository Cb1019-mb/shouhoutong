#!/usr/bin/env bash
# 前台启动应用:在仓库根目录运行;JAVA_HOME 未设时要求 PATH 中已有 java 17
cd "$(dirname "$0")" || exit 1
if [ -n "$JAVA_HOME" ]; then
  export PATH="$JAVA_HOME/bin:$PATH"
fi
pkill -f shouhoutong-0.1.0-SNAPSHOT.jar 2>/dev/null
sleep 1
exec java -jar target/shouhoutong-0.1.0-SNAPSHOT.jar
