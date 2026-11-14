# Permanent release signing from TwoShare 1.0.37

The original debug signing key is unavailable. Version 1.0.37 starts a new,
permanent release identity and requires a one-time reinstall for users of older
official versions. Preserve server data and save any local drafts before reinstalling.

Certificate SHA-256:

`B3790D1FBC5874B880962E91D8282F4F14CC819207A3F7F2CBA1DF64EEBFEF39`

The RSA-4096 private key is protected in a PKCS12 keystore with a random password.
The encrypted keystore backup and its recovery password are retained privately as
separate files. They must never be committed, cached by GitHub Actions, printed in
logs, or uploaded to a public release. Keep an additional offline backup of both,
with the password in a password manager separate from the keystore archive.

GitHub Actions compiles **unsigned** APK artifacts only. They are not distributable
releases. Restore the permanent key into a private signing environment and run:

```bash
python3 scripts/sign-release.py unsigned.apk TwoShare-v1.0.37.apk \
  --keystore /private/twoshare-release-20261114.p12 \
  --password-file /private/signing-password.txt \
  --build-tools /path/to/android-build-tools
```

The helper validates the keystore's public certificate, aligns the APK, signs it,
and verifies the finished certificate and alignment. Publish only that signed APK.
Use this same key for all future releases; do not generate a replacement key for
routine updates. Recovery was tested by extracting the backup and checking its
certificate before the first replacement release.
