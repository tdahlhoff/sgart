# First Real-World Test — Running SGART on a Physical Phone

This guide walks through running the **whole SGART stack for the first time** and driving it from a
real Android phone over USB, from a WSL2 development machine.

> **Scope:** local, dev-only run for hands-on testing. Everything uses plain HTTP and dev-only
> credentials — never a production setup. No Play Store, no signing, no publishing needed.

**Why local, even though a netcup vHost now exists (ADR-0002):** the vHost is provisioned but not
deploy-ready — `docker-compose.yml` still runs the dev-only flags (`KURRENTDB_INSECURE`, Keycloak
`start-dev`) and there is no TLS reverse proxy, no encrypted-at-rest volume, and no secrets story
beyond a plain `.env` yet (ADR-0002 "Production concretization" backlog, items 2-5). Running there
before that hardening lands would expose an unhardened dev stack processing personal data (Rule 5)
on the public internet. Test locally until that backlog closes; the vHost becomes a deploy-readiness
milestone of its own, not something to fold into a feature/manual test pass.

## Why this setup

- Backend, Keycloak, KurrentDB and Postgres all run **inside WSL2**.
- WSL2 is in NAT mode, so a phone on your Wi-Fi cannot reach WSL services directly. Instead we plug
  the phone in over **USB** and use **`adb reverse`**, which forwards the phone's own
  `localhost:8081`/`:8080` to the WSL host. That means:
  - no `netsh portproxy`, no Windows firewall rules, no LAN-IP juggling;
  - the app's **default** URLs (`http://localhost:8081`, `http://localhost:8080/realms/sgart`) work
    unchanged, and the Keycloak token issuer matches the backend's expected issuer out of the box.

### One-time prerequisites you will install

| What | Where | Why |
| --- | --- | --- |
| Android SDK (cmdline-tools + platform + build-tools) | WSL2 | Flutter here has **no** Android SDK yet — it can only run `flutter test`/`analyze`. Needed to build the APK. |
| `usbipd-win` | Windows | Passes the phone's USB connection through to WSL2 so `adb` in WSL sees it. |
| Android debug cleartext config | already added to the repo | Android blocks plain-HTTP by default; a **debug-only** config (`app/android/app/src/debug/res/xml/network_security_config.xml`) opens it for the dev hosts only. Release builds stay TLS-only. |

---

## Part 0 — One-time toolchain setup

### 0.1 Install the Android SDK in WSL2

Install the command-line tools (no Android Studio GUI needed):

```bash
sudo apt-get update && sudo apt-get install -y unzip openjdk-17-jdk  # JDK for the SDK tooling
mkdir -p ~/Android/Sdk/cmdline-tools
cd /tmp
# Get the latest "Command line tools only" (Linux) URL from https://developer.android.com/studio
curl -o cmdline-tools.zip https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
unzip -q cmdline-tools.zip -d ~/Android/Sdk/cmdline-tools
mv ~/Android/Sdk/cmdline-tools/cmdline-tools ~/Android/Sdk/cmdline-tools/latest
```

Add to your `~/.bashrc` (then `source ~/.bashrc`):

```bash
export ANDROID_HOME="$HOME/Android/Sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export PATH="$HOME/tools/flutter/bin:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"
```

Install the SDK packages and accept licenses:

```bash
sdkmanager "platform-tools" "platforms;android-35" "build-tools;35.0.0"
sdkmanager --licenses          # accept all
flutter config --android-sdk "$ANDROID_HOME"
flutter doctor                 # "Android toolchain" should now be ✓
```

> `adb` now lives at `$ANDROID_HOME/platform-tools/adb` and is on your PATH.

### 0.2 Install usbipd-win (on Windows)

In an **Administrator PowerShell** on Windows:

```powershell
winget install --exact dorssel.usbipd-win
```

Enable USB debugging on the phone: **Settings → About phone → tap Build number 7×**, then
**Settings → System → Developer options → USB debugging = on**. Plug the phone in.

---

## Part 1 — Start the backend stack (WSL2)

> **Shortcut:** [`scripts/start.sh`](../scripts/start.sh) automates all of Part 1 (and the emulator
> in Part 2/3 + `flutter run`) in one command — infra, backend with the correct flags, emulator, and
> `adb reverse`. The manual steps below remain the reference for what it does and for a
> physical-phone run. Tear down with [`scripts/stop.sh`](../scripts/stop.sh).

### 1.1 Local infrastructure

```bash
cd ~/projects/sgart
cp -n .env.example .env          # dev-only credentials; skip if you already have .env
docker compose up -d
docker compose ps                # wait until postgres, kurrentdb, keycloak are all "healthy"
```

### 1.2 Run the backend

The backend needs Flyway (Postgres migrations) and the read-model projector switched on for a real
run — they default to *off* so tests/CI don't need live infra. The dev profile supplies the
email-recovery code HMAC secret (Story 7.3) automatically.

```bash
cd ~/projects/sgart/backend
SGART_FLYWAY_ENABLED=true \
SGART_PROJECTOR_AUTOSTART=true \
SGART_POSTGRES_PASSWORD=sgart_dev_password \
./gradlew bootRun
```

`SGART_POSTGRES_PASSWORD` must match `.env`'s `POSTGRES_PASSWORD` — `docker compose` reads `.env`
automatically, but a plain shell invocation of `./gradlew bootRun` does not, and the datasource's
default password is empty (`application.yaml`), which fails Postgres's SCRAM auth outright.

Leave this running. It listens on **`:8081`**. Sanity check from another WSL shell — or run
[`scripts/health-check.sh`](../scripts/health-check.sh), which checks all of this (docker compose
service health, the backend, and Keycloak) in one go:

```bash
curl -s http://localhost:8081/actuator/health   # {"status":"UP"} once Postgres/KurrentDB are reachable
curl -s http://localhost:8080/realms/sgart/.well-known/openid-configuration | head -c 200   # Keycloak realm reachable
```

---

## Part 2 — Attach the phone to WSL2

In **Administrator PowerShell** (Windows), find and attach the phone's USB bus to WSL:

```powershell
usbipd list                       # note the BUSID of your phone (e.g. 2-4)
usbipd bind    --busid 2-4        # one-time per device (persists)
usbipd attach  --wsl --busid 2-4  # re-run this after each replug / reboot
```

Back in **WSL2**, confirm adb sees it (approve the "Allow USB debugging?" prompt on the phone):

```bash
adb devices                       # should list your device as "device" (not "unauthorized")
flutter devices                   # the phone should appear here too
```

---

## Part 3 — Wire the phone's localhost to the backend

With the phone connected, forward its loopback ports to the WSL host:

```bash
adb reverse tcp:8081 tcp:8081     # phone -> backend
adb reverse tcp:8080 tcp:8080     # phone -> Keycloak
adb reverse --list                # verify both mappings
```

> Re-run these two `adb reverse` commands whenever the phone reconnects.

Now the phone can reach the backend and Keycloak at `http://localhost:...`, which is exactly what
the app defaults to — so no `--dart-define` overrides are needed for this USB path.

---

## Part 4 — Build, install and run the app

```bash
cd ~/projects/sgart/app
flutter pub get
flutter run                       # builds the debug APK, installs it, and attaches (hot reload)
```

`flutter run` picks the connected phone automatically (if you have more than one device, add
`-d <device-id>` from `flutter devices`). The debug build includes the cleartext config from
`src/debug`, so HTTP to `localhost` is allowed.

To leave a build on the phone without keeping the terminal attached:

```bash
flutter install                   # installs the debug APK; launch it from the app drawer later
```

---

## Part 5 — Use it

1. On the phone, open **sgart** (installed as "sgart"). It signs in **silently** (Story 7.1) — no
   email, username, password, or browser tab: the app generates a device secret in the secure
   enclave, the backend creates a Keycloak account bound to its public key, and the app signs in
   via the custom Direct-Grant flow, all before anything is shown. You land straight on the
   create/await-invite choice.
2. Create a household, add a shopping list, etc.
3. **Test live sync (Story 4.4) / multi-person households:** each install/device silently
   provisions its **own**, distinct account — there is no "sign in as a different seeded user"
   anymore (that was Story 1.4's password-based flow, removed in 7.1; recovering the *same*
   account on a second device via the recovery phrase is Story 7.2, not yet built). To get a
   second device into the same household, use the in-app **invite** flow (Story 4.6): create the
   household on the first device, send an invite, and accept it from the second device/install.
   The `anna`/`ben`/`carla` synthetic users seeded in `keycloak/realm-sgart.json` still exist for
   backend-only manual testing (e.g. a direct Admin API call), but the app itself has no UI path
   left that signs in with a password.

---

## Teardown

```bash
# WSL2
Ctrl-C in the bootRun terminal        # stop backend
docker compose down                   # stop infra (volumes persist)
# Windows (Admin PowerShell)
usbipd detach --busid 2-4             # release the phone back to Windows
```

---

## Troubleshooting

| Symptom | Likely cause / fix |
| --- | --- |
| App shows network/connection errors on every call | `adb reverse` not set (or phone reconnected) — re-run the Part 3 commands. |
| Cleartext-not-permitted error in logs | You built a **release** APK. Use the debug build (`flutter run` / `flutter install`) — cleartext is debug-only by design. |
| Sign-in fails / `invalid issuer` in backend logs | The token issuer must match. On this USB path everything is `localhost`, so leave the defaults; don't pass `--dart-define` issuer overrides. |
| `adb devices` shows `unauthorized` | Approve the "Allow USB debugging" prompt on the phone; then `adb kill-server && adb start-server`. |
| Phone not in `usbipd list` / not attaching | Try a different USB cable/port; re-run `usbipd attach` after replug; ensure USB debugging is on. |
| Backend starts but first-run routing is empty | `SGART_PROJECTOR_AUTOSTART=true` and `SGART_FLYWAY_ENABLED=true` must be set (Part 1.2). |
| Keycloak unhealthy | Give it ~30s (`start_period`); check `docker compose logs keycloak`. |
| Keycloak container exits with `Value too long for column "DESCRIPTION..."` | A client `description` in `keycloak/realm-sgart.json` exceeds Keycloak's 255-char column limit — shorten it. |
| `bootRun` fails with `FlywaySqlUnableToConnectToDbException` / SCRAM auth error | Missing `SGART_POSTGRES_PASSWORD` (see Part 1.2) — `docker compose` reads `.env` automatically, a plain shell does not. |
| `bootRun` fails with `Migration checksum mismatch for migration version N` | Local Postgres volume has drifted from the current migration files (only possible if `docker compose up`'s Postgres was run against this repo before). Local dev data only: `docker compose down -v && docker compose up -d` to reset it. |

---

## Background push notifications (Story 4.5, D2) — deferred manual wiring

Story 4.5 shipped device-token registration, the swappable `ContentFreePushSender` port, and the
content-free notification fan-out — but the **live** transport is deliberately left unwired (locked
decision D2): the wired default is `LoggingContentFreePushSender` (logs the payload instead of
sending it), and `FcmContentFreePushSender` is a documented skeleton behind
`sgart.push.fcm.enabled` that throws `UnsupportedOperationException` if turned on before this
section's steps are done. The Flutter side ships only the `PushNotifications` port,
`BackendDeviceRegistrationClient`, and a fake — **no** `firebase_messaging` dependency, no native
Android/iOS config. None of this needs external credentials for `flutter test`/`flutter
analyze`/the backend test suite to stay green; it is only needed to see a **real** push arrive on a
physical phone with the app backgrounded.

To wire it up for real (not required for this guide's Parts 0–5, which already prove live sync over
SSE while the app is foregrounded):

1. **Firebase project.** Create a Firebase project, add an Android app with package id
   `de.sgart.app` (or your build's applicationId), download `google-services.json` into
   `app/android/app/`. Add the Google Services Gradle plugin to `app/android/build.gradle.kts` /
   `app/android/app/build.gradle.kts` (not present in this repo yet).
2. **Backend: implement `FcmContentFreePushSender`.** Add the `firebase-admin` Java SDK dependency
   to `backend/build.gradle.kts`, download a service-account key (Firebase Console → Project
   Settings → Service Accounts), and replace the skeleton's
   `throw new UnsupportedOperationException(...)` in
   `backend/src/main/java/de/sgart/collaboration/adapter/out/FcmContentFreePushSender.java` with a
   real `FirebaseMessaging.send(...)` call — map FCM's `UNREGISTERED`/`NOT_FOUND` error to
   `PushDeliveryResult.TOKEN_INVALID` (AC5's stale-token prune already wired above it). Set
   `SGART_PUSH_FCM_ENABLED=true` to switch the wired bean from `LoggingContentFreePushSender`.
   Never commit the service-account key — treat it like the existing `.env` secrets.
3. **Flutter: add `firebase_messaging`.** Add the `firebase_messaging` package (verify latest
   version supported by this repo's Flutter/Dart SDK constraint, CLAUDE.md §7) and run
   `flutterfire configure` (or hand-wire `Firebase.initializeApp`) to generate
   `firebase_options.dart`. Implement a concrete `PushNotifications` behind the existing port
   (`app/lib/shared/push/push_notifications.dart`) that obtains the FCM token via
   `FirebaseMessaging.instance.getToken()`, calls `BackendDeviceRegistrationClient.register(...)`,
   listens to `FirebaseMessaging.onTokenRefresh` to re-register, and surfaces
   `FirebaseMessaging.onMessage`/`onBackgroundMessage` as `HouseholdChangeNudge`s on
   `incomingPushes` — never read any payload field beyond `householdId`/`resource` into state
   (LD-1).
4. **iOS: APNs.** Add the Apple Push Notification key/cert in the Firebase Console, enable the Push
   Notifications + Background Modes → Remote notifications capabilities in Xcode, and add
   `GoogleService-Info.plist`.
5. **Verify.** Background the app (not force-quit), trigger a list change from another member, and
   confirm the notification arrives; then re-open the app and confirm it wakes-and-fetches instead
   of rendering anything from the push payload.

---

## Invite deep link + web fallback (Story 4.6) — deferred manual wiring

Story 4.6 wired every seam that is unit/integration-testable without a production host (locked
decision D1): the host-scoped `de.sgart.app://invite` deep-link handler, the minimal static
`/invite` web-fallback page, and the single `InviteLinkFactory` (`sgart.invite.base-url`) every
entry point builds its link from. None of this needs external credentials or a real domain for `flutter test`/`flutter
analyze`/the backend test suite to stay green — it is only needed to verify the **production**
entry points end-to-end: a browser or another device opening a real invite link, without the app
already installed via USB/emulator.

### Trigger the deep link manually (dev, no production host needed)

With the app installed (this guide's Parts 0–5) and a pending invite's `householdId`/`inviteId` in
hand (e.g. from the dev-profile `InvitePersonHandler` log line, `sgart.invite.base-url`'s log-only
guard, Story 4.6 AC6):

```bash
adb shell am start -a android.intent.action.VIEW \
  -d "de.sgart.app://invite?h=<householdId>&i=<inviteId>" de.sgart.app
```

This exercises the exact `AndroidManifest.xml` intent-filter/`InviteDeepLinkService` path AC1
covers with unit/widget tests — the `adb` step is what proves the OS actually routes the URI to the
installed app, which `flutter test` cannot.

### Verified `https` Android App Links + iOS Universal Links (needs the production domain)

The dev-only custom scheme (`de.sgart.app://invite?...`) works without any of this — it is the
**fallback-free** path once the app is installed. Verified `https://<domain>/invite` App
Links/Universal Links additionally let the *same* link open the app directly from a browser/chat
app without a scheme prompt, and are what makes the web-fallback page (below) actually reachable at
a real URL:

1. **Android App Links.** Host `https://<domain>/.well-known/assetlinks.json` (served by the
   production reverse proxy) declaring the app's package id and the **release-signing** key's
   SHA-256 fingerprint (`keytool -list -v -keystore <release-keystore>`, not the debug key). Add an
   `autoVerify="true"` `https` intent filter for host `<domain>`, path `/invite`, alongside the
   existing custom-scheme filter added in Story 4.6 (`AndroidManifest.xml`) — do not remove the
   custom-scheme one, it is what dev/USB testing above still uses.
2. **iOS Universal Links.** Host `https://<domain>/.well-known/apple-app-site-association` (no file
   extension, served as `application/json`) declaring the app's Team ID + bundle id and the
   `/invite` path. Add the `associated-domains` entitlement (`applinks:<domain>`) in Xcode's
   Signing & Capabilities.
3. **Verify** with each platform's own domain-verification diagnostics (Android: `adb shell pm
   get-app-links de.sgart.app`; iOS: the Associated Domains diagnostics in Xcode/Console) before
   relying on it — a misconfigured `assetlinks.json`/AASA silently falls back to opening a browser
   instead of the app.

### Reverse-proxy `/invite` route (needs the production host)

Per ADR-0002 (no TLS reverse proxy yet): once one exists, route `https://<domain>/invite*` to the
backend's `GET /invite`/`GET /invite/config.json` (Story 4.6 `InviteWebFallbackController`) the same
way the API's `/api/v1/**` prefix is routed — same origin, so the page's same-origin `fetch()` calls
to `/api/v1/households/{h}/invites/{i}/accept` need no CORS configuration.

### Production Keycloak redirect URIs for the web fallback (needs the production domain)

The dev-only `sgart-web-invite` client (`keycloak/realm-sgart.json`) is scoped to
`http://localhost:8081/invite*`/`http://localhost:8081` (`webOrigins`). For production, add the
real domain's redirect URI (`https://<domain>/invite*`) and web origin (`https://<domain>`) to that
client — do not reuse `sgart-app`'s native redirect set (Q1's default, isolating web origins from
the native app). Update `sgart.invite.base-url`
(`SGART_INVITE_BASE_URL=https://<domain>/invite`) and the web page's served `authorizeUrl`/
`tokenUrl` (driven by `sgart.security.jwt.issuer`, already environment-driven) to match.

### Silent account provisioning (Story 7.1): the custom SPI, rate limiting, and the admin secret

**Deploying the custom Direct-Grant authenticator SPI.** The device-signed-challenge sign-in
(`POST /realms/sgart/protocol/openid-connect/token`, `grant_type=password` repurposed for a
signature — never an actual password) is verified by `backend/keycloak-authenticator`, a *separate*
Gradle module (not a dependency of the backend app, F2) packaged as a provider JAR. Build it before
`docker compose up` — `docker-compose.yml` mounts `backend/keycloak-authenticator/build/libs` into
`/opt/keycloak/providers`, and `start-dev` auto-rebuilds Keycloak's optimized image on boot when it
detects a new/changed provider (slower first start only):

```bash
cd ~/projects/sgart/backend
./gradlew :keycloak-authenticator:jar
cd ~/projects/sgart
docker compose up -d keycloak   # or the full stack — the mount only matters for this container
```

Forgetting this step is easy to spot: `sgart-app`'s Direct Grant flow (`sgart-device-direct-grant`,
`keycloak/realm-sgart.json`) references the authenticator by its provider id
(`sgart-device-signed-challenge`); if the JAR isn't loaded, Keycloak fails to build the flow at
startup and logs an error rather than silently accepting logins.

**Rate limiting (AC7) — a documented seam, not yet live.** `POST /api/v1/accounts` is the one
unauthenticated write endpoint in the app (D-E, `SecurityConfig`) — reachable by anyone who can
reach the backend, by design (an account must exist before there is anything to authenticate with).
Its abuse-surface mitigation is **IP-based rate limiting at the TLS reverse proxy** (ADR-0002), not
application code (KISS; a beta-scope decision). That proxy does not exist yet (see "Why local, even
though a netcup vHost now exists" at the top of this guide) — until it does, this endpoint has **no
rate limit at all** in any real deployment. Known limits of the eventual IP-based approach, stated
up front so they are not mistaken for a future bug: **CGNAT/IP rotation** means a single bad actor
can spread requests across many apparent source IPs (a speed bump, not a wall), and a shared-IP
network (campus Wi-Fi, CGNAT mobile carriers) can see legitimate users share a rate-limit bucket.
**Device attestation** (Play Integrity / DeviceCheck) is a named post-beta fast-follow that
meaningfully raises the bar here — explicitly out of scope for this story.

**The `sgart-admin` secret.** Story 7.1 gives `sgart-admin`'s `realm-management` service account the
`manage-users` role so `KeycloakAdminCreateAccount` can create and delete real accounts — which
makes the checked-in dev placeholder secret
(`local-dev-only-keycloak-admin-secret-change-me`, `keycloak/realm-sgart.json`) urgent to
replace. Do not ship real account creation against the
placeholder secret; provision a real secret (a secrets manager, or at minimum an environment
variable never committed) before enabling `SGART_IDENTITY_KEYCLOAK_ADMIN_ENABLED=true` outside dev.

### Recover by email (Story 7.3): SGART's own SMTP send + the code HMAC secret

The one Epic-7 feature that sends email. `AttachRecoveryEmail`/`RequestEmailRecoveryCode` send
their 6-digit one-time codes over SGART's **own** SMTP (never a Keycloak-hosted verify-email/
reset-credentials link — the AC forbids a browser page), via `JavaMailSenderRecoveryCodeEmail`
(`spring-boot-starter-mail`), config-gated exactly like the Keycloak Admin adapter above:
`SGART_IDENTITY_MAIL_ENABLED=true` (default `false`) selects it over the `DeferredSendRecoveryCodeEmail`
no-op, so `./gradlew test`/CI/local dev never need a live SMTP server. Set `SGART_IDENTITY_MAIL_FROM`
and the standard `spring.mail.*` properties (`SGART_SMTP_HOST`/`_PORT`/`_USERNAME`/`_PASSWORD`) to a
real netcup mailbox once its outbound mail-block is removed and **SPF/DKIM** are configured on the
sending domain — sending recovery codes from an unauthenticated domain will land in spam or be
rejected outright.

Also set `SGART_IDENTITY_EMAIL_RECOVERY_CODE_HMAC_SECRET` to a real per-deployment secret (never the
checked-in dev placeholder, `local-dev-only-email-recovery-hmac-secret-change-me`) before enabling
either the mail adapter or the Keycloak Admin adapter outside dev — `HmacSha256RecoveryCodeHasher`
fails context startup on a blank secret.

The recovery/rebind endpoints add no new unauthenticated surface (D-C) and inherit the same
IP-based rate-limiting seam (ADR-0002) documented above for `POST /api/v1/accounts`; the per-code
15-minute TTL and 5-attempt cap are this story's own abuse-resistance layer in the meantime.

---

## Alternatives to this USB path

- **Android emulator (on Windows):** reaches the host via `10.0.2.2`. App flags:
  `--dart-define=SGART_BACKEND_BASE_URL=http://10.0.2.2:8081`
  `--dart-define=SGART_KEYCLOAK_ISSUER=http://10.0.2.2:8080/realms/sgart`; backend also set to
  `SGART_KEYCLOAK_ISSUER=http://10.0.2.2:8080/realms/sgart` (keep `JWK_SET_URI` on `localhost`).
- **Phone over Wi-Fi (no USB):** needs Windows `netsh interface portproxy` for `:8080`+`:8081` →
  the WSL IP plus firewall rules, and the LAN IP (`192.168.0.166`) in the dart-defines and issuer.
  More moving parts and the WSL IP changes on reboot — prefer the USB path above.
- **Flutter + Android Studio on Windows:** avoids the WSL Android SDK and usbipd (USB works
  natively on Windows); backend stays in WSL2, still reached via `adb reverse`. A second toolchain
  to maintain.
