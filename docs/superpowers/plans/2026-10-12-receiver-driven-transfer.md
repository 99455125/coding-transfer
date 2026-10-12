# Receiver-driven transfer implementation plan

Goal: receiver owns block requests and automatic retransmission; sender publishes DATA only in response to a validated request. Apply to project and file transfers without changing inner payloads, Git authority, durable inboxes or completion semantics.

Design: append encrypted REQUEST(index) to the existing message enum, preserving old ordinal values. REQUEST(n) asks for n and confirms the contiguous prefix before n. Sender accepts only its pending index or the next index after a prepared pending DATA, rejecting stale/future requests. Receiver persists DATA before recording/requesting its smallest missing block. Duplicate DATA returns the current request. Last DATA retains the durable final block ACK internally until verified completion replaces it. Only receiver REQUEST and receiver completion receipts retry; DATA and FINAL respond to requests/receipts without timers. INVITE retains bootstrap recovery until a receiver joins. Sender restoration of DATA/FINAL listens without publishing. Existing legacy engine entry points remain for persisted state and inner compatibility; automatic peers should upgrade together.

Execution: implement directly in this checkout, no Git writes or subagents during implementation. Preserve all existing edits.

- [x] Add failing core tests for initial request, persistence/restart, duplicate ciphertext, stale/future rejection, disk failure, final completion, and file request progression.
- [x] Implement REQUEST handling in ReliableTransferEngine and update both UI adapters.
- [x] Add failing coordinator tests for single retry owner, request loss/delayed echoes, newer peer protection, completion receipt retries, and passive sender restoration. Replace obsolete two-clock-slot expectations.
- [x] Implement receiver-only relative timeout recovery, preserving retry bounds and clipboard reread/revision protections.
- [x] Update protocol and architecture documentation; bump version.
- [x] Run focused transport and affected bundle completion tests, coordinator harness, then compile/package IDEA 251 and 262 artifacts. Never run test all.

Review focus: crash between cursor and cached-frame persistence; old local echoes or old requests after progression; failed clipboard response writes; duplicate last DATA while apply fails; completion receipt/FINAL/FINAL_ACK loss. Real RDP scheduling remains a field-validation limitation.

Validation: focused transport and bundle passed; coordinator harness passed with SDK 251/JDK21 and SDK262/JDK25, including dropped control/DATA/completion frames and delayed bridge. Both 6.0.0 distributions compiled and packaged, ZIP/JAR descriptors and checksums verified. Full regression and real RDP interaction were not run. Independent read-only review findings fixed and re-reviewed.
