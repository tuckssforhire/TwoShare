#!/usr/bin/env python3
"""Prepare an isolated TwoShare Beta build in the GitHub Actions workspace.

Never run this against the stable release pipeline.
"""
from pathlib import Path
import os
import re

root = Path(__file__).resolve().parents[1]
gradle = root / "app/build.gradle.kts"
manifest = root / "app/src/main/AndroidManifest.xml"
config = root / "app/src/main/java/com/twoshare/app/data/SupabaseConfig.kt"
key = os.environ.get("TWOSHARE_DEV_PUBLISHABLE_KEY", "").strip()
if not key.startswith(("sb_publishable_", "eyJ")):
    raise SystemExit("Missing TWOSHARE_DEV_PUBLISHABLE_KEY")
if not os.environ.get("TWOSHARE_BETA_FIREBASE_JSON", "").strip():
    raise SystemExit("Missing TWOSHARE_BETA_FIREBASE_JSON: register com.twoshare.app.dev in Firebase first")

import json
firebase = json.loads(os.environ["TWOSHARE_BETA_FIREBASE_JSON"])
packages = [c.get("client_info", {}).get("android_client_info", {}).get("package_name") for c in firebase.get("client", [])]
if "com.twoshare.app.dev" not in packages:
    raise SystemExit("Firebase configuration must contain Android package com.twoshare.app.dev")
(root / "app/google-services.json").write_text(json.dumps(firebase), encoding="utf-8")

text = gradle.read_text(encoding="utf-8")
original = 'applicationId = "com.twoshare.app"'
if text.count(original) != 1:
    raise SystemExit("Expected exactly one production applicationId")
text = text.replace(original, 'applicationId = "com.twoshare.app.dev"')
text = text.replace('versionName = "1.0.31"', 'versionName = "1.0.32-beta"')
gradle.write_text(text, encoding="utf-8")

text = manifest.read_text(encoding="utf-8")
text = text.replace('android:label="TwoShare"', 'android:label="TwoShare Beta"')
text = text.replace('android:scheme="twoshare" android:host="login"', 'android:scheme="twoshare-beta" android:host="login"')
manifest.write_text(text, encoding="utf-8")

provider = root / "app/src/main/java/com/twoshare/app/data/SupabaseProvider.kt"
text = provider.read_text(encoding="utf-8")
text = text.replace('scheme = "twoshare"', 'scheme = "twoshare-beta"')
provider.write_text(text, encoding="utf-8")

text = config.read_text(encoding="utf-8")
text, urls = re.subn(r'https://[a-z0-9]+\.supabase\.co', 'https://ldxjmbkzlnzaukxjrobw.supabase.co', text)
text, keys = re.subn(r'(const val PUBLISHABLE_KEY = ")[^"]+(")', lambda m: m.group(1) + key + m.group(2), text)
if urls != 1 or keys != 1:
    raise SystemExit("Supabase configuration format changed")
config.write_text(text, encoding="utf-8")
print("TwoShare Beta configured with isolated app ID, callback scheme, Firebase and dev Supabase.")
