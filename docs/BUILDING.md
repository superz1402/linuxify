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
./gradlew assembleRelease        # release APK (keystore-signed, see Signing below)
```

Outputs: `app/build/outputs/apk/debug/app-debug.apk`,
`app/build/outputs/apk/release/app-release.apk`.

## Signing

Since v0.1.0-alpha.3 both debug and release builds are signed with the project's own
stable identity, so **updates install over previous builds without uninstalling**:

- `keystore/linuxify-release.jks` — the key (PKCS12, RSA 4096, alias `linuxify`,
  validity 10000 days)
- `keystore/keystore.properties` — `storeFile` / `storePassword` / `keyAlias` /
  `keyPassword` consumed by `app/build.gradle.kts`

**Deliberate deviation from the usual advice:** these two files are committed. The
reason is product-critical: losing the key would force every existing user to
uninstall on the next update — the exact problem this change exists to fix. The repo
is private and the keystore is only for this alpha; if the project ever goes public or
to a store, rotate to a CI-secret-based identity and treat the committed key as
compromised.

If `keystore/keystore.properties` is absent (fresh clone without the folder), the
build falls back to the standard debug keystore so it never hard-fails — but such
builds will NOT install over keystore-signed builds.

Never change the keystore between releases.

## Verifying an APK

```bash
$ANDROID_HOME/build-tools/35.0.0/aapt dump badging app-debug.apk | head -5
sha256sum app-debug.apk
unzip -l app-debug.apk | grep "lib/"    # native ABI payloads
```

Expected: `package: name='com.linuxify.app'`, `sdkVersion:'26'`,
`targetSdkVersion:'28'`, `lib/arm64-v8a/*` and `lib/x86_64/*` payloads.

> Why targetSdk 28 and not 35: Android 10+ denies `execve()` of files in app-private
> storage for apps targeting ≥ 29, and the Linux userspace (proot guests) runs
> exactly from there. targetSdk 28 keeps the app in the `untrusted_app_27` SELinux
> domain, which still allows exec — the same trade-off Termux and UserLAnd make.
> `compileSdk` stays 35. See docs/RUNTIME.md "Incident record" for the full story.
