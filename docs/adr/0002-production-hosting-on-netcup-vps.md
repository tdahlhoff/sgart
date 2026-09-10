# ADR-0002 — Production hosting on a netcup VPS (EU), co-located with a Murmur server

- **Status:** Accepted — provider and server tier chosen; deployment hardening still to be built
  (the "Production concretization" backlog in ARCHITECTURE-SPINE.md)
- **Date:** 2026-09-10
- **Deciders:** Timo Dahlhoff

## Context

The SGART backend is a modular monolith that stands up **five long-lived services** (see
`docker-compose.yml`): the Spring Boot 4.1 / Java 25 backend, KurrentDB 25.1.4 (event-store write
model), PostgreSQL 18 (CQRS read models), and Keycloak 26.7 (identity provider). Alongside it we
want to run a **Murmur (Mumble) voice server** for 2–4 people on the same host.

This rules out shared web hosting: five daemons plus a Murmur daemon, orchestrated via
Docker/Compose, need **root access on a KVM-virtualized VPS**, not a restricted shared tier.

Two forces shaped the choice:

- **Budget.** Target was the low single-digit €/month range, not a dedicated-vCPU tier. Hetzner's
  dedicated CCX line (~26–40 €/month) was explicitly rejected as too expensive for a 2–4-person
  project.
- **GDPR (CLAUDE.md Rule 5).** Receipt contents, purchase history, and household membership are
  personal data. The host processes that data on our behalf, so the provider must offer an
  **Auftragsverarbeitungsvertrag (AVV / Art. 28 DPA)** and keep data inside the EU.

**Why not Hetzner.** Hetzner Cloud's cost-optimized (shared-vCPU) line was the first choice, but on
2026-09-10 **every model in that line — CX23/CX33/CX43/CX53 and the CAX ARM equivalents — was sold
out at all locations** (verified on the live product page). Hetzner's cheap line stocks out
recurrently; only the pricier dedicated CCX line was orderable. netcup (German GmbH, EU data
centers, AVV available) offered an equivalent tier, in the same price corridor, available now.

**EU is sufficient, Germany is not required.** The physical location does not have to be Germany;
any EU location satisfies GDPR (no third-country transfer, AVV covers the processing). This makes
netcup's automatic-EU-location tiers acceptable.

## Decision

**Host the backend stack and the Murmur server on a `netcup VPS Lite 2 G12s`.**

Chosen server specification (baseline for all deployment/config sizing decisions):

| Attribute | Value |
|---|---|
| Model | netcup VPS Lite 2 G12s |
| CPU | 4 vCore, **KVM virtualization, x86** |
| RAM | **8 GB** |
| Storage | 160 GB **SSD** |
| Price | ~7,92 €/month incl. VAT, 0 € setup |
| Term | 3-month minimum / quarterly billing |
| Location | **Automatic EU selection** (Nürnberg DE / Wien AT / Amsterdam NL) — not guaranteed Germany |
| Bandwidth | Throttled to 100 Mbit/s if the 24 h average exceeds it (irrelevant for this workload) |

**x86, not ARM** — so there are no arm64-image concerns (e.g. KurrentDB runs as its normal x86
image); this removed the only open technical question about the ARM alternative (CAX/netcup ARM).

**8 GB is the hard floor** for this stack. All five services plus the OS must fit. This is tight,
so deployment must **cap the JVM heaps of both the backend and Keycloak (`-Xmx`)** and provision
**~2 GB swap**. Any future sizing decision starts from these 8 GB.

## Server access

| Attribute | Value |
|---|---|
| Hostname | `v2202609416029517751.happysrv.de` |
| IPv4 | `89.58.46.117` |
| IPv6 | `2a03:4000:67:79d:38ed:78ff:fe14:2cb5` |
| SSH | Key-only (password auth disabled), `PermitRootLogin no` — log in as `timo`, use `sudo` for root actions |
| Users | `timo` (sudo-enabled), `root` (SSH login disabled, reachable via netcup KVM/Rescue console) |
| Intrusion protection | `fail2ban` active on the `sshd` jail |
| Emergency access | netcup Server Control Panel → KVM console / Rescue system (works independently of SSH; use this if locked out) |

## Domain

**No domain purchase — use netcup's assigned hostname, `v2202609416029517751.happysrv.de`, for TLS.**
It already forward-resolves to the VPS's IPv4 (`89.58.46.117`, verified via `getent ahosts`
2026-09-10), which is all Let's Encrypt's HTTP-01 challenge needs — it does not require domain
ownership, only that the name resolves to a server you control. Sufficient for the small-private-beta
scope: invite links are shared manually (SMTP delivery deferred, follow-up 8), and neither Android App
Links nor iOS Universal Links are in scope yet (both need a domain you control DNS for, and the
custom-scheme deep link `de.sgart.app://invite` already works without one).

**Trade-off accepted:** netcup controls this DNS record, not us. It is tied to this specific VPS for
as long as it's rented — a server migration, or netcup changing its hostname-assignment scheme, breaks
the TLS cert and every place the hostname is configured (`sgart.invite.base-url`, the Keycloak issuer,
the app's release build config). A domain we own would be portable across hosts; revisit if SGART
moves off this VPS or needs Android App Links / iOS Universal Links (both require verified ownership
of the apex domain, which an assigned provider hostname cannot satisfy).

## Consequences

**Positive**

- In-budget (~8 €/month), available immediately, EU-located, AVV-backed — satisfies the GDPR
  provider requirement and the cost constraint together.
- x86/KVM with full root: Docker/Compose deployment of the existing stack is unchanged from local;
  no architecture-specific image work.

**Negative / constraints**

- **8 GB is tight for five services**; heap caps + swap are mandatory, and there is little
  head-room. If the stack outgrows it, the next step is a regular netcup VPS/RS tier (guaranteed
  resources) rather than this "Lite" (best-effort, shared-CPU) tier.
- **Location is not pinned to Germany** — it may land in Vienna or Amsterdam. Accepted: all three
  are EU. If a Germany-only requirement ever arises, a non-Lite netcup VPS with a fixed Nürnberg
  location is the fallback.
- SSD, not NVMe. Fine for the write load of a 2–4-person deployment.
- 3-month minimum term (not hourly-cancelable like Hetzner Cloud).

## Follow-ups (the "Production concretization" backlog)

These are our responsibility, not the provider's — the server + AVV is only the foundation:

1. **Sign the netcup AVV** in the Customer Control Panel before any personal data is processed —
   **DONE (2026-09-10).** Categories selected: data subjects — Kunden (app users/beta testers),
   Besucher der Website (the public `/invite` web-fallback page + reverse-proxy/SSH access logs);
   personal-data categories — Namensdaten, Kontakt- und Adressdaten, Logindaten, Daten zu
   Vorlieben und Verhaltensweisen (purchase history, per Rule 5), and **Foto- und Videodaten**
   (checked pre-emptively for the future receipt-scanning/OCR feature, so the AVV won't need
   amending when it ships). No special/sensitive categories (Art. 9) apply.
2. **Production Compose** that drops the DEV-only flags in `docker-compose.yml`
   (`KURRENTDB_INSECURE`, Keycloak `start-dev`), adds resource limits, and moves secrets out of a
   plain `.env`.
3. **Encryption at rest** (Rule 5): netcup does not encrypt disks by default — put the Postgres and
   KurrentDB data on a **LUKS-encrypted volume**. This also carries the ADR-0001 crypto-shredding
   key vault once that lands.
4. **TLS in transit**: a reverse proxy (Caddy or nginx/Traefik) with Let's Encrypt in front of the
   backend; **never expose KurrentDB / PostgreSQL / Keycloak** — internal to the Docker network
   only. The proxy must not buffer or short-timeout the SSE live-sync endpoint (Story 4.4).
   **Docker bypasses `ufw`**: a container's `ports:` mapping is wired via `iptables` directly and
   is reachable from the internet regardless of `ufw` rules (unlike the dev `docker-compose.yml`,
   which maps 5432/2113/8080 — fine locally, a GDPR-relevant exposure in prod). The production
   compose must publish **only** the reverse proxy (80/443) and Murmur (64738); Postgres,
   KurrentDB, and Keycloak get no `ports:` entry at all, reachable only over the internal Docker
   network.
5. **Encrypted EU backups** with a defined retention period (Rule 5 storage limitation).
6. **JVM heap caps** (backend + Keycloak) and **~2 GB swap** so the 8 GB holds.
7. **Server hardening**: SSH key-only ✅, `ufw` firewall (22/TCP, 80/TCP, 443/TCP,
   64738 TCP + UDP for Murmur, default-deny incoming) ✅, `fail2ban` ✅, automatic security
   updates still open.
8. **Android release signing keystore** — Timo-only manual step (generate + back up outside the
   repo), Gradle signing config wired and ready. See
   [`docs/android-release-signing.md`](../android-release-signing.md).
9. **Keycloak SMTP (registration/invite e-mails)**: Keycloak will send mail directly from this VPS.
   Before enabling it: remove netcup's default "netcup Mail Block" policy in SCP → Firewall (it
   blocks inbound/outbound SMTP by default); then set up SPF/DKIM/DMARC for the sending domain, or
   deliverability will suffer since the VPS IP has no mail reputation. Fallback if that proves
   unreliable: an external transactional e-mail relay (e.g. Mailgun/SendGrid free tier) instead of
   direct SMTP.
