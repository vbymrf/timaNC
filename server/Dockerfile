# syntax=docker/dockerfile:1

FROM golang:1.24.5-alpine AS build
WORKDIR /src

COPY go.mod go.sum ./
RUN go mod download

COPY cmd ./cmd
COPY internal ./internal
RUN CGO_ENABLED=0 GOOS=linux go build -trimpath -ldflags="-s -w" -o /out/tima-server ./cmd/tima-server

FROM gcr.io/distroless/static-debian12:nonroot
COPY --from=build /out/tima-server /tima-server
EXPOSE 8080
USER nonroot:nonroot
ENTRYPOINT ["/tima-server"]
