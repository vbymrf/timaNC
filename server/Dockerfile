# syntax=docker/dockerfile:1

FROM golang:1.24.5-alpine AS build
WORKDIR /src/server

COPY server/go.mod server/go.sum ./
COPY gen/go/go.mod gen/go/go.sum /src/gen/go/
RUN go mod download

COPY server/cmd ./cmd
COPY server/internal ./internal
COPY gen/go/proto /src/gen/go/proto
RUN unformatted="$(gofmt -l cmd internal)"; test -z "$unformatted" || { echo "$unformatted"; exit 1; } \
    && go vet ./... \
    && go test ./... \
    && CGO_ENABLED=0 GOOS=linux go build -trimpath -ldflags="-s -w" -o /out/tima-server ./cmd/tima-server \
    && CGO_ENABLED=0 GOOS=linux go build -trimpath -ldflags="-s -w" -o /out/tima-worker ./cmd/tima-worker \
    && CGO_ENABLED=0 GOOS=linux go build -trimpath -ldflags="-s -w" -o /out/realtime-gw ./cmd/realtime-gw \
    && CGO_ENABLED=0 GOOS=linux go build -trimpath -ldflags="-s -w" -o /out/push-gateway ./cmd/push-gateway

FROM gcr.io/distroless/static-debian12:nonroot
COPY --from=build /out/tima-server /tima-server
COPY --from=build /out/tima-worker /tima-worker
COPY --from=build /out/realtime-gw /realtime-gw
COPY --from=build /out/push-gateway /push-gateway
EXPOSE 8080 8081 8082
USER nonroot:nonroot
ENTRYPOINT ["/tima-server"]
