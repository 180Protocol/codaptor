FROM gitpod/workspace-full

# Corda OS 4.5 only supports Gradle 5.4.1 and JDK 8u171+
# See https://docs.corda.net/docs/corda-os/4.5/getting-set-up.html
ARG GRADLE_VERSION=5.4.1
ARG BUILD_JDK_VERSION=8.0.265-open
ARG TOOLS_JDK_VERSION=14.0.2-open

RUN bash -c ". /home/gitpod/.sdkman/bin/sdkman-init.sh \
  && sdk install java ${BUILD_JDK_VERSION} \
  && sdk install java ${TOOLS_JDK_VERSION} \
  && sdk install gradle ${GRADLE_VERSION}"
