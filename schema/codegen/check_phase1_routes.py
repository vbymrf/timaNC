#!/usr/bin/env python3
"""Fail when a Phase 1 OpenAPI operation has no registered Go HTTP route."""

from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[2]
SPEC = ROOT / "schema" / "openapi" / "client-api.yaml"
HANDLER = ROOT / "server" / "internal" / "httpapi" / "phase1.go"

path_re = re.compile(r"^  (/[^:]+):$")
method_re = re.compile(r"^    (get|post|put|patch|delete):$")
route_re = re.compile(r'mux\.Handle(?:Func)?\("([A-Z]+) (/v1/[^"]+)"')

required: set[tuple[str, str]] = set()
path: str | None = None
method: str | None = None
for line in SPEC.read_text(encoding="utf-8").splitlines():
    if match := path_re.match(line):
        path, method = match.group(1), None
        continue
    if match := method_re.match(line):
        method = match.group(1).upper()
        continue
    if (
        path is not None
        and method is not None
        and line.strip() == "x-tima-implementation-phase: phase-1"
    ):
        required.add((method, "/v1" + path))

registered = {
    (match.group(1), match.group(2))
    for match in route_re.finditer(HANDLER.read_text(encoding="utf-8"))
}
missing = sorted(required - registered)
if missing:
    for operation in missing:
        print(f"missing Phase 1 route: {operation[0]} {operation[1]}", file=sys.stderr)
    raise SystemExit(1)

print(f"Phase 1 route coverage passed: {len(required)} operations")
