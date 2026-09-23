# Every Sync — maintenance rules

Work directly in this checkout. No worktrees, branches, commits, tags, push or business-repository Git writes. Preserve unrelated edits. Java 21 IDEA plugin; business Java 8 projects are unaffected.

## Read narrowly
Read this file and MAINTENANCE.md first. Read only the relevant classes and documentation sections from that index. README.md describes user workflow; architecture.md describes boundaries; protocol.md is required for wire-format changes. VALIDATION.md is historical evidence: do not load all of it on every task.

## Scope and safety
- Keep edits small. No unrelated refactors or new dependencies.
- VM is Git authority; each mapped HOST project has independent Git. Never modify the outer HOST repository.
- One project per session; never transmit raw .git. No payload commands, auto commit, push, reset or network Git.
- Explicit HOST apply imports validated bundles, updates dedicated vm-sync/vm-work refs, HEAD/index and preserves ordinary refs/recovery. Reconstruct from VM baseline plus delta, discarding nonexcluded HOST edits. Keep locks, stale checks, backups and rollback.
- HOST sends current HEAD→working-tree V3 changes including nonignored untracked files; no git add or index mutation. Git standard ignores apply to new files. VM receives via existing three-way merge; canceled/unresolved conflicts must not write business files. VM HEAD/index are unchanged.
- Whole refs drive VM bundle increments; UI file lists do not. Keep V2 Bundle/ACK and V3 worktree compatibility.
- File transfer remains independent, binary, receiver-selected destination. Clearing sessions never deletes source, saved files, baseline, pending ACK or backups.
- Keep role configuration in Settings, read-only role in project page. Do not add UI or business rules during maintenance.

## Execution and verification
- Do not automatically run tests, compilation or packaging for every edit. State the intended scope when execution is needed; a request to deliver an installable plugin includes necessary release compilation/packaging.
- Always ask the user before a full regression run (`./build.sh test all`). Do not approximate a full run by executing every group separately.
- For authorized focused checks, choose the smallest relevant group from MAINTENANCE.md. Never run an omitted/unknown group as all.
- Success output is one summary per group; detailed logs stay under .build/test-*/. Read failure details only when needed.
- If checks were not run, explicitly say so. Historical green results are not proof of a new revision.
- Git writes for tests are allowed only in disposable test fixtures, and only when the relevant tests are authorized.
- Package with `./build.sh` using IC-251.26094.121. Never auto-install or send externally.
