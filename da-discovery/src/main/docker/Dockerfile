# Ultra-lean container using Google Distroless
# Expected final size: ~120-180MB (minimal base + JRE + JAR only)

# Debian 13: the java21-debian12 image lags behind (JDK 21.0.10 and unpatched libexpat1 and
# liblcms2-2 on 2026-10-20), while java21-debian13 carries JDK 21.0.12 and the current packages (VULN-01).
# Pinned by digest (Q-587): this index carries libbrotli1 1.1.0-2+deb13u1 (CVE-2026-111497). Bump it with the tag's
# current digest: docker buildx imagetools inspect gcr.io/distroless/java21-debian13:nonroot
FROM gcr.io/distroless/java21-debian13:nonroot@sha256:ba6c75fda24af8a9aee3ec7633bd7369ee390c054d4e4292d52f17014cc9a11a

# Set working directory
WORKDIR /app

# Copy JAR file
COPY build/libs/da-discovery.jar app.jar

# Expose standard Spring Boot port
EXPOSE 8080

# Run application (distroless has no shell, so use exec form)
# Static busybox provides a healthcheck client (distroless has no shell/wget/curl).
COPY --from=busybox:1.36.1-musl /bin/busybox /busybox

# Run as the distroless "nonroot" user (uid and gid 65532), stated here rather than inherited from the
# base image so scanners and Kubernetes runAsNonRoot can verify it (Q-305). Numeric, because the
# kubelet cannot check a user name against runAsNonRoot.
USER 65532:65532

ENTRYPOINT ["java", \
    "-XX:+UseContainerSupport", \
    "-XX:MaxRAMPercentage=75.0", \
    "-XX:+UseG1GC", \
    "-XX:+UseStringDeduplication", \
    "-Djava.security.egd=file:/dev/./urandom", \
    "-Dspring.backgroundpreinitializer.ignore=true", \
    "-jar", "app.jar"]
