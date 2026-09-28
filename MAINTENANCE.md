# Every Sync — focused maintenance

## Find the smallest change point

| Task | Read / edit | Focused test group |
| --- | --- | --- |
| Project buttons / display | plugin/SyncPanel; core/ProjectTransferService | affected core group + manual IDEA interaction |
| HOST new files / VM merge | SyncEngine, ThreeWayMerge; plugin/MergeDialogs | worktree |
| Refs / bundle / receipts | GitHistory, BundleSync, GitImportTransaction | bundle |
| Git execution safety / IO | GitCommandRunner; GitReader for root checks | worktree or bundle, according to impact |
| Project chunk parsing / receive | ClipboardTransport facade, ProjectChunkCodec, ProjectInbox | transport |
| Single-file transfer | FileTransfer; plugin/FileTransferPanel | transport; large only if relevant |
| Encrypted automatic transfer | EncryptedFrameCodec, ReliableTransferEngine, TransferCoordinator, ProjectTransferService | transport + affected business group + plugin compile |
| Session cleanup | SafeFiles, ProjectInbox, FileTransfer | cache, transport |
| Config / paths / file apply / recovery | Config, SafeFiles, SyncEngine | core |

Paths above are under src/main/java/dev/codingtransfer/. Do not read every class for each task. Read protocol.md only for protocol-sensitive changes. Do not load the full historical VALIDATION.md by default.

## Explicit commands (run only when authorized)

- `./build.sh`: release compile/package only; no tests.
- `./build.sh test core`: configuration, patching, integration, safe apply/recovery, mappings.
- `./build.sh test worktree`: V3 merge and nonignored untracked generation/application.
- `./build.sh test bundle`: Bundle and whole-refs regression.
- `./build.sh test transport`: codec, project chunks, persisted-content validation, ordinary binary file transfer.
- `./build.sh test cache`: sender cleanup boundaries (baseline/pending/source preservation).
- `./build.sh test large`: standalone 65 MiB+1 streaming case, separate from ordinary checks.
- `./build.sh test all`: all groups including large. Always ask the user first.

Missing/unknown group is an error, never an implicit all. Console prints one PASS per group; failures include the diagnostic tail, full log and fixture path. Logs live in `.build/test-*/<group>.log`. Successful disposable fixtures are removed; failed ones are retained for diagnosis. Tests are not shipped in the plugin JAR. Compilation success is not runtime/test success.

## Test maintenance decisions in 4.3.2

- Share Git command execution, fixture initialization/configuration and assertion failure handling in TestSupport.
- Remove WorktreeTest's direct empty-add assertion: UntrackedTest exercises the actual codec/VM empty-file apply instead.
- Remove the repeated WorktreeTest staged-deletion/recreation scenario: UntrackedTest now checks uniqueness, retained BASE and exact recreated bytes together.
- Keep manual Add/Add resolution and actual generated untracked Add/Add coverage: they protect different stages, not duplicates.
- Keep refs, rollback, stale-preview, path, binary-integrity and index-preservation checks.
- Make previously standalone SendCacheTest reachable from the explicit runner.
- ProjectInboxTest covers deferred old-content reading and mandatory final validation/resume. Written, not executed in this delivery.
- Encrypted automatic transport uses AES-GCM framing, persisted stop-and-wait state, duplicate-safe inbox writes, and compact completion records. `ReliableTransferTest` and `TransferCompletionIntegrationTest` exercise the engine, verified-file receipt and Bundle business ACK boundary; `TransferSessionLeaseTest` checks scheduled-task cancellation and callback release across repeated leases. `src/test-plugin/.../TransferCoordinatorTest.java` is a separate JDK 21 + IDEA SDK harness for virtual-clock coordinator flows; compile/run it directly because the core runner intentionally excludes plugin tests.

## Performance boundaries

Incomplete project receives no longer reread/hash/concatenate every saved block. They still enumerate disk entries for persisted progress; no new in-memory session state or wire format. Final reception still builds the existing in-memory payload and can require substantial heap.

Deferred: disk-backed project sending, streaming project payload codec, replacing full workspace snapshots, and removing defensive array copies. These need separate designs; do not weaken stale checks, backup/rollback or encapsulation to reduce memory. Single-file transfer already uses a disk snapshot and on-demand blocks. No measured performance or memory reduction is claimed.
