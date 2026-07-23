FROM postgres:16.14-alpine3.24 AS verify
COPY verify_*.sql /verify/

FROM migrate/migrate:v4.18.3 AS migrate

COPY *.up.sql /migrations/

ENTRYPOINT ["migrate"]
