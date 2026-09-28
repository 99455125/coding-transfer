# Every Sync 4.3.2 architecture

## Encrypted transfer layer

PairingSecretService keeps workspace identities in Config and the shared AES key in PasswordSafe. EncryptedFrameCodec exposes only identity and authenticated ciphertext. TransferCoordinator is an application service that owns clipboard polling, bounded foreign-slot recovery, the single active lease and pause/resume behavior. ReliableTransferEngine persists one pending frame, a sequential project cursor, and sparse prepared/confirmed sets for automatic file transfers. Pending DATA and BLOCK_ACK refresh in separate wall-clock slots (modulo 10 seconds: DATA 1/6, BLOCK_ACK 4/8), evaluated by the existing 400 ms poll with fresh clipboard reads/revisions, per-slot deduplication and a one-second post-publication guard; other controls do not periodically rewrite their own frames; valid peer requests are processed idempotently. It delegates each plaintext DATA payload to the existing project or file inbox, so it does not duplicate incoming blocks.

ProjectTransferService binds reliable transport completion to existing business state: V2 Bundle ACK acceptance remains exact-pending-target validation, and V3 WORKTREE is only marked received after ProjectInbox durably validates it. FileTransferPanel invokes the same transport coordinator while FileTransfer continues to own binary checksums, inbox persistence and receiver-selected save paths. FileReceiveConfirmation sends the file receipt after verified inbox persistence, independent of later target saving. TransferCompletionStore retains bounded compact completion responses for duplicate handling, never business payload copies.

4.3.2 maintenance: ProjectTransferService prepares sending and complete-receive previews without UI dependencies. GitCommandRunner owns the existing fixed-command IO/security policy; GitImportTransaction owns isolated verification, metadata snapshots, dedicated-ref updates and rollback. GitHistory retains refs, diffs and bundle generation. SyncEngine keeps file transaction ordering and separates VM snapshot/HOST worktree generation.

ClipboardTransport is a stable facade over ProjectChunkCodec (wire parsing/encoding) and ProjectInbox (persistence/reassembly). Incomplete reception validates the incoming block and identical duplicates, enumerates safe numbered files and computes the gap; it does not read prior content. Completion revalidates every persisted block/signature/index, whole checksum, protocol/body fields and exclusion policy before returning a payload. Existing on-disk formats and UI completion→preview sequence remain unchanged. Progress counts persisted indexes, not independently revalidated old content, until completion.

TestRunner provides explicit groups with summary-only console output and detailed disk logs. No default full suite; see MAINTENANCE.md.

4.3.1: HOST hostChanges unions tracked HEAD→Working Tree changes with `git ls-files --others --exclude-standard -z`, respecting plugin exclusions. Paths already present in the tracked change set take priority, preserving HEAD BASE for staged deletions followed by recreation. Both initial and final consistency scans use the same union. No index mutation or VM generation change.

4.3: HOST generates tracked HEAD→Working Tree changes independently of received baselines. V3 WORKTREE carries BASE and THEIRS bytes plus source HEAD/branch. VM preview compares BASE/OURS/THEIRS through ThreeWayMerge; clean text uses content-only git merge-file in temporary storage. Conflicts are exposed on Preview, and apply rejects unresolved previews. MergeDialogs uses IDEA native text/binary merge with in-memory virtual outputs. Any cancel discards resolutions; complete resolutions produce a new Preview retaining the original before-map for stale checks. No VM HEAD equality check; no VM index/history writes. Git stdout and stderr are drained separately so warnings cannot corrupt path/blob/merge bytes.

4.2.2 UI: generation combines scanning/packing/chunking; single-file selection triggers preparation. Clipboard reception and complete-payload preview run in one background task, returning the preview with the received result; no deferred ToolWindow callback or busy-gated preview continuation. ACK acceptance is a separate send-page action. Copy returns to the first missing index after either automatic or manual selection. Transport/core rules are unchanged.

The top-level Tool Window has Project Transfer and File Transfer tabs. Existing SyncPanel remains the project flow. FileTransferPanel provides independent single-file send/receive, without Config, role, baseline or Git access. FileTransfer handles raw byte chunks and checksums, persistent inbox, explicit local destination selection, overwrite-state checks and complete-file save. No destination path is accepted from the packet. UI file work runs in background tasks; incomplete/invalid receive cannot enable save. File inbox and project inbox use separate directories and incompatible protocol magic.

VM is authoritative. Each mapped HOST business root has independent Git history. Outer workspace Git is not committed or archived. Role selection is Settings-only; the main panel selects a project.

## Classes

| Class | Responsibility |
| --- | --- |
| Config / SafeFiles | Explicit mappings, exclusions, safe paths, scans, atomic writes |
| Model / Codec | Versioned payloads, hashes, optional Git metadata |
| TextPatch | Exact UTF-8 worktree patching |
| ClipboardTransport / ProjectChunkCodec / ProjectInbox | Stable facade; V1/V2/V3 framing; persistent chunk reassembly |
| GitReader / GitHistory | Root/HEAD, refs/diffs and bundle creation |
| GitCommandRunner / GitImportTransaction | Fixed-command IO; controlled HOST import and rollback |
| ProjectTransferService | Background send preparation and receive→preview |
| BundleSync | Pending target, acknowledged baseline, authoritative reconstruction, receipts |
| SyncEngine | File generation, preview, stale checks, backups, apply/rollback |
| SyncPanel / SettingsDialog | Project-only main header, Settings-only role; full/increment and ACK actions |
| CoreTest / BundleTest | Disposable repository regression tests |

## State chain

VM generate → pending full target, not acknowledged.
HOST receive → verify → reconstruct complete target → preview → explicit apply → persist full baseline.
HOST copy ACK → VM explicitly accepts exact pending snapshot/parent/HEAD/policy → persist acknowledged target.
Next increment uses that target. Regeneration supersedes pending state. One receiver chain per project; restart reloads disk state.

DELTA applies to pristine baseline, never edited HOST bytes. Complete resulting map is compared against HOST so even edits outside delta and extras are discarded. Exclusions retain worktree bytes. HOST→VM WORKTREE uses three-way content decisions and stale worktree checks, never changes VM Git.

## Git transaction

Bundle contains HEAD + the entire refs set, excluding acknowledged tips for increments. Empty object deltas supported. Full bundle staging cannot borrow HOST objects; incremental staging checks local prerequisites. Staged commit/tree validation precedes business writes.

Confirmed HOST apply imports into mapped .git only, mirrors branches at refs/remotes/vm-sync, tags at refs/tags/vm-sync and remaining refs at refs/vmsync/refs, chooses refs/heads/vm-work/<VM branch> (or detached), rebuilds index without worktree checkout. Ordinary refs remain; old HEAD gets recovery ref. CAS ref updates, raw metadata backup, stale checks, best-effort rollback. Hooks/fsmonitor/sparse/split index behaviors and network protocols disabled. No shell payload, reset --hard, git clean, push or auto-commit.

## Storage and recovery

4.2.1: Both full and incremental HOST applies rebuild all dedicated refs/heads/vm-work/* from incoming VM branch refs, not only the current branch. Removed VM branches lose their dedicated local refs. Ordinary HOST branches remain untouched; HEAD still selects the VM current branch (or detached fallback).

Opened workspace .vmsync: config.yaml, baselines, bundle-state/pending, bundle-state/acknowledged, inbox, backups, git-staging, git-outgoing, file-inbox, file-outgoing. Actual target .vmsync/locks/apply.lock serializes applies across workspace IDs. Runtime data ignored by local .gitignore.

Pre/post file snapshots and original Git metadata precede writes. Rollback refuses to overwrite external modifications. Imported unreachable objects/initial empty .git may remain. Power/process loss requires manual recovery; not crash-atomic across files. Pause other writers. See README for limits and unsupported repositories. Exclusions do not redact Git history.
## 4.2 scoped changes

VM refresh lists per-ref committed name/status differences versus that ref's acknowledged tip plus current HEAD→working-tree differences. Contents are lazy-loaded; no checkout is performed. HOST generation/refresh is unchanged. Git bundle create uses HEAD --all and excludes all acknowledged ref tips; the displayed file list is not a packing input.

Fixed transfer byte quotas are removed. Standalone file transfer snapshots to disk and streams reconstruction/checksum/save; existing project payloads remain byte arrays with their inherent JVM and schema representation bounds. Chunk data max is 1 GiB since 4.3.6. Both sending panels track successful copied indexes; receivers compute first absent index from persisted chunks. Role remains Settings-only but is shown read-only beside project selection. Settings chunk editor commits/persists on focus loss; file transfer chunk size persists per IDEA project.
