# Story 7.5 — Invite by Code or Link; Retire the Email Invite Path: Architect Design Note

**Author:** Winston (System Architect) · **Date:** 2026-09-17 · **Status:** draft for Timo's review
**Feeds:** `create-story` 7.5 · **Scope:** the last build story of Epic 7 — replace the email/HMAC
invite path with a code+link invite the app can share, and delete the email machinery end to end.

**Ground truth:** `epics.md` §Epic 7 (Story 7.5) + the as-built forward-pointers on Stories 4.1/4.2,
`prd.md` FR2/FR15/FR-29, `CLAUDE.md` §5 (DSGVO/GDPR — household membership is personal data), §7
(dependency currency), `ARCHITECTURE-SPINE.md` (AD-5 identity ACL, AD-6 no-persisted-PII, AD-8
load-then-append, AD-10 invite is an entity of `Household`), `sprint-change-proposal-2026-09-13.md`
(rev E — the mandate to retire `EmailHmac`/`InviteEmailSideStore`), and the shipped `collaboration`
invite slice (`Household.invitePerson`/`acceptInvite`, `InvitePersonHandler`, `AcceptInviteHandler`,
`InviteController`, `InviteLinkFactory`, `DomainEventJsonCodec`) plus the app invite feature
(`invite_page`, `invites_view`, `invites_api`, `invite_link`, `accept_invite_cubit`,
`invite_deep_link_service`).

---

## 0. The one discovery that shrinks this story

The code-entry contract **already exists**. `app/lib/features/invites/data/invite_link.dart`'s
`InviteLink.tryParse` already accepts three forms — the canonical `https` link, a bare `h=..&i=..`
query string, **and a short `householdId:inviteId` colon form** — all built in Story 4.2/4.6. And
`Household.acceptInvite` already joins-without-duplicating when the caller is already a member
(the E5 no-op: it raises `InviteAccepted` but skips `MemberJoined`). So 7.5 needs **no new invite
aggregate, no code index, no resolve endpoint, and no already-member seam.** It is:

1. a **subtraction** — delete the email/HMAC path (command field, event field, domain check,
   hasher, side-store, identity lookup, three exceptions, a migration, and their tests); and
2. a **UI reshape** — the invite screen stops asking for an email and instead surfaces the code +
   link behind an OS share sheet, and the join screen gains a paste-a-code field.

Everything downstream of `POST .../invites` and `POST .../accept` stays on `(householdId, inviteId)`.

---

## Decisions proposed (confirm before create-story)

- **A — The "join code" IS the existing `householdId:inviteId` colon form.** Two representations of
  the same invite: the **link** (`InviteLinkFactory`, `<base-url>?h=<householdId>&i=<inviteId>`) and
  the **code** (`<householdId>:<inviteId>`, already parsed by `InviteLink.tryParse`). Both are
  bearer capabilities over opaque UUIDs — no PII (AD-6). Accept is unchanged. **KISS/YAGNI: no
  short human-memorable code**, which would require a new global `code → (household, invite)` index
  and a resolve endpoint for no beta-critical gain. *(See §5 for the trade-off if you want a
  friendly code instead.)*
- **B — Retire the email path by subtraction, now, in full.** `EmailHmac` and everything that only
  exists to serve it is deleted, not deprecated (CLAUDE.md §7: a deprecated path is a defect to
  fix). The immutable log keeps legacy `MemberInvited` events; the codec simply stops reading the
  `emailHmac` field (§3).
- **C — Already-a-member prevention moves to accept time, using existing domain state.** No new
  code: `Household.acceptInvite`'s E5 no-op already prevents duplicate membership. The 4.1
  invite-time `findHouseholdMemberByEmail` check is deleted with the rest of the email path.
- **D — Add `share_plus` (OS share sheet).** Not yet in `app/pubspec.yaml` (only `app_links`
  7.1.1). Add the current supported major (CLAUDE.md §7).
- **E — Retention obligation retires with the side-store.** Deleting `invite_email_side_store`
  removes the "purge raw email on accept/expiry/revoke" duty and the documented append-before-
  side-store compensation gap (`deferred-work.md`). Update the Story 6.3 retention note and
  `deferred-work.md` accordingly (§6).

---

## 1. Crux: retiring a field that lives in an immutable event log

`MemberInvited` carries `EmailHmac`, and past invites are already persisted in KurrentDB with that
field. AD-6 says no PII in the log — the HMAC was the privacy-preserving compromise — but rev E
mandates removing it entirely. We **cannot rewrite the log** (same principle as AD-7 erasure).

The resolution is that the HMAC was only ever *read* for one thing: the AC2 duplicate-pending
check in `Household.invitePerson`. That check is being retired. So no code needs the legacy value
on rehydrate — folding a legacy `MemberInvited` into `InviteState` just ignores it. Concretely:

- **New writes**: `MemberInvited` no longer has an `emailHmac` component; `MemberInvitedPayload`
  no longer serializes one.
- **Legacy reads**: `DomainEventJsonCodec` deserializes old `MemberInvited` JSON with Jackson
  configured to ignore the now-unknown `emailHmac` field (confirm the mapper's
  `FAIL_ON_UNKNOWN_PROPERTIES` is already off, or add `@JsonIgnoreProperties(ignoreUnknown = true)`
  to the payload — a one-line, forward-compatible tolerant-reader).

No event upcasting, no version bump. This is the cheapest correct evolution and keeps the
`NoPersistedPersonalDataTest` strictly happier (one fewer pseudonym in the log).

> **Tolerant-reader on `MemberInvitedPayload` is the whole migration story for the event log.**

---

## 2. Backend subtraction — the delete list

**Domain (`collaboration.domain`):**
- `Household.invitePerson(requestedBy, inviteId, now, commandId)` — drop the `EmailHmac` param,
  the duplicate-pending-by-HMAC loop, and `InviteState.emailHmac`. `InviteState` becomes
  `(invitedAt, status)`. The past-TTL lazy-expiry housekeeping (AC5) stays.
- `MemberInvited` — drop the `emailHmac` component + its null-check.
- **Delete** `EmailHmac.java`.

**Application (`collaboration.application`):**
- `InvitePerson` command — drop `emailHmac`.
- `InvitePersonHandler` — drop `InviteEmailHasher`, `InviteEmailSideStore`,
  `FindHouseholdMemberByEmail`, `NormalizedEmail`, the already-member check, the side-store write,
  and the `InviteExpired`→purge step. It shrinks to: resolve caller `MemberId` (403 if not a
  member) → load household (AD-8) → `invitePerson` → append → optional dev-log of the link.
- `AcceptInviteHandler` — drop `InviteEmailSideStore` and every `purge(...)` call (the expiry and
  success branches). The `ConsentGate` check, provision-then-persist-on-success, and the
  compensating `retract` all stay unchanged (7.4 + 4.2 behavior).
- **Delete** `InviteEmailHasher`, `InviteEmailSideStore`, `NormalizedEmail`, and the exceptions
  `InvalidInviteEmailApplicationException`, `AlreadyAHouseholdMemberApplicationException`,
  `DuplicatePendingInviteApplicationException` + domain `DuplicatePendingInviteException`.

**Identity (`identity.application`):**
- **Delete** `FindHouseholdMemberByEmail` (the only consumer was `InvitePersonHandler`). Confirm no
  other caller before deleting.

**Adapter out:**
- **Delete** `HmacSha256InviteEmailHasher`, `JdbcInviteEmailSideStore`.
- `DomainEventJsonCodec` — remove `EmailHmac` import; `MemberInvitedPayload` loses `emailHmac`
  (write side) and gains tolerant-reader (read side, §1). Update the `MemberInvited` write/read arms.
- `CollaborationApplicationConfig` — drop the deleted beans from the `InvitePersonHandler` /
  `AcceptInviteHandler` wiring and the `sgart.invite.hmac-secret` property.

**Adapter in:**
- `InviteController` — `InviteRequest` drops `email`; the `POST` no longer passes it. `GET`
  (`PendingInviteResponse`) already carries no email — unchanged.

**DB migration (`db/migration/V<next>__drop_invite_email_side_store.sql`):**
- `DROP TABLE invite_email_side_store;` Keep `invite_read_model` — it never held email.

**Config:** remove the per-deployment HMAC secret (`sgart.invite.hmac-secret`) from
`application*.yaml`, and delete `InviteEmailHmacSecretProfileGuardTest` (the guard that made the
secret mandatory outside dev).

---

## 3. Tests to delete / add / adjust

**Delete** (the retired path): `EmailHmacTest`, `HmacSha256InviteEmailHasherTest`,
`JdbcInviteEmailSideStoreTest`, `InviteEmailHmacSecretProfileGuardTest`,
`FindHouseholdMemberByEmailTest`, and the already-member/duplicate-pending/invalid-email cases in
`InvitePersonHandlerTest` + `HouseholdTest` + `InviteControllerTest`.

**Adjust**: `InvitePersonHandlerTest`, `HouseholdTest`, `InviteControllerTest`,
`DomainEventJsonCodecTest`, `AcceptInviteHandlerTest` to the slimmer signatures.

**Add** (behavior, not implementation — CLAUDE.md §6):
- `DomainEventJsonCodecTest`: **a legacy `MemberInvited` JSON with an `emailHmac` field
  deserializes** into the new event, ignoring it (the §1 guarantee — a regression test for the log).
- `HouseholdTest`: inviting the same household twice creates two independent pending invites (no
  duplicate-by-email rejection remains); accepting when already a member is the E5 no-op.
- `NoPersistedPersonalDataTest`: assert `MemberInvited` carries no email-derived field (tightens
  the existing guard).

**App tests** (§4): update `invites_api_test`, `invites_cubit_test`, `invite_page`/`invites_view`
widget tests; delete the email-validator test; keep `invite_link_test` (colon form already covered)
and extend it if the code-entry UI needs a new case.

---

## 4. App reshape (`app/lib/features/invites`)

**Generate/share (was: type an email):**
- `invites_view` / `invite_page`: replace the email `TextField` + validator with a **"create
  invite" action** → on success show the **code** (`householdId:inviteId`) and the **link**
  (built the same way the backend does), each with a **share** button (`share_plus`) and copy.
- `invites_api`: `POST .../invites` body drops `email`; keep the client-generated `inviteId` +
  `commandId` envelope (read-your-writes, unchanged). The pending-invite list is unchanged.
- **Delete** `invite_email_validator.dart` (+ its test).

**Join by code (extends 4.2's accept surface):**
- Add a **paste-a-code field** to the await-invite/join screen; feed the raw string through the
  existing `InviteLink.tryParse` → `AcceptInviteCubit` (which already calls `POST .../accept`).
  The deep-link path (`invite_deep_link_service`) is unchanged.

**Dependency:** add `share_plus` (current supported major) to `pubspec.yaml` (CLAUDE.md §7).

**Localization:** add de-DE strings for the create/share/paste surface; remove the email-entry and
"already a member"/"duplicate invite" copy tied to the retired 400/409s.

---

## 5. The trade-off if you want a *friendly* code instead (recommend: not for beta)

Decision A reuses the `householdId:inviteId` colon form — correct and PII-free, but ~73 characters,
so it is a **share-and-paste** code, not a **read-aloud** code. A short memorable code (e.g. 8
Crockford-base32 chars) would need: a new persisted `code → (householdId, inviteId)` index, a
collision/rotation policy, an unauthenticated `GET /api/v1/invites/resolve?code=…` endpoint (with
its own rate-limit, mirroring 7.1's provisioning endpoint), and expiry/consumption wired to the
same invite lifecycle. That is a meaningful new attack surface and a second source of truth for one
beta feature (YAGNI). **Recommendation:** ship the colon-form code + link + share sheet now; log a
"friendly join code" idea to the backlog if real-world testing shows share/paste is too clumsy.

> **Open question for Timo:** confirm A (share-and-paste colon code), or ask for the friendly-code
> variant — it roughly doubles the backend surface of this story.

---

## 6. GDPR / retention / docs housekeeping

- **Retention:** with the side-store gone, the "purge raw email on accept/expiry/revoke" duty and
  the append-before-side-store compensation gap disappear. Update the **Story 6.3** retention note
  (its example invite-email retention no longer applies) and remove the corresponding entry from
  `deferred-work.md`.
- **Data minimization (win):** the invite path now collects **no email at all** — strictly more
  privacy-preserving, satisfying the rev E mandate and FR-29's zero-required-PII goal.
- **As-built pointers:** after ship, add the "retired by 7.5" as-built note under Stories 4.1
  (email/HMAC path) and 4.2 (code-entry surface added), matching the epics.md forward-pointers.
- **`RevokeInviteHandler`:** drops its side-store purge; the revoke state transition is unchanged.

---

## 7. Definition of done (per CLAUDE.md §6 — name the suites)

- Backend: `./gradlew test` green **including ArchUnit** (`HexagonalArchitectureTest`,
  `NoPersistedPersonalDataTest`) — the layer rules are unaffected by pure deletion, but the run
  must be full, not a single module.
- App: `flutter test` **and** `flutter analyze` green.
- Report which suites ran (never "tests green" without naming them).

---

## 8. Sequenced work (for create-story to expand into tasks)

1. Domain: slim `Household.invitePerson` + `InviteState`; drop `EmailHmac` from `MemberInvited`;
   delete `EmailHmac`. (TDD: adjust `HouseholdTest` first.)
2. Codec: tolerant-reader for legacy `MemberInvited`; drop write-side `emailHmac`. (Regression test
   first.)
3. Application: slim `InvitePerson`, `InvitePersonHandler`, `AcceptInviteHandler`,
   `RevokeInviteHandler`; delete hasher/side-store/normalized-email/find-by-email + 3 exceptions.
4. Adapter/config/migration: `InviteController` DTO; drop beans + HMAC secret;
   `V<next>__drop_invite_email_side_store.sql`; delete profile-guard test.
5. App: reshape invite screen (create + share), add code-entry to join screen, drop email
   validator, add `share_plus`, de-DE strings.
6. Docs: 6.3 retention note, `deferred-work.md`, 4.1/4.2 as-built pointers.
7. Full suites (§7); then stop for the review gate (do **not** auto-run code review — ask first).
