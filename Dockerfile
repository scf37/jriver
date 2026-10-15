FROM maven:3-eclipse-temurin-25 AS build
# jpaq is not on Maven Central: build and install it first
RUN git clone --depth 1 https://github.com/scf37/jpaq /jpaq && cd /jpaq && mvn -q install -DskipTests
COPY pom.xml /river/
COPY src /river/src
COPY drivers /river/drivers
RUN cd /river && mvn -q -DskipTests package

FROM eclipse-temurin:25-jre
RUN apt-get update && apt-get install -y --no-install-recommends openssh-client && rm -rf /var/lib/apt/lists/*
COPY --from=build /river/target/river.jar /opt/river/river.jar
COPY river /opt/river/river
COPY drivers /opt/river/drivers
ENTRYPOINT ["/opt/river/river"]
