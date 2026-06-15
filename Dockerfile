# syntax=docker/dockerfile:1.7

FROM eclipse-temurin:25-jdk AS build

ARG GITHUB_USER=grounds-bot

WORKDIR /workspace

COPY . .

RUN --mount=type=secret,id=github_token \
    mkdir -p ~/.gradle && \
    printf "github.user=%s\ngithub.token=%s\n" "$GITHUB_USER" "$(cat /run/secrets/github_token)" > ~/.gradle/gradle.properties && \
    ./gradlew :examples:minigame-agones:installDist --no-daemon

FROM eclipse-temurin:25-jre

WORKDIR /app

COPY --from=build /workspace/examples/minigame-agones/build/install/minigame-agones/ /app/

EXPOSE 25565

ENTRYPOINT ["/app/bin/minigame-agones"]
