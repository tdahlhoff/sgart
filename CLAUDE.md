# SGART — Smart Grocery And Receipt Tracker

## Coding Rules

These rules are binding for **all** code written in this project. When a rule cannot be
followed, make the exception explicit and explain why in the code review or commit message.

### 1. Clean Code Developer

All code follows the **Clean Code Developer** principles. In particular:

- **DRY** — Don't Repeat Yourself. Every piece of knowledge has a single, unambiguous
  representation.
- **KISS** — Keep It Simple. Prefer the simplest solution that solves the actual problem.
- **YAGNI** — You Aren't Gonna Need It. Do not build for speculative future requirements.
- **Single Responsibility Principle** — every class, function, and module has exactly one
  reason to change.
- **Separation of Concerns** — keep domain logic, application logic, infrastructure, and
  presentation apart.
- **Boy Scout Rule** — leave the code cleaner than you found it.
- **Fail Fast** — validate inputs and invariants early; surface errors instead of hiding them.
- Favor **small functions**, **low nesting**, and **no dead code**. Comments explain *why*,
  not *what* — the code itself says what it does.

### 2. Naming

Names are the primary documentation of the code. Therefore:

- **No abbreviations.** Write `customer`, not `cust`; `receiptRepository`, not `recRepo`;
  `calculateTotalPrice`, not `calcTotPrc`. The only exceptions are terms that are more widely
  known in their short form than long (for example established acronyms already used in these
  rules), and these must be genuinely universal.
- Every **function, variable, class, and type name reflects its purpose** and reads as natural,
  human-readable language.
- Functions are named as **verbs or verb phrases** (`registerHousehold`,
  `markShoppingListAsCompleted`); classes and types as **nouns** (`ShoppingList`,
  `ReceiptScanner`); booleans as **predicates** (`isCompleted`, `hasPendingItems`).
- Prefer **intention-revealing** names over comments. If a name needs a comment to be
  understood, the name is wrong.
- Naming is **consistent** across the whole codebase — the same concept has the same name
  everywhere (ubiquitous language, see below).

### 3. Domain Driven Design (DDD)

The architecture follows **Domain Driven Design** where applicable:

- Model the code around the **business domain**, using a **ubiquitous language** shared by
  code, tests, and documentation. Names in code match the terms the domain experts use.
- Organize the system into **bounded contexts** with clear boundaries.
- Distinguish **entities**, **value objects**, **aggregates**, **domain events**,
  **repositories**, and **domain services** explicitly.
- Keep the **domain layer free of infrastructure concerns** (no database, framework, or
  transport details leaking into domain logic).
- Business rules live in the domain model, not in controllers, services glue, or the database.

### 4. CQRS

Apply **Command Query Responsibility Segregation** where it adds value:

- Separate **commands** (state-changing intentions) from **queries** (read-only requests).
- Commands return no domain data beyond success/identifiers; queries have no side effects.
- Read models may be shaped independently from the write model when it simplifies the
  consumer.
- Do **not** force CQRS onto trivial CRUD where it only adds ceremony — "where applicable"
  is deliberate. Justify its use per bounded context.

### 5. Data Protection — DSGVO / GDPR

All code and data design must respect the European **DSGVO (GDPR)**:

- **Data minimization** — collect and store only personal data that is genuinely needed for
  the feature. No "just in case" personal data.
- **Purpose limitation** — every stored personal datum has a documented purpose.
- **Lawful basis & consent** — features touching personal data account for consent and lawful
  basis; consent is revocable.
- **Right to erasure & data portability** — the data model must support deleting and exporting
  a person's data. Design entities so a person's personal data can be located and removed.
- **Storage limitation** — define retention periods; do not keep personal data indefinitely.
- **Security by design & by default** — encrypt personal data in transit and at rest, apply
  least-privilege access, and default to the most privacy-preserving configuration.
- **Pseudonymization / anonymization** — prefer pseudonymized or anonymized data for
  analytics and non-essential processing.
- **Auditability** — access to and processing of personal data is traceable.
- Treat **receipt contents, purchase history, and household membership as personal data**.

### 6. Testing

Tests are first-class code and follow the same Clean Code and naming rules as production code.

- **Test-Driven Development** is the default: write a failing test first, make it pass with the
  simplest change, then refactor. Where TDD is impractical, tests still accompany the code in
  the same change — never "later".
- **Test pyramid** — many fast **unit tests** at the base, fewer **integration tests** in the
  middle, few **end-to-end tests** at the top. Prefer the lowest level that can prove the
  behavior.
- **Domain first** — the domain layer (entities, value objects, aggregates, domain services)
  is covered by fast unit tests with **no** database, framework, or transport dependencies.
- **CQRS coverage** — test commands for their state changes and emitted domain events, and
  queries for the read models they return. Assert commands cause the intended change; assert
  queries stay side-effect free.
- **Behavior, not implementation** — assert observable behavior and outcomes, not internal
  structure, so tests survive refactoring.
- **Test names describe behavior** in the ubiquitous language and read as full sentences, for
  example `markShoppingListAsCompleted_marksAllPendingItemsAsPurchased`. No abbreviations.
- **Arrange–Act–Assert** structure; one logical assertion focus per test; tests are
  independent, deterministic, and free of shared mutable state or ordering assumptions.
- **Isolate external systems** (databases, HTTP, clock, randomness) behind test doubles at the
  boundaries; keep the domain tests pure.
- **DSGVO in tests** — never use real personal data in tests or fixtures. Use synthetic,
  clearly fake data. Test the privacy guarantees explicitly: right-to-erasure, data export,
  and retention behavior have their own tests.
- Every **bug fix starts with a failing regression test** that reproduces the defect.
- Tests run in **continuous integration**; a red build blocks merging.

### 7. Dependency Currency

Dependencies are kept **current**, not left to rot.

- Use the **most recent versions** of every tool, library, package, and CI action that our
  frameworks support. "Most recent *supported*" — never a bleeding-edge release that breaks the
  stack, but never a knowingly outdated one either.
- A dependency pinned to a **deprecated** version is treated as a **defect to fix**, not a warning
  to tolerate. Sitting on a deprecated major means we are already too far behind.
- When touching any dependency or workflow, check whether a newer supported major exists and bump
  to it — verifying framework support first. Small, continuous upgrades over painful big-bang ones.
- **Proactively flag** outdated or deprecated versions you notice (CI action majors, the Gradle
  wrapper, Spring Boot, `kurrentdb-client`, Flutter/pub packages, Testcontainers, JUnit, …), even
  when it is not the task at hand.
- Prefer rolling major tags for GitHub Actions unless strict commit-SHA pinning is called for.

---

*These rules take precedence over convenience. When in doubt, choose the option that is
cleaner, clearer, more domain-aligned, and more privacy-preserving.*
