#!/usr/bin/env python3
"""Sign an unsigned TwoShare APK using the permanent external release key."""
import argparse
import hashlib
import os
from pathlib import Path
import re
import subprocess
import tempfile

EXPECTED_SHA256 = "B3790D1FBC5874B880962E91D8282F4F14CC819207A3F7F2CBA1DF64EEBFEF39"
ALIAS = "twoshare-release"


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("unsigned_apk", type=Path)
    p.add_argument("output_apk", type=Path)
    p.add_argument("--keystore", type=Path, required=True)
    p.add_argument("--password-file", type=Path, required=True)
    p.add_argument("--build-tools", type=Path, required=True)
    a = p.parse_args()
    if a.output_apk.exists():
        p.error("Output exists; choose a new output path instead of overwriting a release")
    env = os.environ.copy()
    env["TWOSHARE_KEY_PASSWORD"] = a.password_file.read_text().strip()
    cert = subprocess.run([
        "keytool", "-exportcert", "-keystore", str(a.keystore),
        "-storepass:env", "TWOSHARE_KEY_PASSWORD", "-alias", ALIAS,
    ], env=env, capture_output=True, check=True).stdout
    if hashlib.sha256(cert).hexdigest().upper() != EXPECTED_SHA256:
        raise SystemExit("Signing certificate mismatch; refusing to sign")
    with tempfile.TemporaryDirectory() as d:
        aligned = Path(d) / "aligned.apk"
        subprocess.run([str(a.build_tools / "zipalign"), "-P", "16", "-f", "4",
                        str(a.unsigned_apk), str(aligned)], check=True)
        signer = ["java", "-jar", str(a.build_tools / "lib" / "apksigner.jar")]
        subprocess.run(signer + ["sign", "--ks", str(a.keystore), "--ks-key-alias", ALIAS,
                       "--ks-pass", "file:" + str(a.password_file),
                       "--key-pass", "file:" + str(a.password_file),
                       "--out", str(a.output_apk), str(aligned)], check=True)
        result = subprocess.run(signer + ["verify", "--verbose", "--print-certs",
                                str(a.output_apk)], capture_output=True, text=True, check=True)
        found = re.search(r"Signer #1 certificate SHA-256 digest: ([0-9a-fA-F]+)", result.stdout)
        if not found or found.group(1).upper() != EXPECTED_SHA256:
            raise SystemExit("Finished APK certificate does not match the permanent key")
        subprocess.run([str(a.build_tools / "zipalign"), "-c", "-P", "16", "4",
                        str(a.output_apk)], check=True)
        print(result.stdout)
        print("APK SHA-256:", hashlib.sha256(a.output_apk.read_bytes()).hexdigest())


if __name__ == "__main__":
    main()
