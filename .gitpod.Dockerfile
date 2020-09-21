FROM gitpod/workspace-full

# Corda OS 4.5 only supports Gradle 5.4.1 and JDK 8u171+
# See https://docs.corda.net/docs/corda-os/4.5/getting-set-up.html
ARG GRADLE_VERSION=5.4.1
ARG JDK_VERSION=8.0.265-open

RUN bash -c ". /home/gitpod/.sdkman/bin/sdkman-init.sh \
  && sdk install java ${JDK_VERSION} \
  && sdk install gradle ${GRADLE_VERSION}"
