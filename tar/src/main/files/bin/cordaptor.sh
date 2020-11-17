#!/bin/sh

home_dir() {
  local dir=$(dirname "$0")
  local full_dir=$(cd "${dir}/.." && pwd)
  echo ${full_dir}
}

export JAVA_APP_DIR="$(home_dir)"
export JAVA_CLASSPATH="${JAVA_APP_DIR}/lib/*:${JAVA_APP_DIR}/ext/*:${JAVA_APP_DIR}/cordapps/*"
export JAVA_MAJOR_VERSION=8
export JAVA_MAIN_CLASS=tech.b180.cordaptor.kernel.ContainerKt
export JAVA_OPTIONS="-Dconfig.file=${JAVA_APP_DIR}/conf/cordaptor.conf -Dlog4j.configurationFile=${JAVA_APP_DIR}/conf/log4j2.properties"

$JAVA_APP_DIR/bin/run-java.sh
