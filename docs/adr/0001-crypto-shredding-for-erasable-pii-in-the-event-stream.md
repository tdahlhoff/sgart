# ADR-0001 — Crypto-shredding for erasable PII in the event stream

- **Status:** Accepted — deferred implementation, **hard gate before public/live release**
- **Date:** 2026-09-07
- **Deciders:** Timo Dahlhoff
- **Amends:** AD-7 (promotes crypto-shredding from a Post-MVP, receipts-only mechanism to a
  general one). Retires part of AD-6 (invite email side-store) and simplifies the AD-5 identity
  mapping's write path. Does **not** change AD-5's pseudonym rule itself.

## Context

SGART is event-sourced (KurrentDB as the write model, PostgreSQL projections as read models —
AD-4). The event log is append-only and never rewritten (AD-7). This creates a standing tension
with GDPR: personal data must be erasable, but you cannot erase a field from an immutable stream.

The MVP resolved that tension by **keeping raw PII out of the event stream entirely** and holding
it in out-of-band, mutable stores that can be purged on erasure:

- **AD-5** — events reference a person only by a household-scoped `MemberId` pseudonym; the
  Identity ACL owns a mutable mapping `{householdId, memberId → keycloakUserId}`, resolved per
  request.
- **AD-6** — the raw invite email never enters an event. `MemberInvited` carries
  `HMAC(secret, normalizedEmail)` for the no-duplicate-pending-invite invariant; the raw email
  lives only in a mutable `invite_email_side_store`, purged on accept/expiry/erasure.
- **AD-7** — erasure = destroy the ACL mapping rows + scrub read models + purge caches + delete
  the Keycloak account. Crypto-shredding was reserved *only* for content that is itself PII
  inside an event/blob (Post-MVP receipt images / OCR text), "settled when Price Intelligence is
  built."

### The problem this pattern actually causes

Because that PII is deliberately excluded from the events, the stores that hold it **cannot be
projections** — a projection can only contain what its events already carry. They are *inputs to*
events, not derivations of them. So every command handler that must write both the event stream
and one of these stores is a genuine **dual write across two technologies** (KurrentDB +
PostgreSQL), with no shared transaction — a `@Transactional` boundary cannot enlist a KurrentDB
append.

We have hit this twice, both at this same privacy seam:

- **Story 4.1 (`InvitePerson`)** — KurrentDB append + `InviteEmailSideStore` write.
- **Story 4.2 (`AcceptInvite`)** — KurrentDB append + Identity mapping `persist` + side-store
  purge. This required a hand-rolled compensation protocol (provision → persist-before-append →
  **retract on append failure** → purge-after-append) to avoid the "F1" household-access leak
  where a race-losing caller keeps a durable mapping.

Both handlers explicitly document the fully-atomic answer as *deferred*. Continuing on this path
means re-deriving bespoke compensation logic at every future PII-touching seam.

## Decision

**Adopt crypto-shredding as SGART's general mechanism for erasable personal data in the event
stream, and treat it as a blocking requirement before the public/live release.** This is the same
pattern used by Axon's Data Protection module (the reference implementation): PII fields are
encrypted at serialization time with a **per-data-subject data key** held in a separate key vault;
**erasure = destroying that subject's key**, which renders the ciphertext permanently unreadable
while the event bytes stay in place.

Concretely, once implemented:

1. **The invite email side-store is retired.** The email becomes a crypto-sharded field on the
   invite event. Erasure/expiry shreds the key; there is no separate `invite_email_side_store`
   table, no `purge` ordering, no append-before-purge invariant.
2. **The identity mapping becomes a rebuildable projection.** With the (crypto-sharded)
   `keycloakUserId` carried on `MemberJoined` / `InviteAccepted`, the `keycloakUserId → memberId`
   lookup is **derived by a projector** rather than written out-of-band by the handler.
   `AcceptInviteHandler` returns to the clean single-write path (append only); the
   provision/persist/retract compensation is deleted.
3. **`MemberId` (AD-5) is unchanged.** The pseudonym still prevents cross-household correlation and
   remains the domain's reference to a person. Crypto-shredding is orthogonal: it governs whether
   *raw* PII may enter the stream, not how the domain references people.
4. **Erasure (AD-7) gains a mechanism.** De-linking remains valid for orphaned `MemberId`s; for any
   raw PII now carried in-stream, erasure is key destruction. The two compose.

### What we do *now* (through beta)

**Nothing changes for beta.** We carry on with the current out-of-band stores and the 4.2
compensation. They are correct and tested; ripping them out now is not warranted while the pattern
is used at only two seams.

This is safe because **beta data will not be migrated into the live release** — the live event
streams start empty. There is therefore no crypto-shredding *migration* problem to solve later: no
existing production events need to be re-encrypted or back-filled. The only cost of deferral is
maintaining the two compensations until they are deleted.

### Why it must land before go-live (not later)

The moment the first real user's data enters an event stream we intend to **retain**, that data is
either crypto-shreddable or it is stranded in the immutable log with only de-linking for erasure.
Retrofitting crypto-shredding *after* go-live would require re-encrypting or rewriting live
history — exactly the migration problem this decision avoids. So the gate is: **crypto-shredding is
in place before any retained production stream exists.** Because no beta→live migration is planned,
implementing it any time before that cutover is equivalent in cost.

## Consequences

**Positive**

- Eliminates the dual-write / compensation class of bug at the PII seam; future PII-touching
  features append a single event instead of orchestrating two stores.
- Makes the Epic 6 right-to-erasure and data-export story dramatically simpler — erasure becomes
  "destroy the subject's key," which is a single, auditable operation instead of a scrub across
  N stores. **Crypto-shredding and Epic 6 are the same machinery**; build them together.
- Unifies the mechanism the architecture already anticipated for receipts (AD-7) with the identity
  seam, instead of maintaining two different erasure strategies.

**Negative / cost**

- We are **not on Axon**; there is no first-class KurrentDB equivalent. This is real
  infrastructure to build: a field-level encrypt/decrypt hook in event serialization, a
  per-subject **key vault** (its own durable, backed-up, access-controlled store), an erasure
  operation, and projection rebuild-after-shred semantics.
- The key vault becomes a new **critical store**: its durability and backups now carry the
  erasability guarantee, and shredding is irreversible by design. Losing a key = unreadable data;
  failing to shred = non-compliance.
- Encrypted PII in events cannot be queried or indexed without decryption — deterministic tokens
  (e.g. the AD-6 duplicate-check HMAC) stay necessary for invariants that must work pre-decrypt.

## Open questions (resolve during the pre-go-live spike)

1. **Key scoping.** Per-data-subject key (so shredding one person never affects another) —
   confirm the subject granularity (person vs. person-per-household, given AD-5's per-household
   `MemberId`).
2. **Duplicate-check HMAC interaction (AD-6).** The `HMAC(secret, normalizedEmail)` used for the
   no-duplicate-pending-invite invariant is a deterministic, email-derived token with a stable
   per-deployment secret. After a subject is crypto-shredded, that HMAC remains computable and
   linkable — decide whether erasure must also neutralize it (e.g. per-subject HMAC keying, or
   accepting it as an unlinkable pseudonym under Recital 26).
3. **Projection rebuild after shred.** Define what a projector does when it replays an event whose
   key is gone (tombstone the row / skip / render "erased") so replay-from-start stays correct
   post-erasure.
4. **Key-vault technology + operational posture** — where keys live, backup/rotation, access
   audit.
5. **DPO sign-off.** "Encrypted-at-rest-in-an-immutable-log, erased-by-key-destruction" is widely
   accepted as satisfying the right to erasure, but it is an interpretation and should be signed
   off explicitly rather than assumed.

## Follow-ups

- Amend the architecture spine: update **AD-7** and the **Deferred → Crypto-shredding
  implementation** line to reflect that crypto-shredding is now a general, pre-go-live mechanism,
  not a Post-MVP receipts-only one. (Not done in this ADR; proposed as a spine edit.)
- Schedule the crypto-shredding spike inside Epic 6 (erasure/export), since they share the
  machinery.
- On completion, delete the `invite_email_side_store` and the `AcceptInviteHandler`
  provision/persist/retract compensation, and convert the identity mapping to a projection.
