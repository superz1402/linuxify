# RECOVERY — Restoring the Linuxify development environment (fresh session)

Goal: a brand-new session/server can reconstruct everything from GitHub + this document.

## 1. Identify the canonical repository

- Android app (this repo): **https://github.com/superz1402/linuxify** — branch `main`.
  Clone: `git clone https://github.com/superz1402/linuxify.git`
- Reference only (do not modify, pull-only for the provided token):
  https://github.com/Bilal140202/linuxify (TypeScript CLI on Termux+proot).

Authentication: use the provided GitHub token (env var `GITHUB_TOKEN` recommended).
Never print it. Configure: `git remote set-url origin https://<TOKEN>@github.com/superz1402/linuxify.git`

## 2. Read state

1. `worklog.md` (chronological evidence log) — read the last 3 task sections.
2. `SESSION_STATE.md` (current handoff) — authoritative for "what is next".
3. Open issues: `curl -H "Authorization: Bearer $GITHUB_TOKEN" https://api.github.com/repos/superz1402/linuxify/issues?state=open`

## 3. Reconstruct the build environment

```bash
# JDK (AGP 8.7 needs JDK 17+; JDK 21 works)
sudo apt-get update && sudo apt-get install -y openjdk-21-jdk-headless unzip zip

# Android SDK (no NDK needed for this project)
export ANDROID_HOME=$HOME/android-sdk
mkdir -p $ANDROID_HOME/cmdline-tools
curl -fL -o /tmp/cmdline-tools.zip \
  https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
unzip -q /tmp/cmdline-tools.zip -d $ANDROID_HOME/cmdline-tools
mv $ANDROID_HOME/cmdline-tools/cmdline-tools $ANDROID_HOME/cmdline-tools/latest
yes | $ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager --licenses > /dev/null
$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager "platform-tools" \
  "platforms;android-35" "build-tools;35.0.0"
```

The project uses the Gradle wrapper (`./gradlew`); first run downloads Gradle 8.9
automatically. Confirm: `./gradlew --version` then `./gradlew assembleDebug`.

## 4. Resume work

1. `git fetch origin && git status` — compare local vs `origin/main`.
2. Identify last completed task from `worklog.md` + closed issues.
3. Continue with the first open issue in priority order (labels: `phase:*`, priority labels).
4. Follow the checkpoint rule: test → worklog → commit → push → verify remote SHA → update issue.

## 5. Build artifacts

- Debug APK (when built): `app/build/outputs/apk/debug/app-debug.apk`
- Release artifacts are attached to GitHub Releases (if any exist yet).

## 6. Non-recoverable context

- Nothing else is durable. The Z Super server filesystem is disposable.
- If `SESSION_STATE.md` is stale, trust git history + Issues over this file.
