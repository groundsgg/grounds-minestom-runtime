# syntax=docker/dockerfile:1.7

FROM eclipse-temurin:25-jdk AS build

ARG GITHUB_USER=grounds-bot

WORKDIR /workspace

COPY . .

RUN --mount=type=secret,id=github_token \
    mkdir -p ~/.gradle && \
    printf "github.user=%s\ngithub.token=%s\n" "$GITHUB_USER" "$(cat /run/secrets/github_token)" > ~/.gradle/gradle.properties && \
    ./gradlew :examples:duel:installDist --no-daemon

FROM eclipse-temurin:25-jre

WORKDIR /app

COPY --from=build /workspace/examples/duel/build/install/duel/ /app/

# 25565: Minecraft, where the proxy routes players after allocation.
# 9090:  the MatchHost gRPC server, where service-match pushes StartMatch.
EXPOSE 25565 9090

ENTRYPOINT ["/app/bin/duel"]
