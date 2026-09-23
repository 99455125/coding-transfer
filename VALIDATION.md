# Validation — Coding Transfer

## 2026-09-22 — Every Sync 4.3.4 confirmation button alignment

- Confirmation buttons now share one preferred-width, equal-width grid with an 8-scaled-pixel gap, anchored at the right of a separate full-width bar. Cancel is immediately left of Confirm; text and actions unchanged.
- Release compilation/packaging completed against IC-251.26094.121. No tests or live IDEA UI validation performed; visual acceptance remains with the user.
- Artifact: `dist/every-sync-4.3.4.zip` and adjacent build-generated SHA-256 file.

## 2026-09-22 — Every Sync 4.3.3 UI delivery

- Release compilation/packaging against IC-251.26094.121 completed. No automated tests, full regression or live IDEA UI checks were run.
- Project send/receive counts use payload.entries().size(), including deletes, independent of per-ref display rows. Receipt handling does not use these file counts. Preview and successful apply display separate actual change counts; reset/clear/new generation clear the relevant summaries.
- Both copying panels retain a single copied/missing-block progress display. Project file counts are separate from transient operation messages.
- TransferConfirmDialog replaces the large checkbox-based apply dialog and VM generation confirmation. Apply keeps original target/source HEAD/source-branch wording and role visibility; buttons explicitly place Cancel left and Confirm right. Text width is capped; no fixed tall preferred panel or stored dialog-size key.
- MergeDialogs, ThreeWayMerge, Git merging, payload format, backup, stale checks and rollback were not changed. No automatic application or Git writes added.
- Artifact: `dist/every-sync-4.3.3.zip`; use the adjacent build-generated `.sha256` file for checksum. Window sizing/button interaction on Windows and HOST still require manual acceptance.

## 2026-09-22 — Every Sync 4.3.2 maintenance delivery

- Release `./build.sh` compiled and packaged against IC-251.26094.121. Core and test sources also compiled with Java 21, without executing tests.
- **No test group, full regression, GUI check or performance benchmark was run**, per the approved verification policy. Prior release results below do not certify 4.3.2.
- One read-only code review found no blocking issue in extraction, chunk integrity or explicit test-group routing. Static review is not proof of runtime equivalence.
- Extracted ProjectTransferService, GitCommandRunner, GitImportTransaction, ProjectChunkCodec and ProjectInbox; SyncEngine's generation roles now have separate methods. File apply/rollback order and protocol formats retained.
- Incomplete project receive no longer reads/concatenates prior block contents. Completion still validates all persisted blocks and the full payload. Added an unexecuted corruption/resume regression for this boundary.
- Removed two duplicate WorktreeTest cases, retaining actual empty-file application and stronger staged-delete/recreation checks in UntrackedTest. Shared fixture/Git/assertion helpers; explicit core/worktree/bundle/transport/cache/large/all entrypoints; SendCacheTest included; large-file checks isolated; success summaries and failure logs handled by TestRunner.
- AGENTS.md and MAINTENANCE.md require explicit full-suite approval and focused reading. No real Git writes, installation, external sending or dependency additions.
- Artifact: `dist/every-sync-4.3.2.zip`; build-generated SHA-256 `977bb5dcc31ed3dba3ab86cb18819e64592f3963a3c81200058cbc9c2a823d43`.
- Deferred as agreed: disk-backed project sending, streaming project payload codec and full-snapshot memory redesign. No claimed measured speedup or heap reduction.

## 2026-09-22 — Every Sync 4.3.1 untracked HOST files

- Fresh `./build.sh test`: **288 checks passed** (134 core, 37 Bundle, 47 file, 24 refs, 36 WORKTREE, 10 untracked).
- New regression first failed against tracked-only generation, then passed with nonignored untracked text/binary/empty files, null BASE encoding, unchanged HOST HEAD/index and VM index, duplicate receive, Add/Add conflict, and staged-delete/recreated-path deduplication. Prior tracked-only expectations were updated to the approved inclusion rule.
- Read-only review found leading whitespace loss in NUL-delimited filenames. A new test reproduced failure before the fix; raw stdout parsing now preserves filenames and the full suite passes.
- `./build.sh` compiled and packaged against IC-251.26094.121; ZIP integrity and SHA-256 sidecar verified.
- Artifact: `dist/every-sync-4.3.1.zip`; SHA-256 `4369505bd37fff1a02c63db4580715f08e9be21b09359cb9b3e7cdc8778d63de`.
- V3 protocol, VM Bundle/ACK, existing merge behavior and real repository Git state unchanged. Git writes occurred only in disposable test fixtures. Native IDEA GUI and Windows VM runtime not exercised; no automatic installation.

## 2026-09-22 — Every Sync 4.3.0 HOST Change Set / VM merge

- Fresh `./build.sh test`: **278 checks passed** (134 core, 37 Bundle, 47 file, 24 refs, 36 WORKTREE).
- New WORKTREE tests first reproduced the old NO_BASELINE requirement, then covered tracked-only generation, source HEAD/branch, raw BASE codec and tamper rejection, V3 clipboard, staged add + unstaged edits, branch switching, ordinary untracked/ignored/exclude omission, staged deletion followed by recreation, CRLF warnings separated from Git output.
- VM tests cover different HEAD automatic merge, VM HEAD/index preservation, idempotent receive, overlapping conflict, unresolved apply rejection, cancellation/no pre-apply writes, explicit resolutions, stale worktree rejection, Add/Add, Modify/Delete, Deleted/Modified, binary conflicts, empty-file distinction, multiple merge-file conflicts, HEAD-only changes after preview, legacy WORKTREE rejection and transaction rollback.
- Regression fixtures now use independent inner Git and full Bundle initialization where HOST tracked generation is exercised. Older expectations for baseline-generated untracked proposals and strict HEAD rejection were changed to the approved 4.3 behavior, not simply removed. VM→HOST authoritative replacement/refs/ACK and independent file tests remain passing.
- Read-only reviewer checked core and native merge adapter. Fixed the reported BOM/CR text serialization issue; added BOM, CRLF, CR and mixed-ending tests. Mixed endings require explicit confirmation before normalizing a manually edited merge output. LEFT/RIGHT use exact original bytes.
- `./build.sh` passed against IC-251.26094.121. Separate source/API compilation against installed IDEA 253 passed. ZIP integrity and SHA-256 sidecar passed.
- Artifact: `dist/every-sync-4.3.0.zip`; SHA-256 `f7e781a3c6e61d2a7bcddc5b3cc3b09b271bba23e672c8005342b285f9b27162`.
- No real business Git write operations, no automatic installation or external sending. Git commits/branch operations occurred only in disposable test repositories with explicit user approval.
- **Not runtime-verified:** native IDEA merge window interaction (text/binary, close/cancel, delete-vs-empty choice), Windows VM execution and Windows App clipboard. SDK compilation and core tests are not a substitute for these GUI checks. Both ends must upgrade; regenerate old HOST WORKTREE packets.

## 2026-09-21 — Sender clear-session buttons

- Project and file send pages now clear generated blocks/copy indexes and explicitly tracked temporary outgoing cache files. Baselines, pending ACK state, backups, source and receiver files remain untouched.
- Java 21 compilation against IC-251.26094.121 passed; standalone SendCacheTest passed (file/bundle deletion, source/baseline/pending preservation, repeated cleanup, unknown-file refusal and traversal refusal).
- Full suite currently fails at the pre-existing configuration-change assertion `VM accepts remote HOST absolute mapping without resolving it`; not reported as passing. Actual IDEA interactions not tested. No release ZIP produced, as user handles packaging.

## 2026-09-20 — Every Sync 4.2.2 UI simplification

- Regression suite passed: 243 checks (135 core, 37 Bundle, 47 file, 24 refs). Added actual checkout/content assertions for full and incremental non-current VM local branches and ordinary HOST branch preservation.
- Release compilation against IC-251.26094.121 passed. Receive and preview now run sequentially in a single background task instead of a deferred callback that may skip preview when busy.
- Removed manual text input and intermediate generation/preview buttons; clipboard receipt acceptance is separate from receive-page state. Manual chunk navigation remains and successful copy selects the first missing index.
- Running IDEA GUI, focus/clipboard interactions and the reported automatic-preview symptom have not been exercised in a real HOST/VM IDE. Core regression and compilation do not establish GUI verification.
- Artifact: `dist/every-sync-4.2.2.zip`.

## 2026-09-20 — Every Sync 4.2.1 branch apply fix

- HOST apply now updates all dedicated vm-work/* local branches from incoming refs for both full and incremental Bundles, preserving ordinary HOST branches.
- Per user request, no tests or runtime validation were run for this fix. Only release compilation/packaging is requested; user performs acceptance.
- Release artifact: `dist/every-sync-4.2.1.zip`.

## 2026-09-20 — Every Sync 4.2.0 scoped update

- `./build.sh test`: **240 checks passed** (135 core, 37 Bundle, 47 file, 21 refs checks).
- Entire refs manifests include local branches, remote refs, custom refs and non-commit objects. Test keeps VM on main after another branch commits, switches HOST to a different checkout, transfers that branch's increment, and verifies imported refs without using the file-preview list as bundle input.
- VM refresh tests cover per-ref committed changes, current committed change plus uncommitted reversion, untracked working files, lazy other-branch diff loading, unchanged checkout and per-ref ACK advancement. HOST refresh regression verifies only its existing local worktree changes are shown.
- Existing 16 MiB project-file and 64 MiB standalone-file bounds are removed. A 65 MiB+1 standalone file is snapshotted, transferred in 4096 KiB blocks, streamed through checksum reconstruction and saved. Project 4096 KiB blocks round-trip too. Project payloads still have the existing byte-array/schema representation and JVM memory constraints; removal of policy quotas is not a claim of unbounded physical capacity.
- Middle-first receive tests verify smallest missing index stays 1, advances to 2 after block 1, and skips previously received block 3. Completion returns no missing index. Copy index tracking checks the same ordering. New send/selection clears prior copy indexes.
- UI source/API compilation passes against IC-251.26094.121 and installed IDEA 253. Packaged plugin name, action and Tool Window ID are Every Sync; project role is a read-only label. Settings chunk editor has focus-loss commit/save and OK-time commit; file chunk input has focus-loss commit. Actual focus/mouse interactions in a running IDE still require manual verification.
- ZIP integrity and SHA-256 sidecar passed. No automatic installation, no business repositories changed.

Release: `dist/every-sync-4.2.0.zip` (about 130 KiB).

SHA-256: `92ba40a5b9afb44efe4c24c9e7179e938d545c8519de87b0162c5986d9860d0e`.

Manual checks still required: install same ZIP on HOST/VM, type 4096 and click another control to confirm persistence, verify role labels and lazy diff rendering, copy/paste blocks out of order and check gap labels. Windows runtime/Windows App clipboard maximum reliable block size and Plugin Verifier are not covered by these tests.

## 2026-09-20 — 4.1.0 independent binary file transfer

- `./build.sh test`: **209 checks passed** (130 original + 37 Bundle + 42 file-transfer checks).
- New checks cover raw non-UTF8 binary round trips, Unicode filenames in headers, no source-directory disclosure, explicit receiver-selected paths/renames, empty files, out-of-order/duplicate/CRLF chunks and persisted resume.
- Partial or corrupt files cannot be saved. Chunk and whole-file checksums, cached-content tampering, duplicate/unknown headers, filename traversal, mixed-session metadata, invalid sizes/counts, source/target type checks and size caps tested.
- Existing-file replacement uses an explicitly captured target state; changed or newly appeared files are rejected. Clearing cache preserves saved output. File flow requires no project mapping/role/Git configuration.
- Original project transfer regressions remain green. UI now wraps unchanged project panel and new file panel in top-level 项目传输 / 文件传输 tabs.
- Release compiled against official IC-251.26094.121. Additional IDEA 253 source/API compilation passed. ZIP integrity and SHA-256 sidecar passed. Original 4.0.0 release artifact retained with its original checksum.

Release: `dist/coding-transfer-4.1.0.zip` (about 122 KiB).

SHA-256: `9f5b45022cf2f5936af1f0ec21b4013735bb82b40859cbfb7367b98e27f24789`.

Running IDEA GUI/file chooser, Windows runtime, Windows App clipboard and Plugin Verifier remain untested. Manual acceptance: install same ZIP on both ends, switch top tabs, transfer a disposable ZIP/JAR in multiple blocks, choose a different destination name, compare original/output SHA-256, then test confirmed overwrite and rejected corrupt/incomplete input. Verify project transmission still works after switching tabs.

## 2026-09-20 — 4.0.0 Git Bundle synchronization

- `./build.sh test`: **167 checks passed** (130 original + 37 Bundle integration checks), using disposable local repositories only.
- Actual full/incremental Git bundles, branches, annotated tags, uncommitted VM content, receipt transport/confirmation, stale receipt rejection, persistent ACK baseline, empty-object delta, divergent branch switching and historical detached checkout tested.
- HOST authoritative apply tested with staged edits, unchanged-by-VM local edits, extra untracked files, exclusions, refs-only changes and full reinitialization after increments.
- Missing Git prerequisites, corrupt bundles and Git changes after preview rejected. Injected failures before and after Git metadata commit restore business files, HEAD, refs, index and baseline. Git hooks did not execute.
- HOST→VM worktree changes still work after a Git bundle baseline and do not modify VM index. Outer HOST HEAD/index/document remained unchanged.
- `./build.sh`: compiled against official IC-251.26094.121 SDK. Additional source/API compilation against installed IDEA 253 SDK passed.
- ZIP integrity and SHA-256 sidecar checks passed. Main panel no longer has a role selector; Settings retains it. Actual running-IDE UI has not been verified.

Release: `dist/coding-transfer-4.0.0.zip` (about 102 KiB).

SHA-256: `3cb72068b40056f88c65c9112ec2b6d0c1f92c258436effec8bb1655e85c949a`.

Windows runtime, Windows App clipboard, Git executable discovery and JetBrains Plugin Verifier remain untested. Compilation is not full runtime certification. Power/process failure still requires manual recovery from journals.

### 4.0 manual acceptance

1. Install same ZIP on both ends. Confirm role selector appears only in Settings; main page only selects project.
2. Use a disposable project: VM full Bundle → HOST preview/apply → copy successful ACK back to VM and confirm.
3. Make HOST staged/unstaged/untracked changes, including a file VM will not change. Commit a different file on VM and send incremental Bundle.
4. HOST preview must show all local managed edits being discarded; after apply check full VM file state, inner Git history, unchanged excluded files and untouched outer Git.
5. Return ACK. Switch VM branch and send next increment. Verify HOST current branch/history and files. Repeat with VM dirty-only changes.
6. Send a HOST worktree edit back to VM; verify VM Git remains uncommitted. Try duplicate/out-of-order clipboard blocks and restart resume.

Older releases below are historical records, not current behavior.

## 2026-09-19 — 3.0.1 arbitrary directory mappings

- `./build.sh test`: **130 checks passed**.
- New checks cover absolute HOST directories outside the workspace, arbitrary relative depth, parent-relative mappings, `~`, `.`, remote HOST path handling on VM, overlapping/duplicate mappings, metadata roots, foreign drive syntax, and unchanged payload traversal rejection.
- Actual snapshot apply / HOST change generation tested against an external mapped directory, including Unicode and spaces. No forced `projects` folder; outside AI/sibling files remain untouched; root `.git` and excluded files remain intact.
- Apply locking tested across two different IDEA workspaces targeting the same directory.
- `./build.sh`: release compiled against `IC-251.26094.121`.
- Source compatibility compilation against installed IDEA 253 SDK completed without diagnostics.
- ZIP integrity and SHA-256 sidecar checks passed.
- Updated UI labels, examples and documentation; old `projects/xxx` configurations remain valid. VMSYNC/1 wire format unchanged.

Current release: `dist/coding-transfer-3.0.1.zip`.

SHA-256: `88695e5422bd7dfe34002503a7522ceb7f4264f332d4872377832b35fdf53ef8`.

Running-IDE GUI and Windows App clipboard checks below are still not performed; these changes do not claim new GUI/runtime coverage.

## 2026-09-19 — 3.0.0 initial rebuild (historical)

Executed locally with the JDK 21 bundled in the local HOST IDEA installation:

- `./build.sh test`: **108 checks passed** on the final source, including directory-casing collisions and recovery export.
- Release compilation: passed against official IDEA Community 2025.1.2 SDK `IC-251.26094.121` (external cache, not packaged).
- Additional source/API compilation: passed against the installed IDEA 2025.3 SDK (build 253).
- `bash -n build.sh`: passed.
- Outer ZIP and inner JAR integrity: passed (`unzip -t`). SHA-256 sidecar check: passed.
- Packaged descriptor: same upgrade ID `com.shie.tools.git-change-export`, version 3.0.0, right-side Tool Window, Tools menu action, platform dependency, build range 251.26094.121–253.*.
- Bytecode major version: 65 (Java 21). Plugin contains only its classes and descriptor, no SDK libraries, native binaries, test classes or business source.

Final release: `dist/coding-transfer-3.0.0.zip` (approximately 75 KiB).

SHA-256: `6324164786624710c19715ee7336f5da7d268d3652ebab57420963e299e87f86`.

## Test coverage

Temporary local Git repositories only; no business repositories edited, no network transport or Git pushes:

- configuration parsing, canonical exclusion policy, multiple mappings, unknown project rejection;
- path traversal, absolute paths, reserved names, .git/.vmsync protection, symlinks, nested Git, case collisions and file/directory conflicts;
- UTF-8 unified diff, CRLF, empty files, absent final newline, Unicode, binary raw bytes, truncated/corrupt patches;
- bounded codec, malformed raw checksum, gzip corruption, duplicate manifests;
- clipboard chunks out of order, repeated CRLF chunks, persistent resume, wrong project/role, corrupt blocks, unknown/duplicate headers, mixed sessions, header/body mismatch, exclusion mismatch, explicit session cleanup;
- initial VM snapshot baseline; HOST edits independent of outer Git commits; unrelated project/AI files excluded;
- binary/add/modify/delete application; VM HEAD and index unchanged;
- stale HEAD and same-HEAD dirty VM target protection; stale preview rejection;
- authoritative VM replacement of HOST conflicts and extras; preservation of exclusions;
- snapshot from a historical VM checkout accepted as authority;
- injected mid-apply failures restore changed/deleted/added files and preserve HOST baseline;
- rollback refuses to overwrite an external concurrent edit;
- project apply lock rejects a concurrent apply;
- manual backup export rejects existing destinations.

## Not yet verified

- Actual plugin loading and visual layout in a running HOST / VM IDEA instance.
- Windows filesystem/Junction behavior and Windows Git executable discovery in a real VM.
- Windows App clipboard redirection, actual maximum reliable chunk size, and cross-machine end-to-end interaction.
- JetBrains Plugin Verifier has not been run. Compiling both SDKs is not equivalent to complete binary/runtime verification.
- Power loss/process kill during apply: complete pre/post backup journals are generated, but a multi-file write is not atomic and may require manual recovery.

## Manual acceptance checklist

1. Install the same ZIP on both sides; open Tools → Every Sync; check light/dark theme and file diff tabs.
2. Register a disposable test project with identical ID and exclusion policy.
3. VM full snapshot → HOST: send all blocks, preview, confirm, verify exact content.
4. Edit/add/delete text and binary files on HOST; send → VM; verify Git HEAD/index unchanged.
5. Change VM HEAD before receiving an old package and confirm BASE_MISMATCH prevents writes.
6. Commit on VM, return a fresh snapshot, and verify HOST baseline and code advance together.
7. Test a multi-block transfer with a repeated block and a restarted receiver.
8. Only after these checks, register real business projects.
