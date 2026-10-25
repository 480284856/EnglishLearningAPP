#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
check_dir=$(mktemp -d)
trap 'rm -rf "$check_dir"' EXIT
sources=(app/src/main/java/cn/wordflow/app/CsvParser.java app/src/test/java/cn/wordflow/app/CsvParserChecks.java)
if command -v javac >/dev/null 2>&1; then
  javac -source 17 -target 17 -d "$check_dir" "${sources[@]}"
else
  java -m jdk.compiler/com.sun.tools.javac.Main -source 17 -target 17 -d "$check_dir" "${sources[@]}"
fi
java -cp "$check_dir" cn.wordflow.app.CsvParserChecks examples/daily-english.csv
