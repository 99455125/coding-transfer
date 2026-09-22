# Validation — Coding Transfer

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
