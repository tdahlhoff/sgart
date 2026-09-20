# Manual Test Findings — 2026-09-20

Session goal: Timo drives the app on the emulator; capture findings, then shape into follow-up
stories/tasks. NOTHING is implemented until Timo says go (manual-test feedback batching rule).

**These findings became Epic 8 "Beta Hardening" (see epics.md). Finding → story map:**
F1 → Story 8.2 (session/silent device re-auth) · F3 → 8.3 (display name) · F7+F6 → 8.4 (single invite
code + discoverability) · F5 → 8.1 (manage-household crash) · F4 → 8.7 (hide selector on Profile) ·
F2 → 8.6 (SMTP + Mailpit) · recovery-token backlog item → 8.5 · doc-review C → 8.8 (projector throw) ·
doc-review E → 8.9 (provisioning tests). This file is the diagnostic backing (root causes, file:line,
cross-references) for those stories. NOTE (2026-09-20): epic numbers are stable IDs — Provisioning
stays Epic 7 (so "Story 7.x" refs below are the shipped provisioning stories); Beta Hardening = Epic 8.
Execution order 1→2→3→4→7→8→5→6.

Legend: [BETA] = must refine before beta · [LATER] = nice-to-have / backlog · [BUG] = defect

## Findings by screen

### F1 — List view → back → error flash → welcome screen  [BETA] [BUG] [HIGH-PRIORITY]
- **Screen(s):** app resume on List view; the screen reached via top back button; Welcome screen.
- **Observed:** App resumed on yesterday's List view (persisted route). Pressed top back button →
  previous screen showed an error message → then landed on Welcome screen with no error.
- **Likely cause:** stale session after infra restart (Keycloak `start-dev` = yesterday's
  refresh token no longer valid). Back-nav triggered a data fetch that failed auth → raw error
  flashed → app then routed to Welcome. Backend logs show no 401/5xx now (only deprecation warns).
- **Finding:** on resume/navigation with an invalid/expired session, the app flashes a raw error
  on the old screen before redirecting to Welcome. Clean behavior = detect dead session, route to
  Welcome silently (or show a friendly "please sign in again"), no raw error flash.
- **Relates to known open bug:** "no refresh-and-retry on 401 outside /me" — same seam.
- **Timo's requirement (explicit):** Do NOT show a "session expired" message and do NOT make the
  user click a "try again" button. Session recovery must happen **automatically in the background**
  (silent token refresh + retry of the failed request). A visible message + user action is
  reserved for **special cases only** — e.g. refresh genuinely fails / re-auth truly required —
  not the normal expired-token path.
- **Confirmed priority:** before-beta, fix very soon.
- **Second reproduction (rename household) — same root cause, expands scope:**
  - „Haushalt umbenennen" screen showed **„Deine Sitzung ist abgelaufen, melde dich neu an."**
    after the access token expired (~5 min TTL) while the user sat on the screen. Tapping
    „Umbenennen" repeatedly does nothing — each tap re-hits the same dead 401. This is the
    "no refresh-and-retry on 401 outside /me" bug on a **command/write** path (rename), not just GET.
  - **Dead-end UX:** the message tells the user to "log in again" but there is NO working action to
    do so on this screen — the user is stuck.
  - **Timo's requirement (strengthened):** the session should NOT expire while actively using the
    app — like Instagram, it should just keep working. Because SGART uses device-credential silent
    provisioning, the app holds the device key and can **re-authenticate silently and indefinitely
    on a trusted device**. So "never expires mid-use" is genuinely achievable, not just a nicety.
  - **Scope the F1 story must cover (consolidated):**
    (1) silent token refresh BEFORE/around expiry (proactive refresh, not only reactive on 401);
    (2) on any 401 (GET **and** commands/writes, app-wide — not just /me), transparently refresh +
        retry the original request; the user sees nothing;
    (3) failed user action (e.g. the rename) is auto-retried after refresh and completes;
    (4) NO „session expired" message and NO manual re-login button on the normal path;
    (5) reserve a visible re-auth prompt only for the genuine special case (silent refresh itself
        fails / device credential gone) — and THEN provide a working re-login action, not a dead end.
- **Answer to Timo's "is this covered by the saved task?":** YES — same task (F1). Now consolidated
  to explicitly include command/write paths, proactive refresh, and Instagram-like session longevity.

### F2 — Email recovery: no emails sent locally (expected) + local testability gap
- **Screen:** Account / email-recovery opt-in (attach recovery email).
- **Observed:** Timo added a recovery email; no email arrived.
- **Cause (confirmed, expected):** mail sending is behind `sgart.identity.mail.enabled=true`.
  Default = `DeferredSendRecoveryCodeEmail` (no-op; logs at debug, never the code — secret-safe).
  Dev `start.sh` doesn't set the mail flag. The opt-in itself works (email attached, code hashed
  + stored); only the send step is stubbed. NOT a bug — Story 7.3 design §3.1 / AC6.
- **Two follow-ups this surfaces — BOTH CONFIRMED IN SCOPE (Timo):**
  1. [BETA] **Real SMTP for beta.** Email recovery IS a beta feature. Services run at netcup for
     beta, so beta config sets `sgart.identity.mail.enabled=true` + real SMTP (server, from-address,
     credentials/secret). Ties into netcup deploy-readiness hardening (ADR-0002). Story must cover:
     SMTP config wiring, secret handling (no plaintext creds), and a beta smoke-test of a real send.
  2. [BETA / DEV-TOOLING] **Local mail catcher.** Add a dev-only mail catcher (Mailpit preferred —
     modern, SMTP + web UI) to docker-compose; point `mail.enabled=true` at it in the local dev
     profile so the recover-by-email flow is testable end-to-end on the emulator (read the code in
     Mailpit's web UI). Wire start.sh accordingly. Confirmed wanted now.

### F3 — User display name ("How do you want to be called?")  [BETA]
- **Screen:** Profile (header shows the raw credential id `9y8erzyb-jlr0gugblyd5_yxd8kivl4rz_khwxfqqwq`
  next to the "9" avatar). Also everywhere a person is named.
- **Observed:** the cryptic device-credential id is shown as the user's name. Not acceptable.
- **Want (Timo):** capture a human display name during first-run onboarding — a step like
  **"How do you want to be called?"** — save it, and use it as the visible name **throughout the
  app**, including activity/event messages, e.g. **"Timo added Milk to the list."**
- **Timo's framing:** yes it adds one step to first-run onboarding, but it's genuinely necessary and
  makes the experience far more personal. Accepted obstacle.
- **Scope this spans (for story breakdown):**
  - Onboarding: new display-name capture step in the first-run/provisioning flow (after silent
    provisioning). Decide: required or skippable-with-default? Editable later from Profile?
  - Backend/domain: store display name on the account/profile (Identity context). It's freely
    chosen → pseudonym-friendly (good for GDPR). Still **personal data**: purpose = social display;
    must be covered by erasure + export; validation (length, allowed chars, empty/whitespace).
  - Read path: resolve display name wherever a person is shown — Profile header, member lists,
    invites, and especially the collaboration activity messages ("<name> added <item>"). Today
    those have no human name to show; needs a name-resolution seam (likely via member mapping /
    account lookup) feeding the collaboration read models.
  - i18n: the "<name> added <item> to the list" message is templated (German UI) — name is a slot.
- **Decided (Timo):**
  - (a) **Required** at onboarding — cannot skip; must provide a name to proceed.
  - (b) **Editable later** from the Profile screen.
  - (c) **No uniqueness enforcement** — duplicate display names allowed. Two "Peter" in a household
    is the users' own problem to disambiguate (they'd name themselves "Peter 1"/"Peter 2" or
    "Peter"/"Peter junior"). App does NOT auto-dedupe or block duplicates.

### F4 — Household selector shown on Profile screen  [BETA]
- **Screen:** Profile — top app bar shows the household selector ("test ▾").
- **Observed:** the household dropdown appears on Profile.
- **Want (Timo):** it should NOT be visible on the Profile screen.
- **Rationale:** household selector is a household-scoped control (belongs on Listen / Einkauf,
  which are household-scoped); Profile is account/person-scoped, so the selector is out of place.
- **Likely fix:** the top-bar household selector is probably a shared app-shell/scaffold widget;
  hide it on the Profile tab (per-tab app-bar config) rather than app-wide.
- **Small/low-risk UI change.**

### F5 — "Haushalt verwalten" crashes (ProviderNotFoundException) → invites unreachable  [BETA] [BUG] [BLOCKER]
- **Screen:** Household switcher sheet (opened from the top-bar household selector) → tap
  „Haushalt verwalten".
- **Observed:** nothing happens on tap.
- **Root cause (confirmed in flutter.log):** `HouseholdSwitcherSheet._openManage`
  (`household_switcher_sheet.dart:96-97`) calls `context.read<StoresApi>()` +
  `context.read<StoreChainReferenceCache>()` BEFORE pushing the page. When the sheet is opened from
  the top-bar selector, those providers are NOT in scope above the sheet →
  `ProviderNotFoundException` thrown in the gesture handler → the `navigator.push` never runs → tap
  is a silent no-op. Sibling items work: `_openRename`/`_openCreate` use `_pushOverProviders`, which
  only reads `HouseholdsApi`/`HouseholdsCubit` (in scope). Only „verwalten" reaches for Stores
  providers that aren't there.
- **Blast radius:** `ManageHouseholdPage` is the management HUB — it hosts the „Einladen"
  (InvitePage) row AND the members row (`manage_household_page.dart:42-53`). So this crash makes
  **inviting others and managing members completely unreachable** from the UI.
- **This answers Timo's Q:** yes, invites live under „Haushalt verwalten" (the hub) — but that hub
  is currently unreachable.
- **Fix direction (for the story):** capture `StoresApi`/`StoreChainReferenceCache` the same
  provider-boundary way the other rows do, OR ensure those providers sit above the switcher sheet,
  OR have the hub obtain Stores deps at its own level. Recurring pitfall — code comments already
  cite "the Story 1.6 ProviderNotFoundException lesson"; regression test the manage path.
- **Regression test:** open switcher from the top bar → tap „Haushalt verwalten" → hub renders
  (widget test that reproduces the missing-provider scope). Boy-Scout: audit the other
  re-provide-across-root-navigator seams for the same gap.
- **Priority:** before-beta, blocker (invites are core to beta).

### F6 — Invite discoverability: "invite others" too hidden under „Haushalt verwalten"  [BETA] [UX]
- **Screen:** household switcher sheet / household management entry points.
- **Concern (Timo):** users likely won't find „Einladen" buried under a generic „Haushalt
  verwalten" label.
- **Options Timo raised (pick during UX/story):**
  1. Promote „Einladen"/invite to its own prominent, directly-visible menu item (or a visible
     action elsewhere in the app).
  2. OR keep the hub but add a **subtitle** to the „Haushalt verwalten" row describing its
     contents — „Mitglieder / Einladungen / Rechte" — in a smaller font under the title (the full
     string is too long for one row, so a two-line ListTile: title + subtitle).
- **Note:** subtitle wording should track what the hub actually contains today (currently Einladen +
  Mitglieder; „Rechte"/roles is still Epic 4 — don't advertise roles before they exist).
- **Design-led:** good candidate for a quick Sally (UX) pass before writing the story.
- **Depends on F5** being fixed first (hub must be reachable at all).

### F7 — Single household invite code, manually replaceable (retire the growing list)  [BETA] [DOMAIN]
- **Screen:** invite screen (reached via the hub — currently blocked by F5, so Timo recalled this
  from memory, couldn't re-open it live).
- **Concern (Timo):** the invite screen lists MULTIPLE invite codes and lets you create unlimited
  ones. Timo finds the accumulating list undesirable.
- **Want (Timo):** a **single active invite code per household**, shareable with multiple people,
  with the ability to **replace/rotate** it: rotating instantly invalidates the old code and issues
  a new one that must be re-shared. Keeps the "invalidate old code" security capability without an
  ever-growing list.
- **Feasibility (checked): YES — NOT tied to Keycloak.** Invites are a pure SGART **collaboration**
  domain concept, event-sourced, folded into the `Household` aggregate as invite state (like store
  state). Keycloak only handles identity/accounts; it never sees invite codes. So this is a
  domain-model decision we fully own — no external-system entanglement.
- **CLARIFIED (Timo): no automatic rotation, no automatic expiry.** The single code is valid
  **indefinitely, as long as it exists** — used by any number of people, any number of times. The
  ONLY way it changes is the **household admin manually clicking "replace"**: the old code vanishes
  (instantly unusable) and a new one is created to be used from then on. No rotate-on-use, no TTL.
- **What the story actually changes (semantic, not integration):**
  1. Collapse "list of pending invites" → "the ONE active invite code per household".
  2. Make accept **not consume** the code — many people join with the same code; it stays valid
     with NO change on use. (Today an invite is almost certainly single-use / consumed on first
     accept — this is the core change to verify.)
  3. **Drop the TTL auto-expiry** for this code (currently 7 days, `Invite.TIME_TO_LIVE`) — the
     household invite code does not expire on a timer; only manual replacement ends it.
  4. Manual **replace** action (admin only) = invalidate old (`InviteRevoked`) + issue new,
     atomically → old code instantly unusable, new code active from then on.
  5. UI: show the one current code + a „Code ersetzen"/replace button (admin) + share, instead of a
     list + unlimited create. Replacing requires no re-issue elsewhere — just re-share the new code.
- **Trade-off to note (Timo already reasoned about it):** a shared, reusable code is a lower
  security bar than per-person single-use codes; Timo judges it sufficient for SGART's household
  sharing context, mitigated by rotation. Story should state this decision explicitly.
- **GDPR upside:** a generic household code (not person-bound) stores less personal data and is
  simpler to reason about than per-invitee records (aligns with 7.5 code/link direction).
- **To verify during story design:** does `AcceptInvite`/`Household` currently consume/expire the
  invite on first accept? Does anything bind an invite to a specific invited identity? (7.5 made it
  code/link-based, so likely already not person-bound — confirm.)
- **DECIDED (Timo): only the household CREATOR can replace the code** (until real roles/„Rechte"
  land in Epic 4). Story enforces this in the domain (Household knows its creator) — a
  non-creator's replace attempt fails fast (application exception → adapter). UI hides/disables the
  replace button for non-creators.
- **Priority: before-beta (DECIDED).** Depends on F5 (need the screen reachable).

## === DOC-REVIEW CROSS-REFERENCES (2026-09-20) ===

### F1 ↔ already-shipped spec (BIG)
- `spec-auth-refresh-and-signout-fixes.md` (status: DONE 2026-09-13) ALREADY centralized 401
  refresh+retry into `AuthenticatedHttpClient` for ALL authenticated calls (not just /me), and
  DELIBERATELY added the „Deine Sitzung ist abgelaufen…" message for the refresh-ALSO-fails case.
- So the silent refresh+retry Timo wants EXISTS for the normal case (access token expired, refresh
  token still valid → transparent). The message Timo saw on rename = the DESIGNED refresh-also-fails
  path, triggered because the app resumed YESTERDAY's session after this morning's Keycloak
  `start-dev` restart → dead refresh token.
- **NEW scope F1 actually needs:** a fallback layer BENEATH the OAuth refresh — when the refresh
  token is dead, re-authenticate SILENTLY via the **device credential** (same Direct-Grant SPI as
  silent provisioning). The shipped spec gives up and shows the message; the device-credential model
  means the session can effectively NEVER expire on a trusted device. Suppress the expired-message
  except when even device re-auth fails.
- **Fold in two already-deferred F1-adjacent items:** (a) concurrent-401 refresh race
  (deferred-work "code review of spec-auth… 2026-09-13", needs in-flight-refresh de-dup guard);
  (b) `_FailurePage` in `first_run_router.dart` shows hardcoded generic error for ALL codes → even
  after fix, a households-bootstrap 401 won't show the right copy / path to re-auth.

### F3 ↔ AR5/NFR2 constraint (MUST respect)
- Architecture says: **NEVER persist display name/email in events** — resolved live from Keycloak/JWT
  (AR5/AD-6, NFR2). `auth_state.dart` already has a `displayName` field (Story 1.11) + `_IdentityHeader`
  renders it; with silent provisioning no name is set → the raw credential/subject id shows.
- So F3 = capture name at onboarding, store it as the **Keycloak user attribute/firstName** (NOT in
  SGART events), populate the existing `displayName`, and resolve MemberId→name for the „<name>
  added Milk" activity messages via the live Keycloak/JWT lookup seam (AR5-compliant). Story must NOT
  put the name into domain events.

### F2 ↔ Epic-7 beta scope + deferred hardening
- Real SMTP for beta already anticipated (Epic 7 mechanism note: "opt-in recover-by-email pulls in
  SMTP/SPF/DKIM"). Fold in deferred 7-3 items: no throttling on attach/requestRecoveryCode (rate
  limiting delegated to ADR-0002 gateway — not built), and attach-with-already-registered-email
  possible 500 + enumeration.

### Additional BEFORE-BETA items already tracked in docs (Timo to confirm inclusion):
- **A. Privacy-notice link + legal copy** (deferred-work, 7.4 review): consent screen uses
  placeholder `sgart.example/privacy`, non-tappable. Explicitly "Before beta: host the real notice,
  replace URL, wire a URL launcher, drop in legal copy." Consent cannot ship with a non-openable
  notice. → HARD before-beta.
- **B. Recovery-phrase reveal has no FLAG_SECURE / screen-capture guard** (deferred-work, 7.2
  review) [MEDIUM security]. (Note: F-recovery-phrase already replaces the 24-word phrase — coordinate.)
- **C. Projector double-`addStreamNamePrefix` latent throw** (deferred-work, 4.4 review): the first
  REAL live SSE subscription against KurrentDB would throw (`Filter type is already set`). Could bite
  in beta live-sync. Verify a follow-up ticket exists / fix the two projectors like 4.4 did.
- **D. [CRITICAL PATH] Epic-4 GDPR privacy tests** (sprint-status, epic 4, status: OPEN): de-link=
  erasure, no-PII-in-events, invite side-store purge points. (Also in memory as still-open.)
- **E. [CRITICAL PATH] Epic-7 test coverage** (sprint-status, epic 7, status: OPEN): ProvisionAccount
  state test, recover-by-email test, privacy tests (erasure/export incl. account+consent+email;
  retention sweep; email detach), rate-limit test on the unauthenticated provisioning endpoint.
  Epic 7 shipped WITHOUT its critical-path test coverage.

## Already-known open items (from memory, for cross-check — do not re-raise as new)
- Onboarding/login UI polish (first Android test)
- Browser-redirect login disliked (deliberate design choice)
- BUG: no refresh-and-retry on 401 outside /me (5-min token expiry)
- Fast-follow: replace 24-word recovery phrase with short token (already in backlog, commit f9b2849)
