# Recovery-Email Ownership: Design Note

**Date:** 2026-10-01 · **Status:** draft for Timo's review · **Supersedes:** the deferred items "Attach with an already-registered email" (7.3), the 8.6 interim D2 (silent 202 on duplicate), and "recovery-email ownership model" in `deferred-work.md`.
**Decided with Timo in an interactive session (2026-10-01).** An early idea of scoping the address by household was dropped: the account is one per person across all its households. No code written yet.

## 1. Problem

Today an attached address is written onto the Keycloak account immediately, and the realm sets `duplicateEmailsAllowed: false`. That causes:

1. **Duplicate address:** attaching an address held by another account is a conflict. 8.6 papered over it with a silent 202 that sends nothing, so a legitimate second account waits forever for a code.
2. **Squatting:** an unconfirmed attach already claims the address, so a stranger can block the real owner.
3. **Lockout:** one throttle budget per target account, shared by attach and recover, lets anyone who knows an address burn the owner's budget for 24 h.
4. **Oracle:** any "taken" signal is a registration oracle, because provisioning is unauthenticated.

## 2. Decision: the address is an index, not a unique Keycloak attribute

Stop using Keycloak's email field. Keep a side-store (identity context, new migration):

```
recovery_email_binding {
  address_digest     text   -- HMAC-SHA256(pepper, normalizedAddress); NO plaintext address anywhere
  keycloak_user_id   text   -- pseudonymous account id
  address_hint       text   -- masked display form, e.g. "t***@gmail.com"
  confirmed_at       timestamptz null   -- null = pending, grants nothing
  PRIMARY KEY (address_digest, keycloak_user_id)
}
```

- **Multi-valued:** the same address may sit on several accounts. Duplicates are normal, so no conflict, no 409, no silent 202.
- **Only confirmed rows count.** Pending rows grant nothing and are never listed or used for recovery.
- **No plaintext at rest:** recovery works because the user types the address again; the code is mailed to what they typed. A database leak alone reveals no addresses.
- **The pepper** is a secret outside the database (same handling class as the existing code-hash key; rotated per deployment).
- Remove the Keycloak email write (`SetAccountEmail`) and `FindAccountByEmail`; the realm's duplicate-email setting becomes irrelevant.

Consequences: duplicates, squatting, and the registration oracle disappear at the root. An attacker attaching your address to their account only makes you receive a code mail they cannot use.

## 3. Flows

All three mails are plain text, with **no links** and no browser surface.

### 3.1 Attach and confirm (authenticated, on the person's device)

- `POST /api/v1/account/email {email}` → validate, throttle (§4), insert a **pending** binding, store a code hash (`ATTACH_CONFIRM`), mail the code. Always `202`.
- Mail content: the code, its TTL, "If you didn't request this, ignore this mail", and a contact line for removal or objection (GDPR). **No household or nickname info** (the household and nickname would be chosen by whoever triggered the mail, a phishing vector).
- `POST /api/v1/account/email/confirm {code}` → sets `confirmed_at`, stores `address_hint`. `204`.
- Detach: `DELETE /api/v1/account/email` removes the caller's binding. Detach never resets any throttle.

### 3.2 Recover (fresh device, throwaway account)

- `POST /api/v1/account/recovery/email {email}` → constant `202` whatever happens. If confirmed bindings exist for the digest and the recover budget allows, send **one mail with one code per address**. The mail says only that it is an SGART recovery code, the code, and its TTL. **No household names, nicknames, links, or "wasn't me" action** (Timo: the email belongs to the account, so the mail needs no household context).
- `POST /api/v1/account/recovery/email/confirm {email, code}` → verifies the code. If the digest maps to **one account** (the normal case), the existing R1 rebind runs immediately (delete the throwaway, set `username := U2`, `publicKey := K2`). If it maps to **several accounts** (shared mailbox, or an old account left by an earlier reinstall), the response lists them as "household name (your nickname)" and the app shows a picker; a second call with the chosen account id completes the rebind. Names are shown only after the mailbox is proven, never sent by email. Compensation behaviour from `8666e93` is unchanged.
- **Rebind restores the whole account** (all its households), as in 7.3.

### 3.3 "Wasn't me" link page: dropped

An unconfirmed attach grants nothing and expires with the code TTL (15 min), so a remove action has no practical effect. The permanent "never email this address" suppression list was also dropped (Timo): it needed an undo path for a rare edge case. This removes the link page, the signed single-use token, and the public endpoint. Repeat spam is limited by the per-address cap (§4).

## 4. Throttling: three separate budgets

| Budget | Keyed by | Limit | Notes |
|---|---|---|---|
| Attach, per caller | caller `KeycloakUserId` | ≥ 60 s apart, ≤ 5 / 24 h (as today) | protects the caller's own flow; over budget → `429` |
| Attach mails, per address | address digest | ≤ 3 / 24 h, then silent | stops inbox spam via free throwaway accounts; silent = constant 202 |
| Recover, per address | address digest | ≥ 60 s apart, ≤ 10 / 24 h (raised from 3 after review) | a successful confirm resets it; an already-issued valid code stays usable |

**Accepted trade-off:** a stranger can use up the per-address attach cap and delay the owner's own attach by up to 24 h. They cannot read anything or touch recovery, because the budgets are separate.
Code format, TTL (15 min), and the 5-attempt cap are unchanged. Throttles stay in-memory, keyed by digests and ids only (no PII); the proxy rate limit from ADR-0002 remains the outer layer.

**Timing side channel:** send mails **asynchronously** (hand off to a queue/executor, return `202` immediately) in both attach and recover. A known address then costs about the same as an unknown or capped one, so response time does not reveal whether an address is registered.

## 5. GDPR / data protection

- **Minimization:** no plaintext address stored; only a digest and a masked hint.
- **Purpose:** recovery only. The `address_hint` exists so the profile can show "recovery email attached: t***@…".
- **Erasure and export:** delete the account's `recovery_email_binding` rows alongside the Epic 6 erasure checklist (next to `account_consent`). Export includes the hint and `confirmed_at`, not a digest.
- **Retention:** pending rows are purged with the code TTL sweep (a pending row without a live code is deleted).
- **Non-user subject** (someone's address typed by a stranger): nothing is stored in plaintext, and the mail carries a contact line for objection.
- **Shared mailboxes:** accepted (Timo). Anyone with the mailbox can recover any account attached to it, which is inherent to email recovery. Say so in the privacy text.
- **Tests:** erasure, export, and pending-purge each get their own test; fixtures use `*@example.test` only.

## 6. Open forks

- **F1 (resolved, Timo 2026-10-01):** the recovery email belongs to the account, never to a household. One code per address; household name and nickname appear only in the in-app picker for the multi-account case.
- **F2: household names for the multi-account picker.** `identity` must not import `household` domain types. Proposal: a narrow out-port in `identity.application` implemented from `ListHouseholdsForCaller`/`ResolveMembershipNicknames`; check its shape before building. Only needed for the rare multi-account case.
- **Out of scope (Timo, 2026-10-01): merging accounts.** Accounts that share an address stay separate; recovery restores exactly one account per device, chosen in the picker (default: the account with the most households, changeable). Merging would re-key member ids (AD-5) and widen the shared-mailbox exposure.
- **F3: pepper provisioning** belongs with the beta-infra secrets work (ADR-0002).
- **F4: existing data:** there is no production data (beta never ran), so the Keycloak-email-to-binding migration is not needed. Drop Keycloak-email handling outright.

## 7. Scope and cost (rough)

Backend: new table and repository, `AttachRecoveryEmail`/`ConfirmRecoveryEmail`/`RequestEmailRecoveryCode`/`ConfirmEmailRecovery` rewritten onto the binding store, three throttles, mail templates, removal of the Keycloak email adapter methods. App: the profile shows the hint; the recovery screen needs an account picker, shown only when an address maps to several accounts (label = households and nicknames). Tests: domain and application unit tests first, then adapter and controller tests, then an end-to-end recovery test. Per CLAUDE.md, the full backend `./gradlew test` and the app's `flutter test` / `flutter analyze` run before reporting green.
