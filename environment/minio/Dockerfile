FROM golang:1.24.2-alpine3.21 AS build
WORKDIR /src
ADD https://github.com/minio/minio/archive/0d7408fc9969caf07de6a8c3a84f9fbb10a6739e.tar.gz /tmp/minio.tar.gz
RUN tar -xzf /tmp/minio.tar.gz --strip-components=1 -C /src \
    && CGO_ENABLED=0 GOMAXPROCS=4 go build -p 4 -trimpath -buildvcs=false \
    -ldflags="-s -w -X github.com/minio/minio/cmd.Version=2025-04-22T22:12:26Z -X github.com/minio/minio/cmd.ReleaseTag=RELEASE.2025-04-22T22-12-26Z -X github.com/minio/minio/cmd.CommitID=0d7408fc9969caf07de6a8c3a84f9fbb10a6739e" \
    -o /out/minio .

FROM alpine:3.21
RUN apk add --no-cache ca-certificates curl \
    && mkdir /data && chown 65532:65532 /data
COPY --from=build /out/minio /usr/local/bin/minio
COPY --from=build /src/LICENSE /licenses/minio-LICENSE
USER 65532:65532
EXPOSE 9000 9001
ENTRYPOINT ["minio"]
