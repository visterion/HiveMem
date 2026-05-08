FROM node:26-alpine AS ui-build

WORKDIR /ui
COPY knowledge-ui/package.json knowledge-ui/package-lock.json ./
RUN npm ci
COPY knowledge-ui/ ./
RUN npm run build

FROM maven:3-eclipse-temurin-26 AS build

WORKDIR /workspace
COPY java-server/pom.xml java-server/mvnw java-server/mvnw.cmd ./
COPY java-server/.mvn .mvn
COPY java-server/src src
COPY --from=ui-build /ui/dist src/main/resources/static

RUN chmod +x mvnw && ./mvnw -q -DskipTests package

FROM eclipse-temurin:25-jre

RUN apt-get update && apt-get install -y --no-install-recommends curl gnupg lsb-release \
    && echo "deb http://apt.postgresql.org/pub/repos/apt $(lsb_release -cs)-pgdg main" > /etc/apt/sources.list.d/pgdg.list \
    && curl -fsSL https://www.postgresql.org/media/keys/ACCC4CF8.asc | gpg --dearmor -o /etc/apt/trusted.gpg.d/pgdg.gpg \
    && apt-get update && apt-get install -y --no-install-recommends \
        postgresql-client-17 \
        tesseract-ocr tesseract-ocr-deu tesseract-ocr-eng \
    && apt-get purge -y gnupg lsb-release && apt-get autoremove -y && rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY --from=build /workspace/target/app.jar /app/app.jar
COPY entrypoint.sh /app/entrypoint.sh
COPY scripts/hivemem-migrate /usr/local/bin/hivemem-migrate
COPY scripts/hivemem-backup /usr/local/bin/hivemem-backup
COPY scripts/hivemem-token /usr/local/bin/hivemem-token

RUN chmod +x /app/entrypoint.sh /usr/local/bin/hivemem-migrate /usr/local/bin/hivemem-backup /usr/local/bin/hivemem-token

EXPOSE 8421
ENTRYPOINT ["/app/entrypoint.sh"]
