# Android Release Signing

Release builds of the SGART app (`de.sgart.sgart`) must be signed with a real, long-lived keystore —
not the auto-generated debug key `flutter run` uses. This is what lets a beta tester update the app
in place instead of uninstalling/reinstalling on every new build, since Android refuses an update
whose signature doesn't match the currently installed one.

This is a one-time, **manual, Timo-only** step — the keystore is a secret credential, generated
outside the repo and never committed (see `.gitignore`).

## 1. Generate the keystore (once)

```bash
cd app/android
keytool -genkeypair -v -keystore sgart-release.jks -alias sgart \
  -keyalg RSA -keysize 2048 -validity 10000
```

`keytool` prompts for a store password, a key password (can be the same), and identity fields (name,
org, etc. — not security-sensitive, but avoid putting real personal data here since it's embedded in
the certificate). `-validity 10000` (days, ~27 years) avoids ever needing to re-sign for expiry.

**Back up `sgart-release.jks` and its passwords somewhere durable outside the repo** (a password
manager, encrypted off-machine storage). Losing it means every future release build gets a new
identity and Android treats it as a different app — no more in-place updates for existing installs,
ever, for anyone who has the old one installed. Leaking it means anyone can sign an app that
Android/Play Store will treat as an authentic SGART update.

## 2. Wire it into the Gradle build (once)

```bash
cd app/android
cp key.properties.example key.properties
```

Edit `key.properties` with the real store/key passwords and alias from step 1, and confirm
`storeFile` points at `sgart-release.jks` (relative to `app/android/`).

`key.properties` and `*.jks`/`*.keystore` are gitignored — verify `git status` shows neither
before ever committing from this directory.

`app/android/app/build.gradle.kts` reads `key.properties` and wires a `signingConfigs.release` used
by the `release` build type. If `key.properties` doesn't exist (CI, a fresh clone, another
machine), the release build type **falls back to the debug key** so `flutter build`/`flutter run
--release` keep working without a keystore — only Timo's machine, with the real `key.properties` in
place, produces an APK actually signed for distribution.

## 3. Build and distribute

```bash
cd app
flutter build apk --release
```

The signed APK lands at `app/build/app/outputs/flutter-apk/app-release.apk`. For this beta's scope
(a few known testers, manual sideload — see [ADR-0002](adr/0002-production-hosting-on-netcup-vps.md)
for why no Play Store / App Links are needed yet), send that file directly; the tester enables
"install from unknown sources" once and installs it. **Every subsequent release must be built with
the same keystore** for updates to install in place.

## Related

- Not the same key as [Android App Links](first-real-world-test.md) — that (deferred, needs a domain
  we control) verifies the app against an `assetlinks.json` on the server using this same signing
  key's SHA-256 fingerprint (`keytool -list -v -keystore sgart-release.jks`). Out of scope for now,
  but note it down for when App Links are wired up: no new keystore needed then, just this
  fingerprint.
