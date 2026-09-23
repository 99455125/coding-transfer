# VMSYNC/1 + VMSYNC/2 + VMSYNC/3 protocol

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

Default Base64 block width 128 KiB, configurable 4–4096 KiB. Raw chunk width is Base64 width × 3/4. Header fields are fixed/unique, counts and lengths checked. Empty file has one zero-byte block and empty DATA. Filename is metadata/a save-dialog suggestion only, never a destination path. No sender directory is transmitted. Filename encoding/size is bounded; embedded paths and control characters rejected.

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

Default chunk data size 128 KiB, configurable 4–4096 KiB. Parts can arrive out of order and identical duplicates are harmless. Conflicting duplicates or mixed session metadata are refused. Inbox is persisted on each part; pasting a part after reopening resumes that session. Local reset clears only the selected session when explicitly requested.

The Gzip payload is a versioned DataOutputStream schema, NOT Java object serialization: magic/version, type, project, full HEAD, exclusion-policy hash, entry count, then entries containing relative path, before hash (`-` for absence), after hash (`-` for deletion), encoding RAW/PATCH/DELETE, byte length and bytes. Strings use UTF-8 with bounded 32-bit lengths. No absolute source/target paths are included.

Plugin 3.0.1 allows arbitrary locally configured HOST directories; the wire format is unchanged. Absolute paths and parent segments are allowed only in trusted local project mappings, never in transmitted entry paths. The receiving project ID resolves solely through the receiver's own configuration.

Snapshot manifests list every managed regular file with raw content and its SHA-256; before hash is absent. Worktree entries are unique path-sorted modifications. Existing UTF-8 text uses a strict single full-file unified hunk (`--- baseline`, `+++ working`), including no-final-newline markers; new/binary files use raw bytes. Before and after hashes are checked independently. Empty files and deletions are distinct. Directories are implicit.

Transfer byte quotas are removed; 10,000-file validation remains. Maximum clipboard data is 4 MiB per block plus headers. Existing project schema uses byte arrays and 32-bit payload lengths, retaining inherent representation/memory bounds rather than configurable byte quotas. Standalone SIZE uses a 64-bit integer and disk-backed content. Fail closed on reserved paths, malformed lengths, trailing bytes, hashes or direction. Excludes have a canonical policy digest; mismatches require aligning configuration and generating a fresh snapshot.

HEAD refers to VM history; file manifests capture actual working-tree bytes, including uncommitted and untracked nonexcluded files. HOST compares against those bytes. Legacy version 1 leaves Git metadata unchanged; version 2 performs the controlled HOST import above. Worktree exclusions do not redact Git history.
First-missing indicators do not change the wire protocol: sender tracks successfully copied block indexes for its current generated session; receiver returns the smallest absent one-based index from persisted chunks (0 means complete). No assumption of sequential transfer. Receivers upgraded to 4.2 accept 4096 KiB blocks; older receivers may reject larger blocks/entire-refs manifests, so update both ends.
