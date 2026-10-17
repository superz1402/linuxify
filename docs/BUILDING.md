# Building

## Requirements

- JDK 17+ (Temurin 21 tested)
- Android SDK: platform `android-35`, build-tools `35.0.0`, platform-tools
- No Android NDK required — native binaries are pinned prebuilts (see `runtime-binaries.json`)

## Reproducible toolchain setup (no root needed)

```bash
# JDK
curl -fL -o /tmp/jdk21.tar.gz \
  "https://api.adoptium.net/v3/binary/latest/21/ga/linux/x64/jdk/hotspot/normal/eclipse"
mkdir -p ~/toolchain && tar -xzf /tmp/jdk21.tar.gz -C ~/toolchain

# Android SDK
export ANDROID_HOME=$HOME/android-sdk
mkdir -p $ANDROID_HOME/cmdline-tools
curl -fL -o /tmp/cmdline-tools.zip \
  https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
# (published sha256: 2d2d50857e4eb553af5a6dc3ad507a17adf43d115264b1afc116f95c92e5e258)
unzip -q /tmp/cmdline-tools.zip -d $ANDROID_HOME/cmdline-tools
mv $ANDROID_HOME/cmdline-tools/cmdline-tools $ANDROID_HOME/cmdline-tools/latest
yes | $ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager --licenses
$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager "platform-tools" \
  "platforms;android-35" "build-tools;35.0.0"
```

## Build

```bash
export JAVA_HOME=~/toolchain/jdk-21
export ANDROID_HOME=~/android-sdk
echo "sdk.dir=$ANDROID_HOME" > local.properties   # machine-local, not committed

./gradlew assembleDebug          # debug APK
./gradlew testDebugUnitTest      # unit tests
./gradlew assembleRelease        # release APK (debug-signed until maintainer keys exist)
```

Outputs: `app/build/outputs/apk/debug/app-debug.apk`,
`app/build/outputs/apk/release/app-release.apk`.

## Signing

Debug builds use the standard generated debug keystore. Release builds currently reuse
the debug signing config **by explicit choice** so that a testable artifact exists;
this is not a production identity. A maintainer must provision a real keystore:

1. Generate a keystore outside the repo (`*.jks` is gitignored).
2. Add `RELEASE_STORE_FILE`, `RELEASE_STORE_PASSWORD`, `RELEASE_KEY_ALIAS`,
   `RELEASE_KEY_PASSWORD` to a non-committed `gradle.properties` or CI secrets.
3. Replace the `signingConfig = signingConfigs.getByName("debug")` line in
   `app/build.gradle.kts`.

Never commit keystore files or passwords.

## Verifying an APK

```bash
$ANDROID_HOME/build-tools/35.0.0/aapt dump badging app-debug.apk | head -5
sha256sum app-debug.apk
unzip -l app-debug.apk | grep "lib/"    # native ABI payloads
```

Expected: `package: name='com.linuxify.app'`, `sdkVersion:'26'`,
`targetSdkVersion:'35'`, `lib/arm64-v8a/*` and `lib/x86_64/*` payloads.
