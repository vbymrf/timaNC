#!/usr/bin/env python3
"""Fail-closed validation of credential names used by release environments."""

from __future__ import annotations

import argparse
import base64
import binascii
import json
import os
import sys
from urllib.parse import urlparse


PROFILES = {
    "android": (
        "TIMA_ANDROID_BASE_URL",
        "PLAY_INTEGRITY_CLOUD_PROJECT_NUMBER",
        "FIREBASE_ANDROID_GOOGLE_SERVICES_JSON_BASE64",
        "ANDROID_SIGNING_KEYSTORE_BASE64",
        "ANDROID_SIGNING_KEY_ALIAS",
        "ANDROID_SIGNING_KEY_PASSWORD",
        "ANDROID_SIGNING_STORE_PASSWORD",
    ),
    "ios": (
        "TIMA_IOS_BASE_URL",
        "APPLE_TEAM_ID",
        "APPLE_BUNDLE_ID",
        "APPLE_IOS_SIGNING_IDENTITY",
        "APPLE_IOS_CERTIFICATE_P12_BASE64",
        "APPLE_IOS_CERTIFICATE_PASSWORD",
        "APPLE_IOS_PROVISIONING_PROFILE_BASE64",
    ),
    "windows": (
        "TIMA_WINDOWS_BASE_URL",
        "WINDOWS_SIGNING_CERTIFICATE_PFX_BASE64",
        "WINDOWS_SIGNING_CERTIFICATE_PASSWORD",
        "WINDOWS_SIGNING_CERTIFICATE_SHA256",
        "WINDOWS_SIGNING_TIMESTAMP_URL",
    ),
    "server": (
        "ATTESTATION_GATEWAY_URL",
        "ATTESTATION_GATEWAY_TOKEN",
        "PUSH_GATEWAY_URL",
        "PUSH_GATEWAY_TOKEN",
        "PUSH_TOKEN_ENCRYPTION_KEY",
        "PLAY_INTEGRITY_CLOUD_PROJECT_NUMBER",
        "FIREBASE_SERVICE_ACCOUNT_JSON_BASE64",
        "APPLE_APP_ATTEST_TEAM_ID",
        "APPLE_APP_ATTEST_KEY_ID",
        "APPLE_APP_ATTEST_PRIVATE_KEY_P8_BASE64",
        "APPLE_APNS_TEAM_ID",
        "APPLE_APNS_KEY_ID",
        "APPLE_APNS_PRIVATE_KEY_P8_BASE64",
    ),
}

BASE64_VALUES = {
    "FIREBASE_ANDROID_GOOGLE_SERVICES_JSON_BASE64",
    "FIREBASE_SERVICE_ACCOUNT_JSON_BASE64",
    "ANDROID_SIGNING_KEYSTORE_BASE64",
    "APPLE_IOS_CERTIFICATE_P12_BASE64",
    "APPLE_IOS_PROVISIONING_PROFILE_BASE64",
    "APPLE_APP_ATTEST_PRIVATE_KEY_P8_BASE64",
    "APPLE_APNS_PRIVATE_KEY_P8_BASE64",
    "WINDOWS_SIGNING_CERTIFICATE_PFX_BASE64",
    "PUSH_TOKEN_ENCRYPTION_KEY",
}

JSON_VALUES = {
    "FIREBASE_ANDROID_GOOGLE_SERVICES_JSON_BASE64",
    "FIREBASE_SERVICE_ACCOUNT_JSON_BASE64",
}

URL_VALUES = {
    "TIMA_ANDROID_BASE_URL",
    "TIMA_IOS_BASE_URL",
    "TIMA_WINDOWS_BASE_URL",
    "WINDOWS_SIGNING_TIMESTAMP_URL",
    "ATTESTATION_GATEWAY_URL",
    "PUSH_GATEWAY_URL",
}


def validate(profile: str) -> list[str]:
    errors: list[str] = []
    values = {name: os.environ.get(name, "").strip() for name in PROFILES[profile]}
    for name, value in values.items():
        if not value:
            errors.append(f"{name} is required")
            continue
        if value.lower() in {"changeme", "placeholder", "todo"}:
            errors.append(f"{name} contains a forbidden placeholder")
            continue
        if name in BASE64_VALUES:
            try:
                decoded = base64.b64decode(value, validate=True)
            except (ValueError, binascii.Error):
                errors.append(f"{name} must be canonical base64")
                continue
            if base64.b64encode(decoded).decode("ascii") != value:
                errors.append(f"{name} must be canonical base64")
                continue
            if not decoded:
                errors.append(f"{name} decodes to an empty value")
                continue
            if name in JSON_VALUES:
                try:
                    document = json.loads(decoded)
                except (UnicodeDecodeError, json.JSONDecodeError):
                    errors.append(f"{name} must contain base64-encoded JSON")
                    continue
                if not isinstance(document, dict):
                    errors.append(f"{name} JSON must be an object")
        if name in URL_VALUES:
            parsed = urlparse(value)
            if parsed.scheme != "https" or not parsed.netloc:
                errors.append(f"{name} must be an absolute HTTPS URL")
    if profile in {"android", "server"} and not values["PLAY_INTEGRITY_CLOUD_PROJECT_NUMBER"].isdigit():
        errors.append("PLAY_INTEGRITY_CLOUD_PROJECT_NUMBER must contain decimal digits only")
    if profile == "windows":
        thumbprint = values["WINDOWS_SIGNING_CERTIFICATE_SHA256"].replace(" ", "")
        if len(thumbprint) != 64 or any(c not in "0123456789abcdefABCDEF" for c in thumbprint):
            errors.append("WINDOWS_SIGNING_CERTIFICATE_SHA256 must be a 64-character hex digest")
    return errors


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("profile", choices=sorted(PROFILES))
    args = parser.parse_args()
    errors = validate(args.profile)
    if errors:
        print(f"{args.profile} release preflight failed:", file=sys.stderr)
        for error in errors:
            print(f"- {error}", file=sys.stderr)
        return 1
    print(f"{args.profile} release preflight passed ({len(PROFILES[args.profile])} values checked)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
