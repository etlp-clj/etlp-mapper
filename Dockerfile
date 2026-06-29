# Base Java image for building the application
FROM clojure:lein AS build

# Set the working directory
WORKDIR /app

# Copy the project.clj and dependencies files
COPY project.clj /app/

# Download and cache dependencies
RUN lein deps

# Copy the source code and resources
COPY src /app/src
COPY resources /app/resources

# Build the JAR file
RUN lein uberjar

# Base Java image for running the application
FROM openjdk:11-jre-slim AS run

# Set the working directory
WORKDIR /app

# Copy the JAR file and resources from the build image
COPY --from=build /app/target/etlp-mapper-0.1.0-SNAPSHOT-standalone.jar /app/
COPY --from=build /app/resources /app/resources

# Data dir for the SQLite file. Mount an Azure Files volume here for
# persistence across container restarts; leave unmounted for an ephemeral DB.
RUN mkdir -p /data

# Set the default port
ENV PORT=3000

# DB selection is entirely via JDBC_URL:
#   Postgres (default): jdbc:postgresql://host:5432/db?user=...&password=...
#   SQLite (no PG cost): jdbc:sqlite:/data/etlp-mapper.db?journal_mode=WAL&busy_timeout=5000
# The app auto-detects the SQLite backend from the jdbc:sqlite prefix.
ENV JDBC_URL=${JDBC_URL}

# Expose the port
EXPOSE $PORT

# Run the application
CMD java -jar /app/etlp-mapper-0.1.0-SNAPSHOT-standalone.jar :duct/migrator && \
java -jar /app/etlp-mapper-0.1.0-SNAPSHOT-standalone.jar
