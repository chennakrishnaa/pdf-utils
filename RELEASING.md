# Releasing

Release builds are signed with an upload key that is **never committed**. `keystore.properties`,
`*.jks`, `*.keystore`, `*.p12` and `dist/release/` are gitignored.

## 1. Create the key (once)

```sh
keytool -genkeypair -keystore ~/keys/pdfutils-upload.jks -storetype PKCS12 \
    -alias upload -keyalg RSA -keysize 4096 -validity 10000
```

Keep the keystore **outside the repo**. Use the same password for the store and the key
(PKCS12 keystores do not support different ones reliably).

## 2. Tell the build where the key is

Either a `keystore.properties` file at the repo root (gitignored; takes precedence):

```properties
storeFile=/home/you/keys/pdfutils-upload.jks
storePassword=...
keyAlias=upload
keyPassword=...
```

or environment variables (used only when `keystore.properties` is absent):

```sh
export PDFUTILS_STORE_FILE=/home/you/keys/pdfutils-upload.jks
export PDFUTILS_STORE_PASSWORD=...  PDFUTILS_KEY_ALIAS=upload  PDFUTILS_KEY_PASSWORD=...
```

A relative `storeFile` is resolved against the repo root. If only some of the four values are
set, Gradle stops with an error naming the missing ones. If none are set, `assembleRelease`
produces an unsigned `app-release-unsigned.apk` as before, so `./scripts/verify.sh` still works
without a key.

## 3. Build and check

```sh
./scripts/release.sh            # verify.sh, then assembleRelease bundleRelease, then checks
./scripts/release.sh --skip-verify
./scripts/release-smoke.sh      # installs dist/release/*.apk on the pdfutils-smoke emulator
```

`release.sh` verifies the APK signature (v2 + v3) and the bundle signature, checks the
signed APK (no permissions, only the launcher activity exported, 64-bit libs only, not debuggable,
16 KB-aligned native libs), and writes `dist/release/<applicationId>-<versionName>.apk` and
`.aab`. It prints sizes, SHA-256 hashes and the signing certificate's SHA-256, and ends with
`RELEASE OK`. Upload the `.aab` to Play. The `.apk` is for sideloading and testing.

Before each release, bump `versionCode` (and `versionName`) in `app/build.gradle.kts`.

## 4. Back up

Store these off this machine, for example in a password manager plus an offline copy:

- the keystore file (`.jks`),
- the store/key password and the alias,
- the certificate SHA-256 printed by `release.sh`, to confirm later that you have the right key.

If you lose the key, you cannot ship updates that install over existing sideloaded copies. With
Play App Signing, Play can reset a lost upload key, but only through a support request.
