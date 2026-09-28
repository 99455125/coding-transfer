# Reliable Encrypted Transfer Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace manual per-block project and file transfer with user-started, encrypted, resumable transfer while preserving inner protocols and the distinct completion rules.

**Architecture:** Keep `ProjectChunkCodec`/`ProjectInbox`, `FileTransfer`, and existing project apply services as inner protocol and business-operation boundaries. Add a core AES-GCM frame codec and persistent reliable transfer state machine, then one shared application-level IDEA coordinator that owns clipboard polling and lifecycle. Refactor the project UI around role/state, then connect file save confirmation.

**Tech Stack:** Java 21, IC-251.26094.121 IDEA SDK, JCA AES/GCM/NoPadding, IDEA PasswordSafe, IDEA background tasks, existing Swing UI and existing explicit Java test runner. No new dependencies.

**Spec:** `docs/superpowers/specs/2026-09-24-reliable-encrypted-transfer-design.md`

## Global Constraints

- Work directly in this checkout; no worktrees, branches, commits, tags, push, or business-repository Git writes.
- Preserve unrelated edits and keep all business data operations within the selected mapped project.
- Keep V2 Bundle/ACK, V3 WORKTREE, and independent file packet formats as encrypted inner payloads.
- Use 128 KiB default blocks; do not add dependencies or stream-refactor project payloads.
- Keep IDEA EDT responsive; automatic listeners run only during explicit user-started transfer and are bounded.
- HOST apply retains exclusions, locks, stale checks, backup and rollback; VM HEAD/index remain unchanged.
- Never run `./build.sh test all`; choose explicit focused groups and report unverified real-IDE/RDP behavior.
- Do not auto-install or externally send a package.

## Review Focus

- Same session/block with different content: reject through the existing project/file inbox without overwriting the persisted block; test at the reliable engine boundary.
- Restart after persist-before-ACK: report the persisted block and resume; test store recovery.
- Wrong identity, wrong key, altered tag, and tampered ciphertext: reject before state mutation; test codec + receiver.
- Duplicate business ACK after apply: respond from completion record without reapplying or advancing unrelated pending state; test VM→HOST state chain.
- Clipboard changed by user or another panel: pause without clearing/overwriting unrelated clipboard contents; test shared coordinator lease policy.

---

### Task 1: Add encrypted frame codec and unit coverage

**Files:** Create `src/main/java/dev/codingtransfer/core/EncryptedFrameCodec.java`; modify `src/test/java/dev/codingtransfer/core/TestRunner.java`; create `src/test/java/dev/codingtransfer/core/EncryptedFrameTest.java`.

**Interfaces:** `encode(String identity, byte[] key, byte[] plaintext, byte[] nonce)` returns one outer clipboard frame; `decode(String expectedIdentity, byte[] key, String frame)` returns authenticated plaintext and nonce metadata. Add a `SecureRandom` production overload; deterministic nonce input is package-private for tests. Frame schema and fixed AAD domain come from the spec.

- [ ] Add tests for round-trip, wrong identity, wrong key, modified tag, malformed Base64, and plaintext not visible in the outer text.
- [ ] Add a deterministic test proving the production frame generator emits distinct 12-byte nonces for two newly generated frames; test retries by persisting and reusing one complete frame string.
- [ ] Run the explicit test runner's new crypto-focused selection if available; otherwise defer execution until transport group integration.
- [ ] Implement codec with JCA `AES/GCM/NoPadding`, 128-bit tag, 96-bit nonce, constant domain AAD, strict outer parsing, bounded input length, and identity match before decryption.
- [ ] Register the test in the explicit runner and run `./build.sh test transport`; expect a single `PASS transport` summary.

### Task 2: Persist minimal reliable state and per-frame retry semantics

**Files:** Create `src/main/java/dev/codingtransfer/core/ReliableTransferEngine.java`; create `src/main/java/dev/codingtransfer/core/TransferSessionStore.java`; create `src/test/java/dev/codingtransfer/core/ReliableTransferTest.java`; modify the test runner.

**Interfaces:** Define typed encrypted messages for INVITE, READY, DATA, BLOCK_ACK, MISSING, RECEIVED, BUSINESS_ACK, FINAL, FINAL_ACK, CANCEL and ERROR. Store session UUID, role/direction, inner session/type, block count, small progress metadata and only the current unconfirmed encrypted frame atomically below `.vmsync/transfer-sessions/`. Persist received bytes through existing project/file inbox APIs; do not duplicate complete received content or every historical ciphertext frame. Engine methods accept/decode a frame, prepare next encrypted frame, and return progress/actions; clock and retry policy are injectable for deterministic tests.

- [ ] Test out-of-order DATA, identical duplicate, conflicting duplicate, missing block request, finite retry cutoff, and persist-before-ACK ordering.
- [ ] Test restart by reconstructing the engine/store and resuming the same session without regenerating the current unacknowledged nonce/ciphertext.
- [ ] Implement a single in-flight DATA frame initially; every new control/data message gets a random nonce once, and retries reuse stored ciphertext verbatim.
- [ ] Add per-identity session exclusion so a second active invite reports “another session active” without changing either session.
- [ ] Run `./build.sh test transport` and `./build.sh test cache` after the explicit runner groups these state tests with transport/cache coverage.

### Task 3: Implement direction-specific durable completion

**Files:** Modify `src/main/java/dev/codingtransfer/core/ProjectTransferService.java`, `src/main/java/dev/codingtransfer/core/BundleSync.java`, `src/main/java/dev/codingtransfer/core/ProjectInbox.java`, and add completion-state tests in `ReliableTransferTest.java`.

**Interfaces:** Project adapter feeds existing chunk strings into/out of the reliable engine. HOST→VM `RECEIVED` is emitted only after complete existing validation and durable pending-content storage. VM→HOST `BUSINESS_ACK` is emitted only after existing apply and baseline persistence; VM accepts it only through `BundleSync.acceptReceipt` exact-pending validation. `FINAL/FINAL_ACK` alter only transfer completion records. Final confirmation retries are automatic only during a bounded grace period; later recovery requires an explicit continue-confirmation action.

- [ ] Test HOST→VM complete receive retains pending disk content but drops transport buffers, and local later apply has no host callback requirement.
- [ ] Test VM→HOST block completion alone does not advance baseline; exact pending business ACK does; duplicate ACK does not reapply; lost FINAL recovers from bounded completion record.
- [ ] Test HOST failure/cancel/stale apply emits no success ACK and retains required recovery data.
- [ ] Implement restart-safe completion records with count/age bounds and no deletion of baseline, pending ACK, source files, saved files or backups.
- [ ] Run `./build.sh test transport`, then `./build.sh test bundle` and `./build.sh test cache`; run `worktree` only if adapter changes affect preview/apply behavior.

### Task 4: Store pairing settings safely and introduce clipboard coordinator

**Files:** Modify `src/main/java/dev/codingtransfer/core/Config.java`, `src/main/java/dev/codingtransfer/plugin/SettingsDialog.java`, `src/main/java/dev/codingtransfer/plugin/SyncPanel.java`, and `src/main/java/dev/codingtransfer/plugin/FileTransferPanel.java`; create `src/main/java/dev/codingtransfer/plugin/TransferCoordinator.java`.

**Interfaces:** Public config contains shared non-secret identity only. IDEA `PasswordSafe` stores a 256-bit key scoped to the local workspace pairing identity. User explicitly generates/copies a pairing string containing identity and key, then pastes it on the peer; only the identity enters YAML. `TransferCoordinator` is shared application-level owner of one bounded scheduled task and unique clipboard lease; explicit send publishes INVITE first, receive reads it and replies READY. Active valid peer frames may replace the lease frame; ordinary clipboard changes pause transfer. Panels receive immutable progress snapshots on EDT.

- [ ] Add tests for identity parsing, key presence/absence and config YAML never containing key bytes; keep PasswordSafe API behind a small plugin service seam.
- [ ] Implement pairing setup fields and secure random generation; provide a copy/paste configuration string, never persist its key portion in YAML or logs.
- [ ] Implement coordinator poll lifecycle: start only from explicit action, stop on complete/cancel/dispose/project change, cap retries/queue/thread count, and update Swing only on EDT.
- [ ] Implement shared clipboard lease: explicit start may publish the first INVITE; active session may replace it with a valid peer frame; ordinary clipboard edits pause; clear only if clipboard still contains this session's frame.
- [ ] Run `./build.sh test transport` and compile with `./build.sh` once core/plugin interfaces compile together; compilation is not claimed as runtime UI validation.

### Task 5: Refactor project transfer UI to one role-driven state area

**Files:** Modify `src/main/java/dev/codingtransfer/plugin/SyncPanel.java`, `src/main/java/dev/codingtransfer/plugin/MergeDialogs.java`, and README/architecture documentation.

**Interfaces:** UI actions delegate to `ProjectTransferService` plus `TransferCoordinator`. HOST state actions: generate changes, receive-and-apply. VM state actions: generate full/increment, receive, local pending diff/merge/apply. Content changes in place; state/progress stays in a fixed status area.

- [ ] Implement role/state action matrix and ensure VM→HOST completion returns to initial actions; increment stays disabled until baseline is usable.
- [ ] Remove send/receive sub-tabs and manual block controls from the main project flow; move local records/diagnostics/cleanup under a “更多” action.
- [ ] Ensure HOST receive applies only after its explicit “receive and apply” action; VM receive retains pending content and shows file list in the main panel.
- [ ] Reuse IDEA editor Diff/Merge pages and preserve cancellation/no-write behavior.
- [ ] Run relevant `transport`, `bundle`, `worktree`, and `core` groups only where touched; no full regression. Record manual IDEA review separately.

### Task 6: Connect independent file transfer and document boundaries

**Files:** Modify `src/main/java/dev/codingtransfer/plugin/FileTransferPanel.java`, `src/main/java/dev/codingtransfer/core/FileTransfer.java`, `protocol.md`, `architecture.md`, `README.md`, and `MAINTENANCE.md`.

**Interfaces:** File payload bytes remain in the independent file inbox/outgoing cache and receiver-selected destination. Transfer engine receives only frame/chunk metadata. Receiver emits file-save ACK only after successful checked save; sender releases its snapshot only after final confirmation.

- [ ] Test file receive remains role/Git independent, destination path never appears in transmitted frames, overwrite checks still run, and successful save is the only event that emits business completion.
- [ ] Implement receive/save/ACK UI; preserve the existing explicit local save chooser and overwrite check.
- [ ] Audit cancel/dispose/completion references so packet arrays, file handles, previews, tasks and active sessions are released; failure keeps only resumable cache.
- [ ] Update protocol versioning/wrapper, architecture boundaries, README workflow/security/memory limit, and maintenance test map.
- [ ] Run authorized focused groups (`transport`, `cache`, and affected `core`/`bundle`/`worktree`); do not run `all`. Real two-end IDEA/RDP checks remain manual acceptance.
