# WolfMind — Agent Development Instructions

> Scope: repository root and all subdirectories, unless a more specific `AGENTS.md` overrides local implementation guidance.  
> Purpose: guide coding agents (including DeepSeek Harness and Codex) to implement WolfMind consistently.  
> Status: architecture baseline; detailed contracts must be defined and reviewed before implementation.

## 1. Project mission

WolfMind is a Java-based online Werewolf platform supporting:

1. AI-only autonomous matches (initially a classic nine-player game).
2. Human–AI mixed matches.
3. Human multiplayer matches.
4. Match replay and AI decision analysis.
5. A later AI experimentation laboratory.

**Fundamental separation:** deterministic game rules belong to the game domain; nondeterministic AI reasoning belongs to the agent subsystem. AI may propose actions but never directly mutate game state.

## 2. Source of truth and reading order

Before starting a task:

1. Read this `AGENTS.md`.
2. Read root `DESIGN.md` (product and architecture overview), if present.
3. Read the relevant files in `docs/architecture/`, `docs/backend/`, `docs/ai/`, and `docs/frontend/`, if present.
4. Read `TASKS.md`, if present, and identify the current phase and acceptance criteria.
5. Inspect existing code, tests, build configuration, and API contracts before editing.

Do **not** assume a referenced file already exists. If a required design or contract is missing, draft it as a proposal or flag the gap; do not invent an irreversible decision and silently treat it as approved.

Priority for conflicts: explicit current user instruction > approved project design and contracts > this general development guide > existing conventions. Report conflicts before making architectural changes. Never silently rewrite approved architecture.

## 3. Approved technology baseline

| Area | Technology |
| --- | --- |
| Backend | Java 21, Spring Boot 3, modular monolith |
| Game design | DDD-inspired domain model, deterministic state machine, domain events |
| AI | LangChain4j, LangGraph4j |
| Frontend | Vue 3, TypeScript, Pinia, Tailwind CSS |
| Realtime | WebSocket |
| Running matches | Redis Hash / Set and other appropriate structures |
| Match event log | Redis Stream, through an Event Store abstraction |
| Durable data / completed matches | SQLite |
| Persistence access | MyBatis or MyBatis-Plus, subject to SQLite compatibility |
| AI concurrency | Bounded executor / CompletableFuture, timeout and fallback |
| Deployment | Docker Compose, Nginx |

Do not introduce microservices, RabbitMQ, Kafka, MySQL, a vector database, or other major infrastructure without an explicit architecture decision. Redis and SQLite are the first-version baseline, not an assertion that they will fit every future scale.

## 4. Ownership and collaboration

- **Backend agent (DeepSeek Harness):** owns `backend/`, backend tests, database migrations, backend-oriented specifications, and server-side contracts.
- **Frontend agent (Codex):** owns `frontend/`, frontend tests, page components, client-side state, and frontend-oriented specifications.
- **Shared contracts:** `docs/backend/api-spec.md`, `docs/frontend/websocket-protocol.md`, and event/schema specifications require coordinated changes. Neither agent may silently change them in a way that breaks the other side.
- **Human project owner:** approves changes to core architecture, game rules, major dependencies, and cross-team contracts.

Before parallel implementation, agree on request/response types, WebSocket message envelopes, error codes, role-based visibility, and a shared mock contract. Prefer generated or shared TypeScript types when a stable schema exists; do not maintain incompatible duplicate definitions.

## 5. Architectural boundaries

Use a modular monolith with logical layers:

- **Interface:** REST/WebSocket endpoints, authentication context, validation, DTO mapping.
- **Application:** use-case orchestration, transactions, loading/saving aggregates, scheduling and event publication.
- **Domain:** pure Java entities, value objects, aggregates, rules, state transitions, and domain events.
- **Infrastructure:** Redis, SQLite, LLM adapters, WebSocket delivery adapters, external services.

Dependency direction: interface and infrastructure depend on application/domain abstractions; domain must not depend on infrastructure. Infrastructure implements ports defined in the inner layers.

### Non-negotiable rules

1. `Game` is the primary aggregate and the authoritative rule boundary for a match.
2. Domain code must not import Spring, Redis, MyBatis, LangChain4j, LangGraph4j, or WebSocket classes.
3. Controllers must not directly mutate game state or write domain-owned Redis keys.
4. Every **committed domain state transition** must be represented by appropriate domain event(s). Rejected commands do not produce successful-action events.
5. Game rules, action legality, skill resolution, phase progression, and victory conditions are deterministic domain responsibilities.
6. AI, WebSocket clients, and tools submit commands through the application boundary; none may bypass domain validation.
7. Domain events describe committed facts (for example `PlayerVoted`), not unvalidated intentions (for example `VoteRequested`).
8. Do not put network calls or LLM calls inside game-state transactions or Redis atomic scripts.

## 6. Game domain and controller abstraction

Initial ruleset: classic nine-player game (3 werewolves, 3 villagers, 1 seer, 1 witch, 1 hunter). **Before implementing rules, explicitly settle** night action order, witch restrictions, hunter triggers, ties, last words, winning conditions, timers, and disconnections. Do not invent rule variants silently.

Game state includes at minimum match ID, ruleset version, phase, phase version, aggregate version, round/day, seats/roles, alive status, and pending/committed actions as appropriate.

Separate player identity from the action provider. Define an asynchronous controller/port for:

- `HumanController`: receives validated client-submitted commands.
- `AiController`: requests decisions from Agent Runtime.
- `RuleBotController`: deterministic simulation/testing fallback.

Do not store an AI SDK object inside the `Player` entity. A human-to-AI takeover must not require changing the game rules.

Commands must carry a unique `commandId`, `gameId`, `playerId` (derived/verified against authenticated session where applicable), expected phase/version, and command-specific payload. Enforce idempotency, authorization, action deadlines, and stale-command rejection.

## 7. Event-driven architecture and Event Store

**Do not equate an event bus with the source of truth.** The intended write path is:

1. Receive command and validate caller.
2. Load current game state / aggregate version.
3. Apply deterministic domain transition and collect domain events.
4. Atomically persist authoritative game-state changes and append events to the Redis Stream, with version/idempotency checks (for example, a carefully designed Lua script or Redis Function on the same Redis deployment).
5. Only after successful commit, publish/deliver committed events to WebSocket projections and other consumers.

Never broadcast a successful state transition before durable/accepted persistence. Consumers must tolerate at-least-once delivery, retries, and duplicates; use `eventId` and/or `(gameId, aggregateVersion)` for deduplication.

### Event envelope

Each persisted domain event must have at least:

- `eventId`, `gameId` / `aggregateId`, `aggregateVersion` (monotonically increasing per game)
- `eventType`, `eventSchemaVersion`, `rulesetVersion`
- `occurredAt`, `commandId` / correlation ID where applicable
- Typed payload sufficient for deterministic replay
- Visibility classification or server-side access policy metadata

**`aggregateVersion` and `phaseVersion` are different:** aggregate version protects event/state ordering; phase version rejects actions from expired phases.

Redis Stream is the initial storage mechanism, **not automatically a complete Event Store**. The application must define append, expected-version checks, read-by-version, schema evolution, snapshots, recovery, and retention. Do not trim or expire unarchived events.

### Snapshots and replay

- A snapshot stores the complete replay-relevant aggregate state, `gameId`, `aggregateVersion`, and `rulesetVersion`.
- Recovery loads the latest valid snapshot, then replays events with higher aggregate versions in strict order.
- Event replay must be deterministic and must not call an LLM or rerun random decisions. Persist resolved outcomes and any random seed/results required by the rules.
- Initial snapshot cadence can be configurable (e.g. every 50 committed events), not hard-coded into the domain.
- Test replay equivalence: reconstructed state equals the state obtained from live command processing.

### Redis and SQLite durability

- Redis holds active-match state and events; SQLite stores accounts, completed games, match history, stats, and selected AI metadata.
- Archiving must be idempotent and retryable. Mark an archive complete only after SQLite transaction success; set Redis TTL/cleanup only afterward.
- Configure and document Redis persistence and backup assumptions. Redis-only active matches are not automatically crash-proof; specify acceptable data-loss/recovery behavior.
- Never assume a Redis Stream consumer acknowledgment proves that SQLite archival succeeded.

## 8. AI subsystem boundaries

The intended path is:

`AiController → Agent Runtime → Strategy Layer → LangGraph4j workflow → LangChain4j/model → proposed structured action → application/game validation`

### Agent Runtime

Owns per-match/per-seat agent lifecycle, context building, memory isolation, workflow invocation, model selection, timeouts, and observability. Do not mix runtime state with the domain `Player` entity.

### Strategy Layer

Owns explicit tactical constraints, candidate actions, heuristic scoring, and structured objectives. LLMs may reason and produce natural-language speech, but deterministic legality and victory logic remain in the domain. Do not hard-code one mandatory tactical choice for all personalities.

### Tool Gateway

All model-visible tools go through a controlled gateway:

- Read tools return only the information the acting seat is authorized to see.
- Action tools produce proposals or submit authenticated commands through the application service; they never write Redis/SQLite directly.
- Verify game/seat identity, role, phase, target legality, rate limits, command idempotency, and deadlines server-side.
- Never trust model-generated `playerId`, `role`, or `gameId` as authorization evidence.

**Hidden-information protection:** public, faction-only, private-role, and post-game data must be isolated. A villager Agent must not receive werewolf identities, night choices, private tools, or hidden reasoning through prompts, memory, logs, or replay endpoints. Spectator and replay APIs must respect reveal policy until a game is finished.

**AI failure policy:** bounded concurrency, explicit timeout, limited retry budget, deterministic legal fallback, and stale-result rejection. Do not block the game state machine indefinitely on a model call.

**Decision analysis:** store observable inputs, prompt/model versions, chosen strategy labels, tool outcomes, action, latency, and token usage when available. Do not claim access to a model's private chain-of-thought; show a concise generated rationale or structured decision summary instead.

## 9. API, WebSocket, and frontend rules

- REST handles authentication, lobby/room operations, configuration, history, and analysis retrieval.
- WebSocket handles live room/game events, player commands, speech streaming, and reconnection synchronization.
- Define canonical message envelopes with `type`, `messageId`, `gameId`/`roomId`, `sequence` or `aggregateVersion`, timestamp, and payload.
- On reconnect, send an authorized state snapshot plus missed events or a resync marker; never trust client-side state as authoritative.
- Keep the server authoritative for timers, turn order, permissions, and results.
- Streamed AI speech chunks are **presentation updates**, not automatically committed domain events. Commit the final accepted speech/action once; distinguish transient chunks from replayable events.
- The frontend must never infer secret identities from unrestricted server payloads. Enforce visibility server-side.

Planned routes/pages: landing (`/`), authentication, lobby, room creation, waiting room, live game, AI spectator arena, replay, post-game analysis, AI lab, and profile. Build according to `docs/frontend/page-design.md` once approved; use mock adapters until contracts are ready, and remove mocks when integrated.

## 10. Quality gates and testing

Every implementation task must include relevant tests and acceptance evidence:

- **Domain:** state-machine transitions, roles/skills, tie handling, victory conditions, invalid actions, and deterministic simulations.
- **Events:** event schema, strict aggregate versions, duplicate command handling, atomic state+event writes, replay equivalence, snapshot recovery, and archive retry.
- **AI:** role visibility isolation, structured action validation, tool authorization, timeout/fallback, stale response rejection, and model-call mocking.
- **Realtime:** WebSocket ordering, reconnect/resync, duplicate delivery, permissions, and client-side state reconciliation.
- **Frontend:** route navigation, loading/empty/error states, action eligibility, and accessible interaction feedback.

Use deterministic fake controllers and mocked model adapters in automated tests. Tests must not require paid LLM calls by default. Run the relevant tests and report actual results; never claim unrun tests passed.

## 11. Development phases and acceptance criteria

### Phase 1 — Pure Java game domain

Implement game aggregate, rules, state machine, commands, domain events, and rule-based test controllers. No Redis, WebSocket, or LLM dependency in domain. **Acceptance:** deterministic automated matches and edge-case tests, with no illegal phase transitions.

### Phase 2 — Redis Event Store and SQLite archival

Implement event envelopes, expected-version atomic commit, projections, snapshot/replay, SQLite schema/migrations, and idempotent archival. **Acceptance:** recovered state equals pre-restart state; duplicates and stale writes cannot corrupt a game; archive retry is safe.

### Phase 3 — AI Agent integration

Implement controller abstraction, Agent Runtime, Strategy Layer, Tool Gateway, LangChain4j, and LangGraph4j workflows. **Acceptance:** nine AI seats can finish a match; secret roles remain isolated; invalid/late model output is rejected or safely handled.

### Phase 4 — Online multiplayer

Implement room management, WebSocket protocol, Vue pages, authentication, AI fill, mixed play, and reconnect. **Acceptance:** human and AI seats complete a match with correct visibility and synchronized state.

### Phase 5 — Product and operations

Implement replay, AI analysis, AI lab, observability, container deployment, and performance testing. **Acceptance:** replay accuracy, controlled model cost, reproducible deployment, and documented operational limits.

Do not pre-build later phases unless a current task explicitly requires them.

## 12. Agent execution protocol

For every coding or documentation task:

1. **Read:** inspect relevant docs, source files, and existing tests.
2. **Clarify:** identify missing decisions or contradictory requirements. Ask for approval when the decision changes rules, architecture, data ownership, or public contracts.
3. **Plan:** state the smallest deliverable, affected files, dependencies, and acceptance checks.
4. **Implement:** make scoped changes; preserve module boundaries; do not rewrite unrelated code.
5. **Verify:** run formatting, compilation, tests, and schema/contract checks as available.
6. **Report:** summarize changed files, behavior, tests actually run, outstanding risks, and follow-up work.
7. **Document:** update affected design/API/event schema docs when implementation changes an approved contract.

For documentation generation, produce concrete definitions (fields, invariants, diagrams, sequence flows, failure modes, APIs, examples, and testable acceptance criteria). Clearly mark open decisions as **TBD** and list alternatives rather than presenting guesses as finalized requirements.

## 13. Documentation map

Expected documentation (create incrementally, not as empty placeholders):

```text
wolfmind/
├── AGENTS.md
├── DESIGN.md
├── TASKS.md
├── README.md
├── docs/
│   ├── architecture/
│   │   ├── ddd-design.md
│   │   ├── event-design.md
│   │   └── system-design.md
│   ├── backend/
│   │   ├── domain-model.md
│   │   ├── api-spec.md
│   │   ├── redis-schema.md
│   │   └── database-schema.md
│   ├── ai/
│   │   ├── agent-runtime.md
│   │   ├── strategy-layer.md
│   │   └── tool-gateway.md
│   ├── frontend/
│   │   ├── page-design.md
│   │   └── websocket-protocol.md
│   └── ops/
│       ├── deployment.md
│       └── observability.md
├── deploy/
│   ├── docker-compose.yml
│   ├── Dockerfile.backend
│   ├── Dockerfile.frontend
│   ├── nginx.conf
│   └── .env.example
├── backend/
└── frontend/
```

`DESIGN.md` is the human-readable architectural overview and decision index. Detailed `docs/` files are implementation specifications. `TASKS.md` tracks approved milestones and status. `README.md` explains setup and usage. This `AGENTS.md` governs how coding agents work; it is not a replacement for detailed design.

## 14. Forbidden shortcuts

- Do not let an LLM directly change roles, kill players, decide rule legality, or declare winners.
- Do not allow agents or controllers to bypass the Game aggregate.
- Do not publish successful gameplay events before state/event commit succeeds.
- Do not make Redis state and event history independently writable without an atomic consistency plan.
- Do not use Redis Stream consumer groups as a substitute for event-version validation.
- Do not expose private-role information in generic broadcasts, prompts, spectator feeds, or pre-game-end replay.
- Do not invent API schemas or ruleset details as if already approved.
- Do not introduce new infrastructure solely to increase resume buzzwords.
- Do not fabricate test results, benchmarks, model reasoning, or deployment success.

**Default decision rule:** choose the smallest implementation that preserves the approved architecture, deterministic game behavior, information security, and testability.

## 15. Source-file conventions (implementation-binding)

These two rules resolve conflicts between the design documents and the Java language. They are
naming/encoding decisions only; they do not change the approved architecture.

### 15.1 Layer package names

`ddd-design.md` §5.2 names the layers `interface`, `application`, `domain`, `infrastructure`.
`interface` is a Java reserved word and cannot be a package segment, so the Java package is
`interfaces`:

| Design name | Directory | Java package |
| --- | --- | --- |
| Interface | `backend/server/src/main/java/com/wolfmind/interface/` | `com.wolfmind.interfaces.*` |
| Application | `backend/server/src/main/java/com/wolfmind/application/` | `com.wolfmind.application.*` |
| Domain | `backend/engine/src/main/java/com/wolfmind/domain/` | `com.wolfmind.domain.*` |
| Infrastructure | `backend/server/src/main/java/com/wolfmind/infrastructure/` | `com.wolfmind.infrastructure.*` |

Directory keeps the design name, package uses the compilable name. Dependency-rule tests
(`DEP-1..DEP-7`) must assert on `com.wolfmind.interfaces.`, never `com.wolfmind.interface.`.

### 15.2 Encoding and generated edits

- All source files are **UTF-8 without BOM**. A BOM makes `javac` fail with
  `illegal character: '\ufeff'`, and it only shows up in a real build.
- On Windows PowerShell 5.1, `Set-Content -Encoding UTF8` and `Out-File` **write a BOM**.
  Use `[System.IO.File]::WriteAllText($path, $text, [System.Text.UTF8Encoding]::new($false))`
  or `-Encoding utf8NoBOM`.
- Do not write `\uXXXX` sequences in comments: Java processes Unicode escapes before lexing,
  so an invalid escape fails the build even inside a comment.
- After any scripted bulk edit to `src/`, run a real `mvn test` on the affected module before
  reporting success. A byte-level check for `EF BB BF` is cheap and worth running.

