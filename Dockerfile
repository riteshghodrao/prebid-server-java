FROM amazoncorretto:21.0.8-al2023

WORKDIR /app/prebid-server

VOLUME /app/prebid-server/conf
VOLUME /app/prebid-server/data

COPY src/main/docker/run.sh ./
RUN chmod +x /app/prebid-server/run.sh
COPY src/main/docker/application.yaml ./
COPY target/prebid-server.jar ./

# Copy production config and stored data into the image
COPY production/ ./production/

EXPOSE 8080
EXPOSE 8060

ENTRYPOINT [ "/app/prebid-server/run.sh" ]
