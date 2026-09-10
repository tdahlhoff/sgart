---
baseline_commit: bfc01134128d203d8314cdf302dd3126e1a94cd5
---

# Story 4.6: Invite-acceptance web fallback

Status: done

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As an invited person without the app installed,
I want the invite link to work through an OS deep link or a browser fallback,
so that I can join the household from wherever I open the link — even before installing SGART.

This is **Epic 4's sixth and final story** — it closes the loop that 4.1 (invite) and 4.2 (accept)
opened. Story 4.2 built the whole accept-and-join engine (`AcceptInvite` command/handler,
`InviteLink.tryParse`, the in-app Accept screen) and **deliberately deferred to 4.6** the three
*entry points* that route external invite links into that engine: the **OS deep link**, the
**browser web fallback**, and the **Keycloak email/redirect wiring** (plus the real Keycloak Admin
email→user lookup that 4.1 stubbed). 4.6 wires those entry points; it adds **no new membership
domain state** (state model §6: "4.4/4.5/4.6 consume these events/read models … no new membership
state").

## Context — what already exists vs. what 4.6 adds

**Already built (reuse verbatim, do NOT recreate):**
- Backend accept engine — `AcceptInvite` command + `AcceptInviteHandler` (provision→accept→persist→
  append→purge, all five branches), `POST /api/v1/households/{householdId}/invites/{inviteId}/accept`
  returning `200`, the 404/409/410 error advice (Story 4.2).
- `InviteLink.tryParse` (`app/lib/features/invites/data/invite_link.dart`) — parses the canonical
  `https`/query/colon forms; **kept pure specifically so 4.6's deep-link handler reuses it** (4.2
  decision 1). The canonical link is `.../invite?h=<householdId>&i=<inviteId>`.
- Flutter accept UI — `AcceptInviteCubit`/`AcceptInviteState`, the functional `AwaitInvitePage`
  accept screen, `InvitesApi.acceptInvite`, the four error codes in `error_message_resolver.dart`
  (Story 4.2).
- The `de.sgart.app` custom URL scheme is **already registered on both platforms** by
  `flutter_appauth` (Android `manifestPlaceholders["appAuthRedirectScheme"]`, iOS
  `CFBundleURLTypes`) — but only for its own OAuth redirect host `oauth/callback`.
- Keycloak realm `sgart-app` public client (PKCE) with a single redirect URI
  `de.sgart.app://oauth/callback` (`keycloak/realm-sgart.json`).
- Config pattern for "code the seam now, defer the live transport behind a flag": Story 4.5's
  `@ConditionalOnProperty` FCM gate (`LoggingContentFreePushSender` default) — 4.6 mirrors it for
  the Keycloak Admin lookup.

**4.6 adds:** an OS deep-link handler that routes `de.sgart.app://invite?...` into the existing
accept flow; a minimal static browser accept page; the real (config-gated) Keycloak Admin email→user
lookup; an invite-link factory + base-URL config; and the documented production-wiring follow-ups.

## Locked Decisions (Timo, 2026-09-10)

Production is **not deployed** (ADR-0002: provider/tier chosen, "deployment hardening still to be
built" — no domain, no TLS reverse proxy, no SMTP). So 4.6 builds every seam that is unit/
integration-testable now and documents the parts that require the live host + external credentials,
mirroring Story 4.5's D2 pattern. These decisions are binding and fold into the ACs and tasks.

- **D1 — Code the testable slice now; document prod-infra as manual follow-up.** Build the deep-link
  handler + routing, the static web-fallback page, the config-gated Keycloak Admin email→user
  lookup, and the invite-link factory. Document (in `docs/first-real-world-test.md`) the parts that
  need the production host: verified `https` App Links / Universal Links domain verification, SMTP
  invite-email delivery, the reverse-proxy `/invite` route, and the production Keycloak redirect
  URIs. **This story closes Epic 4.**
- **D2 — Deep link = custom scheme now, `https` App Links documented.** Route
  `de.sgart.app://invite?h=&i=` via the `app_links` package into the accept flow (dev/USB-testable
  now). Verified `https://<domain>/invite` Android App Links (`assetlinks.json`) and iOS Universal
  Links (`apple-app-site-association` + `associated-domains` entitlement) need the domain + release
  signing → documented follow-up only.
- **D3 — Web fallback = minimal static HTML+JS page, checked in.** A single vanilla accept-only page
  (Keycloak Authorization Code + PKCE in the browser → `POST …/accept`), served by the backend's
  static resources for dev and behind the reverse proxy in prod. **No Flutter-web target is added.**
  Honors NFR7 (fallback, never a daily client). Its production redirect wiring is documented.
- **D4 — Build the Keycloak Admin email→user lookup now (config-gated); document SMTP send.** Replace
  the always-empty `DeferredFindHouseholdMemberByEmail` with a real
  `KeycloakAdminFindHouseholdMemberByEmail` behind `@ConditionalOnProperty` (Deferred stays the
  default when disabled, so tests/CI/local dev need no admin credentials). This completes the 4.1 E5
  "email already belongs to a member" check. The actual invite-email **delivery** (SMTP/relay, needs
  the mail-unblocked VPS + SPF/DKIM) is documented as manual prod follow-up.

## Acceptance Criteria

From `epics.md#Story 4.6` (BDD), with the locked decisions applied. Where an AC's *end-to-end*
verification requires the production host, the **code seam** is the in-scope deliverable and the
**live verification** is the documented follow-up — each AC below says which.

1. **AC1 — OS deep link routes into the existing accept flow (code seam in scope).** Given the SGART
   app is installed, when the invitee opens an invite link `de.sgart.app://invite?h=<householdId>&i=<inviteId>`
   (cold start or already running), then the app parses it via `InviteLink.tryParse`, routes to the
   accept flow (Story 4.2), and — after Keycloak sign-in if needed — the invitee joins as
   `HouseholdRole.PARTICIPANT` via the **unchanged** `AcceptInvite` path. The deep link is
   **host-scoped to `invite`** so it never collides with `flutter_appauth`'s
   `de.sgart.app://oauth/callback`. A malformed/unparseable deep link is ignored (no crash, no
   spurious accept). *Verified now by unit/widget tests + the documented `adb`/manual step.*

2. **AC2 — Web fallback lands the same join outcome (page in scope; live redirect documented).**
   Given an invite link opened where the deep link can't resolve to the app, when the invitee follows
   the browser fallback page (`/invite?h=&i=`), then a **minimal, accept-only** page authenticates
   them via Keycloak (Authorization Code + PKCE) in the browser and calls
   `POST /api/v1/households/{h}/invites/{i}/accept` — the **same** join outcome as the deep link
   (FR2). The page shows the join / expired (410) / not-found (404) / already-used (409) / invalid
   outcomes in German, states the privacy basis, and joins as **„Mitglied"** (UX-DR20). *The page
   artifact + its served route are verified now (served with 200, references the accept endpoint,
   accept-only); the full browser PKCE round-trip is verified against a real Keycloak redirect URI in
   the documented follow-up.*

3. **AC3 — Both entry points land the identical outcome (invariant).** Given the deep-link path and
   the web path, when either redeems the same invite, then both call the identical backend accept
   contract and produce the identical result: `InviteAccepted` [+ `MemberJoined(PARTICIPANT)`],
   side-store purge, and the invite leaving the pending list — expired/consumed/unknown invites are
   rejected identically (410/409/404). Neither path introduces a second accept code path (DRY — both
   reuse Story 4.2's `AcceptInvite`).

4. **AC4 — Keycloak Admin email→user lookup completes the 4.1 already-member check (config-gated,
   in scope).** Given the Keycloak Admin lookup is enabled (`sgart.identity.keycloak-admin.enabled=true`),
   when a member invites an email that already belongs to a **current member** of the household, then
   `KeycloakAdminFindHouseholdMemberByEmail` resolves the email → `keycloakUserId` via the Keycloak
   Admin REST API and maps it → `MemberId` via the member mapping, so `InvitePersonHandler` rejects
   the invite (`409`, AC3/E5 of Story 4.1). When the lookup is **disabled** (default — tests, CI,
   local dev), `DeferredFindHouseholdMemberByEmail` stays wired and the check is a no-op (correct:
   no admin credentials required to build or run the suite). No email → empty; email resolves but the
   user is not a member of this household → empty.

5. **AC5 — Web is a fallback, never a daily client (NFR7).** Given the web fallback, when a person
   tries to use it for anything beyond invite acceptance, then **only** invite acceptance is
   supported — the page exposes no list/trip/household functionality and no navigation into the app's
   daily surfaces. No Flutter-web app is shipped (D3).

6. **AC6 — Invite link is generated from one configurable base URL (in scope).** Given an invite is
   created, when the canonical link is needed (by the documented email delivery, and for manual dev
   testing), then it is built by a single `InviteLinkFactory` from a configurable base URL
   (`sgart.invite.base-url`) as `<base-url>?h=<householdId>&i=<inviteId>` — the exact form
   `InviteLink.tryParse`/the web page/the deep link all consume. The link contains only opaque UUIDs
   (`householdId`, `inviteId`) — no PII (AD-6), so it may be logged at dev-time for manual testing.

7. **AC7 — Production wiring is documented, not lost (D1).** Given the parts that need the production
   host, when 4.6 completes, then `docs/first-real-world-test.md` documents, as reproducible manual
   steps: verified `https` App Links (`assetlinks.json`) + iOS Universal Links (AASA +
   `associated-domains`), SMTP invite-email delivery (per ADR-0002 §8, incl. the netcup mail-block
   removal + SPF/DKIM/DMARC) wired to the `InviteLinkFactory` seam, the reverse-proxy `/invite`
   route, the production Keycloak redirect URIs, and enabling the Keycloak Admin lookup. A dev
   "trigger the deep link" (`adb … VIEW -d de.sgart.app://invite?…`) snippet is included.

8. **AC8 — No regressions; full suites green.** Given the new deep-link handler, static page, and
   config-gated lookup, when the suites run, then the backend `./gradlew test` (incl. ArchUnit +
   Testcontainers) and `flutter test` + `flutter analyze` are all green, and the existing OAuth
   redirect (`de.sgart.app://oauth/callback`) still works (host-scoping regression).

## Tasks / Subtasks

> Each task lists its expected test(s) — see the **Test Manifest**. A `[x]` task with no matching
> test is an integrity failure (Epic-3 retro Action 2). TDD is the default (CLAUDE.md §6); keep the
> domain pure (AD-1, §8) — no new domain code is expected in this story. Names: no abbreviations
> (AD-11).

### Backend — Keycloak Admin email→user lookup (D4, AC4)

- [x] **T1. `KeycloakAdminFindHouseholdMemberByEmail` adapter** (AC4) — new
  `de.sgart.identity.adapter.out.KeycloakAdminFindHouseholdMemberByEmail implements
  FindHouseholdMemberByEmail`. It: (1) obtains a Keycloak Admin API access token via **client-credentials**
  (a confidential admin client / service account — never the app's public client); (2) `GET
  {base-url}/admin/realms/{realm}/users?email={email}&exact=true`; (3) if exactly one user is found,
  takes its `keycloakUserId` (the `id` field) and resolves `(keycloakUserId, householdId) → MemberId`
  via `MemberMappingRepository` (already injected in Identity); returns `Optional.empty()` for
  no-user / not-a-member-of-this-household. Use Spring Boot 4.1's `RestClient` (not `RestTemplate`/
  `WebClient`) for the HTTP calls. Keep all Keycloak/HTTP types inside this adapter (AD-1/AD-2 — the
  port stays a plain `(String, HouseholdId) → Optional<MemberId>`).
  - [x] `KeycloakAdminFindHouseholdMemberByEmailTest` — with a stubbed HTTP transport
    (`MockRestServiceServer` over the `RestClient`, or a small injectable HTTP seam): email→one user
    who **is** a member → returns that `MemberId`; email→one user who is **not** a member of the
    household → empty; email→**no** user → empty; token-fetch/lookup call shapes asserted (path,
    `email`+`exact=true` query, bearer token). **No live Keycloak** — do not add a Keycloak
    Testcontainer for this (KISS; the container cost isn't justified for one lookup — stub the HTTP).
- [x] **T2. Config-gated bean wiring + config keys** (AC4, D4) — in `IdentityBeansConfig`, make the
  `FindHouseholdMemberByEmail` bean **conditional**: `KeycloakAdminFindHouseholdMemberByEmail` when
  `sgart.identity.keycloak-admin.enabled=true`, else the existing `DeferredFindHouseholdMemberByEmail`
  (default). Mirror Story 4.5's `@ConditionalOnProperty` FCM gate exactly (default false everywhere).
  Add to `application.yaml`: `sgart.identity.keycloak-admin.{enabled,base-url,realm,client-id,client-secret}`
  with `${SGART_IDENTITY_KEYCLOAK_ADMIN_*}` env overrides and safe dev defaults (enabled=false;
  base-url `http://localhost:8080`; realm `sgart`). **No secret default** for `client-secret` outside
  dev (fail-fast if enabled without it — mirror the invite HMAC secret's profile guard rationale).
  - [x] `IdentityBeansConfig`/context test — default profile wires `DeferredFindHouseholdMemberByEmail`
    (assert bean type) so the suite needs no admin credentials; with the property set, the Keycloak
    adapter is selected. Mirror the existing FCM-conditional test if one exists.
- [x] **T3. Correct the `DeferredFindHouseholdMemberByEmail` javadoc** (fix-rigor, Epic-3 Action 4) —
  it currently says the real lookup "ships in Story 4.6"; 4.6 is now. Reword: it is the **fallback**
  used when `sgart.identity.keycloak-admin.enabled=false`; the real lookup is
  `KeycloakAdminFindHouseholdMemberByEmail`. No stale forward-reference left behind. Also update the
  `FindHouseholdMemberByEmail` port javadoc ("ships in Stories 4.2/4.6") to name the real adapter.

### Backend — invite link factory + base URL (AC6)

- [x] **T4. `InviteLinkFactory` + `sgart.invite.base-url`** (AC6) — a small
  `de.sgart.collaboration.application.InviteLinkFactory` (or `adapter.out`, whichever keeps the port
  boundaries clean — it produces a URL string from ids, no domain rule) that builds
  `<base-url>?h=<householdId>&i=<inviteId>` — the exact form the deep link, the web page, and
  `InviteLink.tryParse` consume. Add `sgart.invite.base-url` to `application.yaml`
  (`${SGART_INVITE_BASE_URL:http://localhost:8081/invite}`). Wire it into `InvitePersonHandler` so
  that, after a successful invite append, the constructed link is available to the (documented) email
  delivery seam and is **logged at INFO in the dev profile only** for manual testing (the link is
  opaque UUIDs, not PII — AD-6; guard the logging so prod doesn't log links by default). Keep this
  minimal — one class, one config key, one call site (KISS/YAGNI; do not build an email sender here,
  D4).
  - [x] `InviteLinkFactoryTest` — builds the canonical `?h=&i=` form from a known base URL;
    round-trips through `InviteLink.tryParse` semantics conceptually (documented: the Dart parser
    consumes exactly this shape). `InvitePersonHandlerTest` — the existing tests stay green; add/extend
    one asserting the factory is invoked on the success path (no PII in the produced/logged value).

### Backend — static web-fallback page (D3, AC2, AC5)

- [x] **T5. Minimal accept-only web page + served route** (AC2, AC5) — add
  `backend/src/main/resources/static/invite/index.html` (+ a small inline or co-located `.js`/`.css`;
  no external CDN — bundle it). The page: reads `h`/`i` from its own query string; runs Keycloak
  **Authorization Code + PKCE** against the `sgart-app` public client (or a dedicated web client, see
  T6) redirecting back to `/invite`; on return, `POST /api/v1/households/{h}/invites/{i}/accept` with
  the bearer token + a generated `commandId`; renders join / 410 / 404 / 409 / invalid-link outcomes
  in **German** (reuse the four Story 4.2 error meanings); states the privacy basis and "als
  Mitglied beitreten" (UX-DR20); is **accept-only** — no other app functionality (AC5). Add a
  `WebMvcConfigurer` view controller (or equivalent) mapping the clean path `/invite` →
  `forward:/invite/index.html` so the canonical `.../invite?h=&i=` URL serves the page (Spring static
  otherwise needs `/invite/`). Ensure the security config **permits** unauthenticated `GET /invite`
  and the static asset (it must load before the user has a token).
  - [x] `InviteWebFallbackControllerTest` / MockMvc — `GET /invite` returns `200` `text/html`; the
    served page body references the accept endpoint path and is accept-only (no links to daily
    surfaces); `GET /invite` is reachable **without** authentication (security permit). (The
    in-browser PKCE JS is not unit-tested in the Java stack — keep the JS minimal and note this;
    full round-trip is the documented follow-up, AC7.)
- [x] **T6. Keycloak realm redirect wiring for the web fallback (dev) + documented prod** (AC2, AC7) —
  in `keycloak/realm-sgart.json`, add the **dev** web redirect URI `http://localhost:8081/invite` (and
  the matching `webOrigins` entry for the token endpoint CORS) to the `sgart-app` client — or add a
  dedicated `sgart-web-invite` public PKCE client if keeping the native and web redirect sets
  separate reads cleaner (recommend a dedicated client: it isolates web origins from the native
  app). Do **not** remove the existing `de.sgart.app://oauth/callback`. The **production** domain
  redirect URI(s) + web origins are documented in T9 (need the real domain).
  - [x] Covered by the realm file diff + the T9 doc; if a realm-shape test exists, extend it, else
    assert via the served-page + doc (no automated Keycloak realm test in this repo today — note it).

### Client — Flutter deep-link handling (D2, AC1, AC3)

- [x] **T7. `app_links` dependency + `InviteDeepLinkService`** (AC1) — add `app_links` at the current
  version supported by Flutter 3.44 / Dart `^3.12.2` (CLAUDE.md §7 — verify latest; pin it). Add
  `app/lib/shared/deeplinks/invite_deep_link_service.dart` (mirror the `shared/sync`, `shared/push`
  structure) that exposes: the **initial** link (cold start, `AppLinks().getInitialLink()`) and a
  **stream** of subsequent links (`AppLinks().uriLinkStream`), filters to invite links
  (**host `invite`** only — ignore the `oauth` callback host so `flutter_appauth` keeps it), and
  parses each via the existing `InviteLink.tryParse`, emitting parsed `InviteLink`s (malformed →
  dropped). Keep it behind an abstract port with a fake for tests (mirror the `PushNotifications`
  port pattern from 4.5 for optional/nullable injection).
  - [x] `invite_deep_link_service_test.dart` — an incoming `de.sgart.app://invite?h=..&i=..` emits the
    parsed `InviteLink`; a `de.sgart.app://oauth/callback` link is **ignored** (host-scoping); a
    malformed invite link is dropped (no emit, no throw); the cold-start initial link is surfaced.
- [x] **T8. Android + iOS custom-scheme registration + routing into the accept flow** (AC1, AC3) —
  Android: add a **second** `intent-filter` on `MainActivity` (`AndroidManifest.xml`) with
  `action.VIEW` + `category.DEFAULT` + `category.BROWSABLE`, `<data android:scheme="de.sgart.app"
  android:host="invite"/>` (host-scoped, so it does not swallow `flutter_appauth`'s
  redirect-receiver filter). iOS: the `de.sgart.app` scheme is already in `CFBundleURLTypes` —
  `app_links` catches it; verify no Info.plist change is needed beyond confirming the scheme.
  Wire `InviteDeepLinkService` into app startup (near `main.dart`/the auth gate): on a parsed invite
  link, route to the **existing** Story 4.2 accept flow — pre-fill/hand the `InviteLink` to
  `AcceptInviteCubit`/`AwaitInvitePage`; if the user is not signed in, route through sign-in first,
  then to accept (reuse `auth_gate`/`first_run_router`/`households_cubit` seams — no new accept
  logic, DRY/AC3). Guard re-entrancy so one link triggers one accept.
  - [x] Widget/cubit test — an emitted invite `InviteLink` drives the accept flow (the accept screen
    opens pre-filled / the accept intent fires); signed-out → sign-in-then-accept routing; the OAuth
    callback link never routes to accept (regression for AC8). Keep the manifest/plist change verified
    by the documented `adb` step (T9) — native intent resolution isn't unit-testable in `flutter
    test`.

### Docs, dependency currency, full build (D1, AC7, AC8)

- [x] **T9. Document the production-only wiring** (AC7) — extend `docs/first-real-world-test.md` (a
  new section, mirroring the 4.5 "Background push (D2)" section) with reproducible manual steps for:
  (a) **verified `https` App Links** — host `assetlinks.json` at `https://<domain>/.well-known/`
  with the release-signing SHA-256, add the `https`/`autoVerify` intent filter; **iOS Universal
  Links** — host `apple-app-site-association`, add the `associated-domains` entitlement; (b) **SMTP
  invite-email delivery** — Keycloak SMTP per ADR-0002 §8 (remove the netcup mail block, SPF/DKIM/
  DMARC) *or* a transactional relay, sending the `InviteLinkFactory` link (name the seam); (c) the
  **reverse-proxy `/invite` route** to the backend static page; (d) **production Keycloak redirect
  URIs**/web client for the real domain; (e) enabling the **Keycloak Admin lookup**
  (`SGART_IDENTITY_KEYCLOAK_ADMIN_ENABLED=true` + the confidential admin client's creds). Include a
  dev **"trigger the deep link"** snippet: `adb shell am start -a android.intent.action.VIEW -d
  "de.sgart.app://invite?h=<householdId>&i=<inviteId>" de.sgart.app`, and how to obtain the link from
  the dev-profile INFO log (T4).
- [x] **T10. Dependency currency + full green build** (AC8, CLAUDE.md §7) — verify `app_links` is the
  latest version supported by the repo's Flutter/Dart constraint; flag any other outdated deps noticed
  (CI action majors, Gradle wrapper, Spring Boot, `kurrentdb-client`, Testcontainers, JUnit, pub
  packages). Run the **complete** backend suite `./gradlew test` (incl. ArchUnit + Testcontainers)
  **and** `flutter test` + `flutter analyze`; report which suites ran and their counts (a partial run
  is not green — [[backend-test-hygiene]]).

### Definition of Done (standing, per retros)

- [x] Full suites green **and named**: backend `./gradlew test` (incl. ArchUnit + Testcontainers)
  **and** `flutter test` + `flutter analyze` (CLAUDE.md §6; [[backend-test-hygiene]]). Report which
  ran + counts.
- [x] `commandId` lifecycle correct on any client accept path reused here; error advice unchanged and
  still mapped/tested (the 4.2 404/409/410 seam is reused, not modified); a11y labels on any new/
  changed interactive widget; **no dead code/strings/stale comments** (T3 stale-javadoc fix);
  client fail-fast on malformed links (deep-link handler drops them). (Epic-1 DoD.)
- [x] Config-gated new adapter defaults **off**, so tests/CI/local dev need no external credentials
  (the 4.5 D2 precedent); no secret has a committed non-dev default.
- [x] The existing OAuth redirect still works (host-scoping regression, AC8) — verified by the
  deep-link host-scoping test + noted in the manual `adb` step.
- [x] Every `[x]` task has its Test-Manifest test actually present (Epic-3 Action 2).
- [ ] **This story closes Epic 4** — after review, run the Epic 4 retrospective (see
  [[epic-4-retro-notes]] for items already queued).

## Dev Notes

### Ground truth — read these before coding
- **Story 4.2** (`_bmad-output/implementation-artifacts/4-2-accept-an-invite-and-join.md`) — the
  accept engine 4.6 routes into. Decision 1 there *explicitly parks* the deep-link/web-fallback/
  Keycloak-redirect wiring in 4.6 and the real Keycloak email lookup in 4.6; the `InviteLink` parser
  was kept pure "so 4.6's OS deep-link handler reuses [tryParse] unchanged". **Do not build a second
  accept path** (AC3, DRY).
- **Membership state model** (`_bmad-output/planning-artifacts/epic-4-membership-state-model.md`) —
  §4 "invitee opens invite link (deep-link 4.2, or web-fallback 4.6 — **same outcome**)"; §7 "4.4/4.5/
  4.6 consume these events/read models … **no new membership state**". 4.6 adds no events/commands/
  aggregates/read models/migrations.
- **Architecture spine** (`.../ARCHITECTURE-SPINE.md`) — AD-1/§8 (domain pure, hexagonal — the new
  Keycloak/HTTP types live only in `adapter.out`; ArchUnit `HexagonalArchitectureTest` must stay
  green), AD-2 (Collaboration reaches Identity only through the published `FindHouseholdMemberByEmail`
  port — already so), AD-5/AD-6 (no PII in events/logs; the invite link is opaque UUIDs, loggable;
  the raw email lives only in the side-store), AD-11 (no abbreviations). The spine's Deferred list
  parks exactly this: "Keycloak realm/client config + invite deep-link/web-fallback flow".
- **ADR-0002** (`docs/adr/0002-…`) — prod host reality (no domain/TLS/SMTP yet; §8 the Keycloak SMTP
  plan + netcup mail-block caveat). This is *why* D1 splits code-now from documented-follow-up.
- **UX-DR20** (`epics.md:141`) — the recipient acceptance screen content: who invited you + which
  household, "join as Mitglied", privacy stated, a calm decline. Applies to the web page (AC2) and is
  already realized in the in-app accept screen (4.2).

### Patterns to mirror (exact files)
- **Config-gated adapter with a default fallback** ← Story 4.5's `FcmContentFreePushSender`
  (`@ConditionalOnProperty`, default `LoggingContentFreePushSender`) + its config in `application.yaml`
  (`sgart.push.fcm.enabled`). T2 mirrors this for `sgart.identity.keycloak-admin.enabled`.
- **Identity ACL port + bean wiring** ← `identity/adapter/out/IdentityBeansConfig.java` (the
  `findHouseholdMemberByEmail()` bean), `identity/application/FindHouseholdMemberByEmail.java` (the
  port), `identity/domain/MemberMappingRepository.java` (the `(keycloakUserId, householdId)→MemberId`
  lookup the new adapter composes with — it already backs `ResolveMemberIdentity`).
- **RestClient HTTP adapter** ← Spring Boot 4.1 `RestClient` (there is no existing Keycloak Admin/HTTP
  client in the repo — this is the first; keep it isolated in `adapter.out`).
- **Custom-scheme redirect already registered** ← `app/android/app/build.gradle.kts`
  (`appAuthRedirectScheme=de.sgart.app`), `app/ios/Runner/Info.plist` (`CFBundleURLTypes`),
  `app/lib/features/auth/data/keycloak_config.dart` (`de.sgart.app://oauth/callback`). The new invite
  filter uses the **same scheme, host `invite`** — the host is what keeps the two from colliding.
- **Optional/nullable port injection on the client** ← Story 4.5's `PushNotifications` port +
  `AuthCubit`/`HouseholdShell` guarded-optional wiring (avoids churning unrelated test call sites);
  `InviteDeepLinkService` follows the same shape.
- **Client accept reuse** ← `features/invites/data/invite_link.dart` (`tryParse`),
  `features/invites/presentation/accept_invite_cubit.dart`, `features/households/presentation/
  await_invite_page.dart`, `households_cubit.dart` (`bootstrap()` to route in after join),
  `features/auth/presentation/auth_gate.dart` (sign-in gating).

### The deep-link ↔ OAuth collision (the crux to get right — AC1/AC8)
`flutter_appauth` registers a redirect receiver for the whole `de.sgart.app` scheme. Adding an invite
filter on the same scheme is safe **only** because it is **host-scoped to `invite`**
(`de.sgart.app://invite?…`) while OAuth uses host `oauth` (`de.sgart.app://oauth/callback`). Android
routes an incoming URI to the filter whose `scheme`+`host` match, so the two do not fight. The
`InviteDeepLinkService` must **also** filter by `uri.host == 'invite'` in Dart, so even if
`app_links` surfaces the OAuth callback it is ignored (never fed to `tryParse`/accept). Test both
directions (T7). If, in practice, the two receivers still contend on a device, the documented
fallback is a distinct scheme/host for invites — note it but do not pre-build it (YAGNI).

### Web fallback specifics (D3, AC2/AC5)
- **Accept-only, minimal, bundled** — no external CDN/script (works offline of third parties; NFR7).
  Vanilla HTML+JS is deliberate over a Flutter-web target (D3): far smaller, trivially "fallback-only",
  nothing to keep in sync with the app's daily surfaces.
- **PKCE in the browser** against the `sgart-app` (or dedicated `sgart-web-invite`) public client;
  redirect back to `/invite`. The page needs the Keycloak issuer/authorize/token URLs, the client id,
  and the accept API base — inject them at build/serve time (a small templated `<script>` block or a
  tiny `GET /invite/config.json`), do **not** hard-code a production URL into a committed static file
  (keep it env-driven like the rest of the config). Keep secrets out — it is a public PKCE client.
- **Served by the backend static resources** for dev (Spring serves `static/**`); the view controller
  gives the clean `/invite` path. Security config must **permit** unauthenticated `GET /invite`
  + assets. In prod the reverse proxy routes `/invite` to the same page (documented, T9).
- The full browser round-trip needs a real redirect URI Keycloak trusts; for the real domain that is
  documented follow-up (T9). Dev testing uses the `http://localhost:8081/invite` redirect added in T6.

### Keycloak Admin lookup specifics (D4, AC4)
- **Client-credentials, confidential admin client** — never the app's public client, never a user
  password. The lookup token is a service-account token with `view-users` on the realm. Keep the
  client secret in config (env), no committed non-dev default (T2).
- **`?email=&exact=true`** to avoid substring matches; treat 0 or >1 results as "unknown" (empty).
  Map the found user's `id` (that is the `keycloakUserId` / JWT `sub`) → `MemberId` via
  `MemberMappingRepository`. A user who exists in Keycloak but has no mapping for this household is
  **not** a member → empty (correct: they can still be invited).
- **Default off** — the whole suite, CI, and local dev keep `DeferredFindHouseholdMemberByEmail`
  (empty) so no Keycloak admin credentials are needed to build/test. Enable only where the real
  lookup is wanted (documented). This is the honest, tested seam; the *behavioral* effect (rejecting
  an already-member invite) is unit-tested via the stubbed-HTTP adapter test, not a live Keycloak.

### GDPR / privacy (CLAUDE.md §5, AD-5/AD-6)
- **The invite link is not PII** — `householdId` + `inviteId` are opaque UUIDs; the invited email is
  never in the link (it stays in the side-store, AD-6). So logging the link in dev for manual testing
  is acceptable; still guard it to the dev profile (don't log links by default in prod). The web page
  and deep link carry the same opaque ids.
- **The Keycloak Admin lookup touches a raw email** (the invite address) — but only transiently, to
  resolve membership; it persists nothing new (no read model, no event). Same lawful basis as 4.1's
  already-member check. No PII enters an event or a Collaboration read model (AD-5/AD-6 unchanged).
- **No new personal data is stored** by 4.6 — no migration, no new table, no new column.

### Scope guards (KISS / YAGNI — CLAUDE.md §1)
- **No new domain code** — no event, command, aggregate method, read model, projector, or codec
  change (state model §7). If you find yourself editing `Household.java`, stop — 4.6 is entry-point
  wiring over the 4.2 engine.
- **No migration** — V17 is the latest; 4.6 adds none (verify by reading `db/migration/`). The
  Keycloak lookup uses the existing `identity_member_mapping` table via `MemberMappingRepository`.
- **No email sender in code** (D4) — delivery is documented (SMTP/relay needs external creds). Build
  only the `InviteLinkFactory` seam it will plug into.
- **No Flutter-web app** (D3) — a static page only.
- **No Keycloak Testcontainer** for the lookup (stub the HTTP) — the container cost isn't justified
  for one call (KISS); revisit only if a broader Keycloak-integration need appears.

### Previous-work intelligence (Stories 4.2 & 4.5 — read their Dev Agent Records)
- **4.2 review scars relevant here:** the accept engine had a hard-won access-control fix (provision→
  persist-on-success→append→compensate-on-conflict) — **do not touch it**; 4.6 only *routes into*
  `AcceptInviteHandler` unchanged. Reuse `InviteLink.tryParse` exactly (it already hardened malformed
  percent-escapes). The client error codes are `invite.invalidLink/.expired/.notFound/.alreadyUsed`
  (singular `invite.` — 4.2 fixed a plural typo); reuse them.
- **4.5 D2 precedent (the template for D1/D4):** ship the port/seam + a hermetic default now, gate the
  live transport behind `@ConditionalOnProperty` default-off, and document the manual credential
  wiring in `docs/first-real-world-test.md`. 4.6's Keycloak Admin lookup and the prod deep-link/SMTP
  wiring follow this exactly. Also from 4.5: prefer nullable/optional client-side injection to avoid
  churning unrelated test call sites.
- **Error-advice contract test** was retired in 4.1 (distributed per-endpoint coverage kept) — 4.6
  reuses the 4.2 accept endpoint's existing advice; no new advice, no contract test needed.

### Testing standards (CLAUDE.md §6)
- Keycloak Admin adapter → unit test with stubbed HTTP (`MockRestServiceServer`), no live Keycloak.
- Bean-gating → context/config test (default = Deferred; property-set = Keycloak adapter).
- `InviteLinkFactory` → pure unit test.
- Web page → MockMvc served-route test (200/text-html/accept-only/permit-all); the in-browser JS is
  out of the Java suite's reach — keep it minimal, verify E2E in the documented step.
- Flutter deep-link → unit test the service (parse/host-scope/malformed/initial-link) + a widget/cubit
  test that a link drives the accept flow and the OAuth host is ignored.
- Native intent resolution + full browser PKCE → the documented manual steps (AC7), not automated.
- **Green = full suite for every touched module** (backend `./gradlew test` incl. ArchUnit +
  Testcontainers **and** `flutter test` + `flutter analyze`).

## Test Manifest (task → named test)

| Task | Test(s) |
|---|---|
| T1 | `KeycloakAdminFindHouseholdMemberByEmailTest` (member→MemberId · non-member user→empty · no user→empty · call shapes: `?email=&exact=true` + bearer) |
| T2 | `IdentityBeansConfig`/context test (default→`DeferredFindHouseholdMemberByEmail`; property-set→Keycloak adapter) |
| T3 | *(doc/javadoc fix — verified by no stale forward-reference; no runtime test)* |
| T4 | `InviteLinkFactoryTest` (canonical `?h=&i=` form) · `InvitePersonHandlerTest` (factory invoked on success · no PII logged/produced · existing cases stay green) |
| T5 | `InviteWebFallbackControllerTest` (GET `/invite`→200 text/html · references accept endpoint · accept-only · permitted unauthenticated) |
| T6 | *(realm-file diff + T9 doc; no automated Keycloak realm test in repo — noted)* |
| T7 | `invite_deep_link_service_test.dart` (invite link→parsed emit · oauth host ignored · malformed dropped · cold-start initial link) |
| T8 | deep-link routing widget/cubit test (link drives accept flow · signed-out→sign-in-then-accept · oauth link never accepts) + documented `adb` step |
| T9 | *(documentation — verified by presence of the manual steps in `first-real-world-test.md`)* |
| T10 | full-suite run (backend `./gradlew test` + `flutter test`/`analyze`) with named counts |

## Project Structure Notes

- **New backend files:** `identity/adapter/out/KeycloakAdminFindHouseholdMemberByEmail.java` (+ any
  small HTTP-config/RestClient bean it needs), `collaboration/application/InviteLinkFactory.java` (or
  `adapter.out` if it reads config directly), `adapter/in/InviteWebFallbackController.java` (or a
  `WebMvcConfigurer`) for the `/invite` view route, `src/main/resources/static/invite/index.html`
  (+ bundled JS/CSS). Tests alongside each.
- **Modified backend files:** `identity/adapter/out/IdentityBeansConfig.java` (conditional bean),
  `identity/adapter/out/DeferredFindHouseholdMemberByEmail.java` + `identity/application/
  FindHouseholdMemberByEmail.java` (javadoc), `collaboration/application/command/InvitePersonHandler.java`
  (invoke `InviteLinkFactory` on success; dev-only link log), `src/main/resources/application.yaml`
  (`sgart.identity.keycloak-admin.*`, `sgart.invite.base-url`), the security config (permit
  `GET /invite` + assets), and `keycloak/realm-sgart.json` (dev web redirect URI / dedicated web
  client).
- **No new migration** (V17 stays latest; the lookup reuses `identity_member_mapping`).
- **New/modified client files:** `pubspec.yaml` (+`app_links`), `app/lib/shared/deeplinks/
  invite_deep_link_service.dart` (+ a fake for tests), `app/android/app/src/main/AndroidManifest.xml`
  (host-scoped `invite` intent filter), the app-startup/auth-gate wiring that routes a parsed link
  into the 4.2 accept flow, and their tests. iOS: verify `CFBundleURLTypes` already covers the scheme
  (no change expected).
- **Docs:** `docs/first-real-world-test.md` (new "Invite deep link + web fallback (4.6)" section).
- **ArchUnit:** the new Keycloak/HTTP types live only in `identity.adapter.out`; the port stays a
  plain `(String, HouseholdId)→Optional<MemberId>` (AD-2). `HexagonalArchitectureTest` must stay
  green unchanged.

## References

- [Source: `_bmad-output/planning-artifacts/epics.md#Story 4.6: Invite-acceptance web fallback`] — ACs.
- [Source: `_bmad-output/planning-artifacts/epics.md:141`] — UX-DR20 recipient acceptance content.
- [Source: `_bmad-output/planning-artifacts/epic-4-membership-state-model.md` §4 (deep-link 4.2 / web-fallback 4.6 = same outcome), §7 (4.6 consumes; no new membership state)].
- [Source: `_bmad-output/implementation-artifacts/4-2-accept-an-invite-and-join.md`] — the accept engine + decision 1 (deep-link/web-fallback/Keycloak → 4.6) + `InviteLink` reuse.
- [Source: `_bmad-output/implementation-artifacts/4-5-content-free-notifications.md`] — the D2 `@ConditionalOnProperty` default-off + documented-follow-up pattern mirrored by D1/D4.
- [Source: `.../architecture/architecture-sgart-2026-08-20/ARCHITECTURE-SPINE.md#AD-1, AD-2, AD-5, AD-6, AD-11, Deferred(Keycloak realm/client config + invite deep-link/web-fallback flow)`].
- [Source: `docs/adr/0002-production-hosting-on-netcup-vps.md` §Follow-ups (SMTP §8, TLS reverse proxy §4)].
- [Source: `backend/.../identity/application/FindHouseholdMemberByEmail.java`, `identity/adapter/out/{DeferredFindHouseholdMemberByEmail,IdentityBeansConfig}.java`, `identity/domain/MemberMappingRepository.java`, `collaboration/application/command/InvitePersonHandler.java`, `src/main/resources/application.yaml`, `keycloak/realm-sgart.json`].
- [Source: `app/lib/features/invites/data/invite_link.dart`, `app/lib/features/invites/presentation/accept_invite_cubit.dart`, `app/lib/features/households/presentation/await_invite_page.dart`, `app/lib/features/auth/data/keycloak_config.dart`, `app/android/app/build.gradle.kts`, `app/ios/Runner/Info.plist`, `app/pubspec.yaml`].
- [Source: `docs/first-real-world-test.md` (the 4.5 D2 documented-follow-up section this story extends)].
- [Source: `CLAUDE.md#1 Clean Code`, `#5 DSGVO/GDPR`, `#6 Testing`, `#7 Dependency currency`, `#8 Package structure`].

## Questions for Timo (non-blocking — sensible defaults chosen)

1. **Dedicated web PKCE client vs. reusing `sgart-app`.** Default = **add a dedicated
   `sgart-web-invite` public PKCE client** for the browser fallback (isolates web origins/redirects
   from the native app; cleaner than mixing a web redirect into the native client). Say the word to
   reuse `sgart-app` with an added redirect URI instead.
2. **Invite-link logging in dev.** Default = log the constructed invite link at **INFO under the dev
   profile only** (opaque UUIDs, not PII — AD-6) so manual end-to-end testing has a link without
   SMTP. Prefer no logging at all (obtain the link another way)? Say so.
3. **Distinct invite scheme/host if the OAuth receiver contends.** Default = **host-scope on the
   existing `de.sgart.app` scheme** (`//invite`), which should not collide with the `//oauth`
   callback. If a device shows contention in the manual test, the fallback is a distinct scheme —
   noted but not pre-built (YAGNI).

## Dev Agent Record

### Agent Model Used

Claude Sonnet 5 (claude-sonnet-5)

### Debug Log References

None — no HALT conditions hit; all ten tasks completed in one pass, full backend + Flutter suites
green throughout.

### Completion Notes List

- **T1–T3 (Keycloak Admin lookup, D4, AC4):** `KeycloakAdminFindHouseholdMemberByEmail` — client-
  credentials token fetch + `?email=&exact=true` admin lookup via Spring Boot 4.1 `RestClient`,
  stubbed with `MockRestServiceServer` (no live Keycloak, no Testcontainer per the scope guard).
  Config-gated in `IdentityBeansConfig` (`@ConditionalOnProperty` + `@ConditionalOnMissingBean`
  mirroring 4.5's FCM gate exactly); `DeferredFindHouseholdMemberByEmail` stays the default. Fixed
  the stale "ships in 4.6" javadoc on both the port and the deferred stub.
- **T4 (`InviteLinkFactory`, AC6):** one class, one config key (`sgart.invite.base-url`), one call
  site in `InvitePersonHandler` — builds the canonical `?h=&i=` link on the success path and logs it
  at INFO only under the `dev` profile (`Environment.acceptsProfiles(Profiles.of("dev"))`, computed
  once at bean-wiring time, not re-checked per request).
- **T5–T6 (web fallback, D3, AC2/AC5):** a single bundled `static/invite/index.html` (vanilla HTML/
  CSS/JS, no CDN) served at the clean `/invite` path by `InviteWebFallbackController`, plus a
  `/invite/config.json` endpoint injecting the Keycloak authorize/token URLs and client id at serve
  time (env-driven, never hard-coded into the committed file). Reachable unauthenticated because
  `/invite` falls outside `SecurityConfig`'s `/api/v1/**` gate — no security-config change needed.
  Added a dedicated `sgart-web-invite` public PKCE client (Q1's default) to the dev realm seed, plus
  a `sgart-admin` confidential client (client-credentials, `view-users` realm-management role) so D4
  is manually testable against local Keycloak too.
- **T7–T8 (Flutter deep link, D2, AC1/AC3/AC8):** `app_links: ^7.1.1` (resolved 7.2.1 — verified
  current, Flutter 3.24+/Dart 3.5+ minimum, well within this repo's 3.44.9/^3.12.2). Host-scoped
  `invite` intent-filter added to `MainActivity` alongside the existing (library-contributed) OAuth
  one; iOS needed no change (the scheme is already registered, `app_links` + the Dart-side host
  filter do the rest). `InviteDeepLinkService` is constructor-injectable (fake `Uri` stream/initial-
  link function in tests) rather than a separate abstract-port + fake pair — simpler for an
  equivalent test seam (KISS). New `PendingInviteLinkCubit` (held above `AuthGate` in `main.dart`)
  survives the sign-out→sign-in transition; `FirstRunRouterBody`'s new
  `_PendingInviteLinkRouter` consumes it exactly once — covering both the "already pending at mount"
  case (signed-out→sign-in-then-accept) a plain `BlocListener` would miss, and the "offered while
  already mounted" case. `AwaitInvitePage` gained an optional `initialLink` that pre-fills the field
  and auto-triggers the existing `AcceptInviteCubit.accept()` — the identical 4.2 path, no second
  accept code path (AC3). Extracted `openAwaitInvitePage()` so `CreateOrAwaitChoicePage`'s manual
  "I have an invite" choice and the new deep-link routing share one call site (DRY).
- **T9 (docs, AC7):** extended `docs/first-real-world-test.md` with the `adb` trigger snippet, and
  the App Links/Universal Links, reverse-proxy, production Keycloak redirect, SMTP delivery, and
  Keycloak Admin lookup follow-ups — mirroring 4.5's D2 section.
- **T10 (dependency currency + full green build, AC8):** `app_links` confirmed latest supported
  (7.2.1). Noticed but **not** bumped (out of scope, no major-version lag, KISS/YAGNI): `dio`
  5.11.0→5.11.1, `flutter_appauth` 12.0.2→12.1.0 (both minor/patch) — flagging per CLAUDE.md §7 for
  a future story to pick up. Backend `./gradlew test` (incl. ArchUnit + Testcontainers): **953/0**
  (up from the 4.5 baseline of 945/0 — +8 new tests). Flutter `flutter test` + `flutter analyze`:
  **641/0**, analyze clean.
- **Scope guards honored:** no new domain event/command/read model/migration (verified — V17 stays
  latest); no email sender built (D4 documents SMTP only); no Flutter-web target; no Keycloak
  Testcontainer.

### File List

**Backend — new:**
- `backend/src/main/java/de/sgart/identity/adapter/out/KeycloakAdminFindHouseholdMemberByEmail.java`
- `backend/src/main/java/de/sgart/collaboration/application/InviteLinkFactory.java`
- `backend/src/main/java/de/sgart/collaboration/adapter/in/InviteWebFallbackController.java`
- `backend/src/main/resources/static/invite/index.html`
- `backend/src/test/java/de/sgart/identity/adapter/out/KeycloakAdminFindHouseholdMemberByEmailTest.java`
- `backend/src/test/java/de/sgart/identity/adapter/out/IdentityBeansConfigTest.java`
- `backend/src/test/java/de/sgart/collaboration/application/InviteLinkFactoryTest.java`
- `backend/src/test/java/de/sgart/collaboration/adapter/in/InviteWebFallbackControllerTest.java`

**Backend — modified:**
- `backend/src/main/java/de/sgart/identity/adapter/out/IdentityBeansConfig.java`
- `backend/src/main/java/de/sgart/identity/adapter/out/DeferredFindHouseholdMemberByEmail.java`
- `backend/src/main/java/de/sgart/identity/application/FindHouseholdMemberByEmail.java`
- `backend/src/main/java/de/sgart/collaboration/application/command/InvitePersonHandler.java`
- `backend/src/main/java/de/sgart/collaboration/adapter/out/CollaborationApplicationConfig.java`
- `backend/src/main/resources/application.yaml`
- `backend/src/test/java/de/sgart/collaboration/application/InvitePersonHandlerTest.java`
- `keycloak/realm-sgart.json`

**Client — new:**
- `app/lib/shared/deeplinks/invite_deep_link_service.dart`
- `app/lib/features/invites/presentation/pending_invite_link_cubit.dart`
- `app/test/shared/deeplinks/invite_deep_link_service_test.dart`
- `app/test/features/invites/presentation/pending_invite_link_cubit_test.dart`

**Client — modified:**
- `app/pubspec.yaml` (+`app_links`)
- `app/android/app/src/main/AndroidManifest.xml`
- `app/lib/main.dart`
- `app/lib/features/households/presentation/await_invite_page.dart`
- `app/lib/features/households/presentation/create_or_await_choice_page.dart`
- `app/lib/features/households/presentation/first_run_router.dart`
- `app/test/features/households/presentation/await_invite_page_test.dart`
- `app/test/features/households/presentation/first_run_router_test.dart`

**Docs — modified:**
- `docs/first-real-world-test.md`

**Sprint tracking — modified:**
- `_bmad-output/implementation-artifacts/sprint-status.yaml`

### Review Findings

_Code review 2026-09-10 (bmad-code-review, Opus 4.8; Blind Hunter + Edge Case Hunter + Acceptance Auditor). 2 decisions resolved (Timo), 9 findings kept, 13 dismissed as noise._

- [x] [Review][Patch] Web fallback: add explicit confirm/decline before accept (Decision 1, UX-DR20) [backend/src/main/resources/static/invite/index.html] — FIXED: on return from Keycloak the page now shows „Beitreten"/„Ablehnen" and accepts only on the explicit tap (`promptConfirm`); no gesture-free auto-accept. Inviter/household stay hidden (§7).
- [x] [Review][Patch] Web fallback OAuth-error redirect loops forever [backend/src/main/resources/static/invite/index.html] — FIXED: `run()` now short-circuits on an `error` query param with „Die Anmeldung wurde abgebrochen oder ist fehlgeschlagen." instead of re-entering `beginSignIn`.
- [x] [Review][Patch] Keycloak email query param not URL-encoded → plus-addressed emails bypass the already-a-member guard [backend/src/main/java/de/sgart/identity/adapter/out/KeycloakAdminFindHouseholdMemberByEmail.java] — FIXED: email is now passed as a `{email}` URI variable (percent-encoded on the wire); added `forHousehold_emailContainsAPlusSign_...` test.
- [x] [Review][Patch] Invite link built unconditionally then discarded in prod [backend/src/main/java/de/sgart/collaboration/application/command/InvitePersonHandler.java] — FIXED: `buildLink` moved inside the dev-logging `if`; added `...WithoutDevLinkLoggingDoesNotBuildTheInviteLink` and reworked the no-PII test to the dev-logging path.
- [x] [Review][Patch] `/invite/config.json` endpoint untested [backend/src/test/java/de/sgart/collaboration/adapter/in/InviteWebFallbackControllerTest.java] — FIXED: added `getInviteConfigJson_...` MockMvc test (200, unauthenticated, authorize/token URL suffixes + non-empty clientId).
- [x] [Review][Patch] `@ConditionalOnMissingBean` in a user `@Configuration` is order-fragile [backend/src/main/java/de/sgart/identity/adapter/out/IdentityBeansConfig.java] — FIXED: the deferred bean now uses `@ConditionalOnProperty(... havingValue="false", matchIfMissing=true)`, removing the declaration-order dependency.
- [x] [Review][Patch] Deep-link init has no async error handler [app/lib/main.dart] — FIXED: `initialInviteLink()` got a `catchError` and the stream `.listen` an `onError`, both reporting via `FlutterError.reportError`.
- [x] [Review][Dismiss] Keycloak Admin lookup aborts the invite on HTTP error (Decision 2) — DISMISSED per Timo: fail-fast is intended (refuse the invite when membership can't be verified); lookup is default-off (prod-only).
- [x] [Review][Defer] `InviteDeepLinkService` host filter will drop the documented `https` App Links [app/lib/shared/deeplinks/invite_deep_link_service.dart:49] — deferred, pre-existing scope. `uri.host != 'invite'` drops the canonical `https://<domain>/invite?...` links that `InviteLink.tryParse` supports and T9 documents wiring later. Consistent with the current custom-scheme-only scope; widen the filter when the T9 https App Links are wired.
- [x] [Review][Defer] Lossy `InviteLink` → colon-form → re-parse round-trip [app/lib/features/households/presentation/await_invite_page.dart:68] — deferred, not reachable with production data. `_rawFormOf` re-flattens the structured link to `h:i` and `AcceptInviteCubit.accept` re-parses it; a `:` in either id would break it, but real ids are UUIDs. Cleaner: hand the `InviteLink` through instead of re-serializing (Boy-Scout, small cubit-API change).

## Change Log

| Date | Change |
|---|---|
| 2026-09-10 | Story drafted (ready-for-dev). Locked decisions D1–D4 (Timo): code the testable slice (deep-link handler + static web page + config-gated Keycloak Admin lookup + invite-link factory), document prod-infra follow-ups. Epic 4's final story. |
| 2026-09-10 | Implemented T1–T10: Keycloak Admin lookup (config-gated), `InviteLinkFactory`, static web-fallback page + route, Keycloak realm dev clients, Flutter deep-link service + pending-link routing, docs, dependency currency. Backend 953/0 (incl. ArchUnit + Testcontainers), Flutter 641/0 + analyze clean. Status → review. |
| 2026-09-10 | Code review (bmad-code-review, Opus 4.8; Blind Hunter + Edge Case Hunter + Acceptance Auditor). 2 decisions resolved (D1 → confirm/decline patch; D2 → fail-fast, dismissed), 7 patches applied, 2 deferred (https App-Link host filter; colon round-trip — see deferred-work.md), 13 dismissed. Backend 956/0 (incl. ArchUnit + Testcontainers), Flutter 641/0 + analyze clean. Status → done. Next: Epic 4 retrospective. |
