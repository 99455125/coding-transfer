# Every Sync

Work directly in the current checkout. Do not create a separate worktree, temporary clone, or task branch unless the user explicitly requests it. Make requested in-scope code edits directly after inspection. Do not commit, amend, tag, or push; leave changes uncommitted for user review and HOST/VM synchronization. Preserve unrelated local changes.

Read README.md, architecture.md, protocol.md and VALIDATION.md before changes.
This is a standalone Java 21 IntelliJ plugin, not a business Java 8 module.
VM is the sole business Git authority. HOST owns only its outer AI workspace.
Only one registered project is transferred per session; never transfer .git.
Do not execute payload commands or automatically commit/reset/merge/push.
4.0 user-approved amendment: real Git bundles import objects into the selected HOST repository only. Explicitly confirmed apply may update dedicated vm-sync refs, HEAD and index; preserve local branches and recovery refs. VM Git and outer HOST Git remain unchanged. HOST discards all nonexcluded local worktree edits using full baseline plus delta. Role selection belongs only in Settings.
4.1 user-approved amendment: top-level Project Transfer and File Transfer tabs. Single-file transfer is independent of project mappings/Git/roles, uses raw binary chunks with a filename header, and saves only to a receiver-selected path after complete verification and overwrite confirmation. Project-transfer restrictions above still apply to the project page.
Use ./build.sh test and ./build.sh. Release against IC-251.26094.121.
Do not install into the user's IDE or send files externally without a request.
4.2 scoped amendment: increment uses entire refs; VM refresh shows per-ref committed paths plus current working tree with lazy diffs, HOST refresh stays unchanged. Remove fixed transfer byte quotas; chunk max 4096 KiB, focus-loss chunk commit/save, read-only role label, first missing copy/paste indicators. No other workflow changes.
