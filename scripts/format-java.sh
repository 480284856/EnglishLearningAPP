#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
formatter_version=1.24.0
formatter_dir=${WORDFLOW_TOOLS_DIR:-.tools}
mkdir -p "$formatter_dir"
formatter_jar="$formatter_dir/google-java-format-$formatter_version-all-deps.jar"
if [ ! -f "$formatter_jar" ]; then
  curl -fL --retry 2 "https://repo.maven.apache.org/maven2/com/google/googlejavaformat/google-java-format/$formatter_version/google-java-format-$formatter_version-all-deps.jar" -o "$formatter_jar"
fi
printf '%s  %s\n' 812f805f58112460edf01bf202a8e61d0fd1f35c0d4fabd54220640776ec57a1 "$formatter_jar" | sha256sum -c -
mapfile -t java_sources < <(find app/src -name '*.java' -type f | sort)
java -jar "$formatter_jar" --replace "${java_sources[@]}"
