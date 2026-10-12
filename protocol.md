# VMSYNC/1 + VMSYNC/2 + VMSYNC/3 protocol

## Encrypted automatic clipboard transport

The automatic project and file flows wrap their existing inner formats in AES-256-GCM frames. The only visible outer-frame field is the paired identity followed by Base64 nonce+ciphertext+tag; message type, scope, session, indexes, counts, and inner payload are encrypted. The identity is authenticated as associated data. Every authenticated frame uses a fresh 96-bit nonce. A pairing key is 256 random bits and is stored in the OS PasswordSafe; users explicitly export/import the pairing string to connect workspaces. The key is not written to config.yaml.

One app-wide coordinator owns clipboard polling and a single active transfer lease. The sender publishes INVITE first; the receiver explicitly starts listening and publishes encrypted REQUEST(1). REQUEST(n) asks for block n and acknowledges the contiguous prefix before n. The sender returns exactly the requested DATA only after session/scope/kind/direction/total and cursor checks. A repeated current request reuses the pending ciphertext. Stale requests cannot rewind the cursor; requests cannot skip unprepared blocks. For restored sparse FILE coverage, a request may advance over multiple already-prepared blocks, confirming only a fully prepared prefix; project transfers retain sequential advancement. The receiver persists and validates DATA through the existing inbox before requesting the smallest missing block. A delayed old DATA replays the current request rather than an old ACK. Last DATA produces an internal durable BLOCK_ACK, replaced only after validation/application by the existing completion receipt.

Only the receiver's REQUEST and completion receipt own automatic retries: ten seconds since last publication, at most eight attempts followed by a ten-second response window before resumable pause. The existing 400 ms poll checks these relative timeouts; no synchronized clocks or DATA/ACK wall-clock slots are required. DATA and FINAL never autonomously rewrite the clipboard, including foreign content and delayed local echoes; restoration of these sender frames listens without publishing them. A lost DATA is recovered by repeating its small REQUEST, which causes the sender to repeat that DATA. INVITE is a bootstrap exception with bounded small-frame retry until a receiver joins. Valid peer responses are processed immediately. Clipboard rechecks/revisions protect observed newer peer frames, but RDP replication remains asynchronous and there is no distributed lock. Disk/persistence errors retain the peer frame. Waiting HOST focus assistance is separate from clipboard publication: after the configured focus delay without progress following successful publication (default 300 ms; local HOST setting, not a wire field) (and at least three seconds of observation after each successful cycle), an idle, already-frontmost supported remote client may be briefly deactivated to the current IDEA process and restored. At most three successful cycles are allowed per distinct pending frame; consecutive duplicate publications preserve this budget. User input or foreground changes during a cycle stop assistance for that session. No keyboard/mouse input is injected. macOS requires existing IDEA Accessibility permission. A completed focus cycle is not proof of clipboard delivery.

Project chunks still use the existing V1/V2/V3 inner encodings and their existing receiver semantics. VM→HOST Bundle application and the original V2 business ACK happen through BundleSync/SyncEngine before an encrypted completion handshake. HOST→VM WORKTREE is durably received by the existing ProjectInbox before it is available for local preview/merge. A canceled conflict preview makes no business-file writes. File chunks still use VMSYNC-FILE/1 and only the receiver chooses a destination. The file success response is sent after full reception, whole-file checksum validation and durable receiver caching; saving to a chosen destination is separate and may happen after the encrypted completion handshake. Completion records retain compact duplicate responses, not file/project payload copies. The short-lived terminal coordinator record retains only a detached identity/key decoder and completion metadata, without panel/listener ownership or DATA copies.

The completion exchange uses RECEIVED or BUSINESS_ACK, FINAL and FINAL_ACK as appropriate to the direction. The original V2 ACK is carried inside encrypted BUSINESS_ACK. The receiver retries its durable receipt until FINAL arrives; the sender only responds with FINAL, so a lost FINAL is recovered by the next receipt. Duplicate requests replay responses from durable current-frame/completion records. After replying FINAL_ACK the receiver releases its UI lease, retaining a lightweight application-level record for three minutes. It can republish that small confirmation at ten-second intervals, at most eight times, only while its own confirmation or an authenticated stale frame from the same session remains visible and no active transfer owns the coordinator. New user clipboard content is preserved. This recovers a lost FINAL_ACK without autonomous sender FINAL retries. Duplicate FINAL also replays FINAL_ACK.

4.3.1 adds nonignored HOST untracked files as existing V3 RAW Add entries (absent BASE, before hash `-`). No wire-format change; the 4.3.0 V3 receiver can process these entries using existing Add/Add conflict handling.

## 4.3 WORKTREE extension

New HOST proposals use VMSYNC/3 and binary version 3, WORKTREE only. After project/head/policy, encode source branch (full refs/heads name or empty detached); each entry encodes path, before/after hashes, encoding, BASE length (-1 for absent) and BASE bytes, then THEIRS length and bytes. RAW or DELETE only. BASE hash must match before; RAW bytes must match after; DELETE has absent after and zero data length. Absence is distinct from an empty file.

The existing BASE header is source HEAD metadata, not a receiver equality constraint. VM uses transmitted BASE, its current OURS, and transmitted THEIRS for content merging. Old WORKTREE packets are rejected at VM preview with an upgrade message. V1 snapshots and V2 SNAPSHOT/DELTA/ACK remain compatible and unchanged. Both endpoints must upgrade for new HOST proposals. BASE + THEIRS increases payload size. Conflict UI and cancellation do not alter framing or write business files; resolved results go through preview and explicit apply.

## 4.1 independent file transport

Files use separate `VMSYNC-FILE/1` framing; project packets cannot be received as files or vice versa. No Git or role metadata required. All bytes are raw binary encoded as Base64, without text conversion or compression.

```text
VMSYNC-FILE/1
SESSION:<UUID>
FILENAME:<single UTF-8 basename, no slash/backslash/control characters>
SIZE:<original file bytes>
CHUNK-BYTES:<raw bytes per nonfinal chunk>
PARTS:<total count>
ENCODING:BASE64
SHA256:<whole original file hash>
PART:<one-based index>
CHUNK256:<raw chunk hash>
DATA:
<Base64 chunk>
```

Default Base64 block width 128 KiB, configurable 4–1048576 KiB. Raw chunk width is Base64 width × 3/4. Header fields are fixed/unique, counts and lengths checked. Empty file has one zero-byte block and empty DATA. Filename is metadata/a save-dialog suggestion only, never a destination path. No sender directory is transmitted. Filename encoding/size is bounded; embedded paths and control characters rejected.

Persistent `.vmsync/file-inbox/<UUID>/`: metadata.txt, numbered .part binary blocks and verified.bin after complete file hash verification. Up to 32 sessions, without a byte quota. Out-of-order/identical duplicate chunks supported. Metadata conflicts and corrupted blocks rejected. Re-paste any block to resume across restart. Receiver explicitly chooses target and confirms replacing existing files. Cache bytes are revalidated at save; target changes since selection block writes. No automatic extraction/execution. Clearing only removes validated known files in the selected cache session.

## 4.0 extension

Extended payloads use VMSYNC/2 and binary version 2. After file entries: snapshot UUID, parent UUID (`-` for full), VM branch or empty for detached, complete refs map (existing reference-count validation retained), length-prefixed Git Bundle bytes. Version 1 file-only payloads remain readable but cannot seed a Git increment chain.

SNAPSHOT is VM→HOST with HEAD header, full files and full bundle. DELTA is VM→HOST with HEAD, file changes and incremental/empty bundle. ACK is HOST→VM with HEAD, accepted snapshot/parent IDs, but no files/bundle/refs/branch. Legacy WORKTREE used VMSYNC/1; current senders use VMSYNC/3 as above. Header/body/version/direction must agree; mixed-version chunks are refused.

HOST accepts DELTA only when parent matches its accepted baseline. Before-hashes refer to pristine baseline, never local edited bytes. It reconstructs and persists the entire target manifest. ACK comes only from persisted successful baseline; VM accepts only its exact pending target. Duplicate ACK is idempotent. Generation alone never advances acknowledged state; regeneration supersedes pending target.

Isolated staging validates Git prerequisites; full bundles cannot borrow receiver objects. Branches mirror under refs/remotes/vm-sync and tags under refs/tags/vm-sync; other refs under refs/vmsync/refs. Working branch is vm-work/<VM branch> (or detached). Explicit HOST confirmation permits controlled HEAD/index/ref updates. VM Git and outer HOST Git remain untouched.

## Legacy framing and common rules

4.2.1 receiver fix: every incoming refs/heads/* also updates its checkoutable refs/heads/vm-work/* local branch, for both SNAPSHOT and DELTA. Dedicated local branches absent from the incoming branch map are removed; the current detached fallback is retained when needed. No wire-format change.

Transport is manually copied plain clipboard text. No file clipboard types or commands.

```text
VMSYNC/1
TYPE:WORKTREE
SOURCE:HOST
TARGET:VM
PROJECT:ri-core
SESSION:<UUID>
PART:1/3
ENCODING:GZIP+BASE64
SHA256:<compressed payload hash>
CHUNK256:<this chunk ASCII hash>
BASE:<full VM commit hash>
DATA:
<Base64 characters>
```

SNAPSHOT substitutes TYPE:SNAPSHOT, SOURCE:VM, TARGET:HOST and HEAD for BASE. Headers are fixed, duplicate/unknown fields refused, order independent. CRLF header separators accepted. Base64 data is never executed. Each chunk carries its own checksum; SHA256 verifies the reconstructed compressed payload. SHA-256 is integrity checking, not sender authentication or encryption: accept only a trusted source.

Default chunk data size 128 KiB, configurable 4–1048576 KiB. Parts can arrive out of order and identical duplicates are harmless. Conflicting duplicates or mixed session metadata are refused. Inbox is persisted on each part; pasting a part after reopening resumes that session. Local reset clears only the selected session when explicitly requested.

The Gzip payload is a versioned DataOutputStream schema, NOT Java object serialization: magic/version, type, project, full HEAD, exclusion-policy hash, entry count, then entries containing relative path, before hash (`-` for absence), after hash (`-` for deletion), encoding RAW/PATCH/DELETE, byte length and bytes. Strings use UTF-8 with bounded 32-bit lengths. No absolute source/target paths are included.

Plugin 3.0.1 allows arbitrary locally configured HOST directories; the wire format is unchanged. Absolute paths and parent segments are allowed only in trusted local project mappings, never in transmitted entry paths. The receiving project ID resolves solely through the receiver's own configuration.

Snapshot manifests list every managed regular file with raw content and its SHA-256; before hash is absent. Worktree entries are unique path-sorted modifications. Existing UTF-8 text uses a strict single full-file unified hunk (`--- baseline`, `+++ working`), including no-final-newline markers; new/binary files use raw bytes. Before and after hashes are checked independently. Empty files and deletions are distinct. Directories are implicit.

Transfer byte quotas are removed; 10,000-file validation remains. Maximum clipboard data is 1 GiB per block plus headers. Existing project schema uses byte arrays and 32-bit payload lengths, retaining inherent representation/memory bounds rather than configurable byte quotas. Standalone SIZE uses a 64-bit integer and disk-backed content. Fail closed on reserved paths, malformed lengths, trailing bytes, hashes or direction. Excludes have a canonical policy digest; mismatches require aligning configuration and generating a fresh snapshot.

HEAD refers to VM history; file manifests capture actual working-tree bytes, including uncommitted and untracked nonexcluded files. HOST compares against those bytes. Legacy version 1 leaves Git metadata unchanged; version 2 performs the controlled HOST import above. Worktree exclusions do not redact Git history.
First-missing indicators do not change the wire protocol: sender tracks successfully copied block indexes for its current generated session; receiver returns the smallest absent one-based index from persisted chunks (0 means complete). No assumption of sequential transfer. Receivers upgraded to 4.3.6 accept 1 GiB blocks; older receivers may reject larger blocks, so update both ends.


## File block state (shared clipboard slot)

In 6.0.0, REQUEST is appended as message type ordinal 11; existing type ordinals, binary schema version 1 and encrypted envelope fields remain unchanged. REQUEST has a one-based index within total and an empty payload. Both automatic endpoints must upgrade together; old decoders do not recognize REQUEST. Inner V1/V2/V3 project packets and VMSYNC-FILE/1 remain compatible.

Legacy BLOCK_ACK still confirms only its named file block; a high index is not cumulative. REQUEST has explicitly different semantics: the prefix below its index is durable and the index names the next needed block. Project senders accept only the current pending index or its successor; file senders may advance across a fully prepared contiguous prefix when resuming sparse coverage after checking prepared coverage. Receivers retain sparse FILE coverage for existing state, but the pull flow requests the smallest missing index. File completion still requires all coverage and whole-file verification; a receipt cannot confirm a never-prepared block.

Receiver continuation rebuilds the request from durable coverage when state/frame publication was interrupted. If coverage is complete but no durable completion receipt exists, it requests the final DATA again so validation/application can finish; it does not publish an internal final BLOCK_ACK and strand the sender waiting for a receipt. A legacy sender whose final BLOCK_ACK was consumed (or whose receipt consumption was interrupted before FINAL creation) may reopen only the explicitly requested last block, with no pending frame and cursor total+1; file prepared coverage is still required. Other stale requests and requests after persisted FINAL are rejected. Delayed authenticated local request echoes permit timeout recovery of the current request, with the same reread/revision guard.

File state.bin uses FBS1 binary state: magic 0x46425331, total, and length-prefixed BitSet byte arrays for prepared and confirmed/received indices (bit zero unused). The limit is 256 KiB for up to one million blocks. Legacy NEXT states migrate as contiguous confirmed prefixes with any pending DATA marked prepared. Both endpoints must upgrade; old plugins cannot read FBS1. Project state and project application/baseline semantics remain unchanged.
