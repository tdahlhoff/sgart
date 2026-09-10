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

1. **Sign the netcup AVV** in the Customer Control Panel before any personal data is processed.
2. **Production Compose** that drops the DEV-only flags in `docker-compose.yml`
   (`KURRENTDB_INSECURE`, Keycloak `start-dev`), adds resource limits, and moves secrets out of a
   plain `.env`.
3. **Encryption at rest** (Rule 5): netcup does not encrypt disks by default — put the Postgres and
   KurrentDB data on a **LUKS-encrypted volume**. This also carries the ADR-0001 crypto-shredding
   key vault once that lands.
4. **TLS in transit**: a reverse proxy (Caddy or nginx/Traefik) with Let's Encrypt in front of the
   backend; **never expose KurrentDB / PostgreSQL / Keycloak** — internal to the Docker network
   only. The proxy must not buffer or short-timeout the SSE live-sync endpoint (Story 4.4).
5. **Encrypted EU backups** with a defined retention period (Rule 5 storage limitation).
6. **JVM heap caps** (backend + Keycloak) and **~2 GB swap** so the 8 GB holds.
7. **Server hardening**: SSH key-only, firewall opening only **443/TCP** (HTTPS) and **64738
   TCP + UDP** (Murmur — confirm UDP is permitted), `fail2ban`, automatic security updates.
