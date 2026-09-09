# First Real-World Test — Running SGART on a Physical Phone

This guide walks through running the **whole SGART stack for the first time** and driving it from a
real Android phone over USB, from a WSL2 development machine.

> **Scope:** local, dev-only run for hands-on testing. Everything uses plain HTTP and dev-only
> credentials — never a production setup. No Play Store, no signing, no publishing needed.

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

### 1.1 Local infrastructure

```bash
cd ~/projects/sgart
cp -n .env.example .env          # dev-only credentials; skip if you already have .env
docker compose up -d
docker compose ps                # wait until postgres, kurrentdb, keycloak are all "healthy"
```

### 1.2 Run the backend

The backend needs Flyway (Postgres migrations) and the read-model projector switched on for a real
run — they default to *off* so tests/CI don't need live infra. The dev profile supplies the invite
HMAC secret automatically.

```bash
cd ~/projects/sgart/backend
SGART_FLYWAY_ENABLED=true \
SGART_PROJECTOR_AUTOSTART=true \
./gradlew bootRun
```

Leave this running. It listens on **`:8081`**. Sanity check from another WSL shell:

```bash
curl -s http://localhost:8081/actuator/health || echo "(health endpoint may differ — a connection at all means it's up)"
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

1. On the phone, open **sgart** (installed as "sgart"). Tap through to sign in.
2. A browser tab opens Keycloak. Sign in with a **synthetic dev user**:
   - `anna@example.test` / `anna-dev-password`
   - `ben@example.test` / `ben-dev-password`
3. The browser redirects back into the app via `de.sgart.app://oauth/callback` and you land in the
   app. Create a household, add a shopping list, etc.
4. **Test live sync (Story 4.4):** install on a second device (or sign in as `ben` in the emulator)
   in the same household and watch changes appear in real time.

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
