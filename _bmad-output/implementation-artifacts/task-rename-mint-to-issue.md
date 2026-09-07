# Task: remove the "mint" metaphor — `issue` (authority) + `generate` (client) — before Story 4.3

**Type:** naming refactor (names + javadoc prose only — **no behavior change**). **Model target:** Sonnet 5,
medium effort. **Do this BEFORE `dev-story` 4.3** so 4.3's new `RetractMembership` port is written against the
new name.

**Why:** `mint` (coin-minting metaphor) is obscure and hard for non-native speakers. Replace it with the plain,
standard words — and eliminate it from `backend/src` entirely (AD-11 ubiquitous language; CLAUDE.md §2).
The word maps to **two** meanings, which take **two different** replacements:

## Bucket A — authority *issues* a `MemberId` → `issue` / `issuer`

The Identity ACL is the sole authority that creates+records a `MemberId` (like a passport office issues an ID,
a CA issues a certificate). Rename symbol + prose:

| Old | New |
|---|---|
| class `MintMemberIdentity` (+ `.java` file) | `IssueMemberIdentity` |
| method `mint(keycloakUserId, householdId)` | `issue(keycloakUserId, householdId)` |
| test `MintMemberIdentityTest` (+ file) | `IssueMemberIdentityTest` |
| bean method / fields / params `mintMemberIdentity` | `issueMemberIdentity` |
| prose: "sole minter", "mint the … MemberId", "ACL-minted", "Identity-ACL-minted", "mint-then-append", "post-mint", "idempotent mint", "a silent mint", "never mints or writes", "the mint/write path", "newly minted (id/mapping)" | "sole issuer", "issue the … MemberId", "ACL-issued", "Identity-ACL-issued", "issue-then-append", "post-issue", "idempotent issue", "a silent issue", "never issues or writes", "the issue/write path", "newly issued (id/mapping)" |

**Keep unchanged** (already good): `provision`, `persist`, `retract`, `ProvisionedMemberId`,
`freshlyProvisioned`. Only the `mint()` convenience (provision+persist) becomes `issue()`.

**Bucket-A files:** `identity/application/IssueMemberIdentity.java` (rename),
`identity/application/{ResolveMemberIdentity,ProvisionedMemberId,NotAMemberException}.java`,
`identity/domain/MemberMappingRepository.java`, `identity/adapter/out/{IdentityBeansConfig,DeferredFindHouseholdMemberByEmail}.java`,
`identity/package-info.java`, `shared/MemberId.java`,
`collaboration/application/command/{CreateHouseholdHandler,AcceptInviteHandler}.java`,
`collaboration/application/query/ListMyHouseholds.java`,
`collaboration/domain/Household.java` (factory javadoc + "Identity-ACL-minted joiner"),
`collaboration/domain/event/{MemberJoined,InviteAccepted}.java` ("Identity-ACL-minted MemberId"),
`collaboration/adapter/in/InviteController.java` (line ~68 "the handler mints the joiner's MemberId"),
`collaboration/adapter/out/CollaborationApplicationConfig.java` (imports + bean params for
`createHouseholdHandler`/`acceptInviteHandler`).
**Tests:** `IssueMemberIdentityTest` (rename), `CreateHouseholdHandlerTest`, `AcceptInviteHandlerTest`,
and any other test referencing the type / `.mint(` (`HouseholdControllerTest`, `ResolveMemberIdentityTest`,
`JdbcMemberMappingRepositoryTest`, `DomainEventJsonCodecTest`, `HouseholdTest`).

## Bucket B — client (or create) *generates* its own id → `generate` / `client-generated`

A client generating a random UUID for its own `storeId`/`itemId`/`listId`/`tripId`/`inviteId` is not an
authority "issuing" anything — the plain word is **generate**. Prose only (no symbols to rename):

| Old | New |
|---|---|
| "client-minted", "minted client-side" | "client-generated", "generated client-side" |
| "the client minted the {…Id}", "the client mints a fresh {…Id}" | "the client generated the {…Id}", "the client generates a fresh {…Id}" |
| "the id it minted" | "the id it generated" |
| "minting duplicates" (HouseholdId — retried create) | "generating duplicates" |

**Bucket-B files:** `shared/{ShoppingListId,TripId,StoreId,ItemId,HouseholdId,InviteId}.java`,
`collaboration/application/command/{StartTrip,InvitePerson,AddItem,CreateShoppingList,CreateShoppingListHandler,AddStore,AddStoreHandler}.java`,
`collaboration/adapter/in/{InviteController,ShoppingListController,TripController,ItemController,StoreController}.java`
(the "client minted the …Id" DTO/response comments),
`collaboration/adapter/out/ShoppingListReadModelProjector.java`,
`collaboration/domain/readmodel/ItemSuggestionView.java`.
(Note: `InviteController` and `CreateHouseholdHandler` contain **both** buckets — the ACL-issues-MemberId
lines are Bucket A, the client-generated-inviteId/listId lines are Bucket B.)

## Docs (ubiquitous-language sources — AD-11)

Update, applying the A/B split as appropriate:
- `_bmad-output/planning-artifacts/architecture/architecture-sgart-2026-08-20/ARCHITECTURE-SPINE.md` (AD-5
  "sole minter" → "sole issuer"; any client-side "minted" → "generated").
- `_bmad-output/specs/spec-sgart/glossary.md` (the mint/MemberId entry).
- `_bmad-output/implementation-artifacts/4-3-membership-roles-governance.md` (references to
  `MintMemberIdentity`/"mint"/"sole minter" → `IssueMemberIdentity`/"issue"/"sole issuer"; keep the
  `RetractMembership` "governance sibling of `IssueMemberIdentity`" framing accurate).

**Leave completed story files** (`4-1-*.md`, `4-2-*.md`, and earlier, plus retro/`.memlog` files) **untouched**
— they are point-in-time records.

## Definition of Done

- `grep -rniE '\bmint' backend/src` returns **nothing** — "mint" is fully removed from backend code + tests.
- Backend compiles; **full `./gradlew test` green** (incl. ArchUnit + Testcontainers) — report the count.
- Names + prose only, no behavior change; no new/removed tests except the `IssueMemberIdentityTest` rename.
- Flutter is untouched (the client never referenced this backend type) — no `flutter test` needed; say so.
