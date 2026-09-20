# Lets anyone reproduce the sbt 2.x FileSystemAlreadyExistsException race
# (see README.md, https://github.com/sbt/sbt/issues/9625) without installing
# sbt or a matching JDK locally.
#
# Build:
#   docker build -t sbt2-junit5-fsrace-repro .
#
# Run (repeat a few times -- see README.md, it's not 100% every run):
#   docker run --rm sbt2-junit5-fsrace-repro

FROM docker.io/library/eclipse-temurin:25-jdk-noble

# Matches project/build.properties -- the sbt *runner*/launcher version isn't
# what matters for reproducing the bug (that's project/build.properties'
# sbt.version, which the runner reads and bootstraps automatically), but
# pinning here keeps the image deterministic.
ARG SBT_VERSION=2.0.6

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl ca-certificates \
    && curl -fsSL "https://github.com/sbt/sbt/releases/download/v${SBT_VERSION}/sbt-${SBT_VERSION}.tgz" -o /tmp/sbt.tgz \
    && tar -xzf /tmp/sbt.tgz -C /opt \
    && rm /tmp/sbt.tgz \
    && rm -rf /var/lib/apt/lists/*

ENV PATH="/opt/sbt/bin:${PATH}"

WORKDIR /repro
COPY . .

# Pre-fetch dependencies and do one full build at image-build time, so
# `docker run` is fast and works offline. This does NOT prevent the race from
# reproducing at `docker run` time: it lives in a JVM-process-static
# bookkeeping map inside JUnit's CloseablePath (see README.md, "Why this
# happens"), which starts empty on every fresh sbt/JVM process -- i.e. on
# every `docker run` -- regardless of whether the underlying jar files
# already exist on disk from this build step.
# `|| true`: this step reproducing (or not) the bug is not a build failure.
RUN sbt "reload; cleanFull; testFull" || true

CMD ["sbt", "reload; cleanFull; testFull"]
