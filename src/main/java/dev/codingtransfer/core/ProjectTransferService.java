package dev.codingtransfer.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import static dev.codingtransfer.core.Model.*;

/** Prepares project transfers without depending on IDEA UI or clipboard APIs. */
public final class ProjectTransferService {
    private final Path workspace;
    private final Config config;

    public ProjectTransferService(Path workspace, Config config) {
        this.workspace = workspace;
        this.config = config;
    }

    public record SendResult(SyncEngine.Generated generated, List<String> parts,
                             List<GitHistory.RefChange> refs) {}
    public record ReceiveResult(ClipboardTransport.Received received, SyncEngine.Preview preview) {}

    public SendResult generate(String projectId, boolean incremental) throws IOException {
        SyncEngine.Generated generated;
        BundleSync bundle = null;
        if (config.role() == Role.HOST) {
            generated = new SyncEngine(workspace, config).generate(projectId);
        } else {
            bundle = new BundleSync(workspace, config);
            generated = bundle.generate(projectId, incremental);
        }
        List<String> parts = ClipboardTransport.split(generated.payload(), config.chunkKb());
        List<GitHistory.RefChange> refs = bundle == null ? List.of() : bundle.refChanges(projectId);
        return new SendResult(generated, parts, refs);
    }

    public ReceiveResult receive(String projectId, String text) throws IOException {
        ClipboardTransport.Received received = receiveChunk(projectId, text);
        Payload payload = received.payload();
        if (payload != null && payload.type() == Type.ACK) {
            throw new IllegalArgumentException("请在发送页接收回执");
        }
        // Keep completion and preview in the same background operation.
        SyncEngine.Preview preview = payload == null ? null
                : new SyncEngine(workspace, config).preview(projectId, payload);
        return new ReceiveResult(received, preview);
    }

    /** Persists and validates one inner project chunk without triggering preview, document save, or UI. */
    public ClipboardTransport.Received receiveChunk(String projectId, String text) throws IOException {
        return ClipboardTransport.receive(workspace, config, projectId, text);
    }

    /** Rebuilds a pending VM WORKTREE from its durable chunks after the UI releases the in-memory payload. */
    public Payload loadCompletedWorktree(String projectId,String innerSession)throws IOException{
        if(config.role()!=Role.VM)throw new IOException("仅 VM 可重新载入待处理 WORKTREE");
        if(innerSession==null||!innerSession.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))throw new IOException("待处理 WORKTREE 会话无效");
        Path dir=SafeFiles.state(workspace).resolve("inbox").resolve(innerSession);SafeFiles.noLinks(dir);
        if(!Files.isDirectory(dir))throw new IOException("待处理 WORKTREE 收件缓存不存在");
        Path part=null;
        try(var entries=Files.list(dir)){
            for(Path entry:entries.toList()){
                SafeFiles.noLinks(entry);
                if(entry.getFileName().toString().matches("[1-9][0-9]{0,8}\\.txt")){part=entry;break;}
            }
        }
        if(part==null)throw new IOException("待处理 WORKTREE 没有可重载的分块");
        ClipboardTransport.Received restored=receiveChunk(projectId,new String(SafeFiles.read(part,TransferLimits.MAX_PACKET_CHARS),StandardCharsets.UTF_8));
        if(restored.payload()==null||restored.payload().type()!=Type.WORKTREE)throw new IOException("待处理 WORKTREE 尚未完整或类型错误");
        return restored.payload();
    }

    /** The explicit HOST receive-and-apply action authorizes this existing guarded transaction. */
    public Payload receiveAndApply(String projectId, Payload payload) throws IOException {
        if (config.role() != Role.HOST || (payload.type() != Type.SNAPSHOT && payload.type() != Type.DELTA))
            throw new IOException("仅 HOST 可自动接收并应用 VM Bundle");
        SyncEngine engine = new SyncEngine(workspace, config);
        SyncEngine.Preview preview = engine.preview(projectId, payload);
        if (!preview.conflicts.isEmpty()) throw new IOException("HOST Bundle 预览出现冲突，未应用");
        engine.apply(preview);
        // BundleSync emits the existing business receipt only after the baseline is durable.
        return new BundleSync(workspace, config).receipt(projectId);
    }

    /** HOST durable completion for a received V3 WORKTREE. No preview/apply or host business ACK is involved. */
    public String recordWorktreeReceived(String outerSession, String projectId, String innerSession, int total,
                                        Payload payload, String identity, byte[] key) throws IOException {
        if (config.role() != Role.VM || payload.type() != Type.WORKTREE || !payload.project().equals(projectId))
            throw new IOException("仅 VM 可持久确认 HOST WORKTREE");
        Codec.validate(payload);
        TransferCompletionStore records = new TransferCompletionStore(workspace);
        var previous = records.find(outerSession);
        if (previous.isPresent()) {
            TransferCompletionStore.Record record = previous.get();
            require(record.scopeId().equals(projectId) && record.transferId().equals(innerSession)
                    && record.direction() == ReliableTransferEngine.Direction.HOST_TO_VM, "接收完成记录与项目包不匹配");
            if (record.response().length > 0) { new PendingWorktreeStore(workspace).save(new PendingWorktreeStore.Pending(projectId,outerSession,innerSession)); return new String(record.response(), StandardCharsets.UTF_8); }
        }
        ReliableTransferEngine.Message response = new ReliableTransferEngine.Message(outerSession, projectId, innerSession,
                ReliableTransferEngine.Kind.PROJECT, ReliableTransferEngine.Direction.HOST_TO_VM,
                ReliableTransferEngine.Type.RECEIVED, 0, total, new byte[0]);
        String frame = ReliableTransferEngine.receiver(identity, key, new TransferSessionStore(workspace)).prepareCompletion(response);
        records.advance(new TransferCompletionStore.Record(outerSession, projectId, innerSession,
                ReliableTransferEngine.Kind.PROJECT, ReliableTransferEngine.Direction.HOST_TO_VM,
                TransferCompletionStore.Phase.RECEIVED_DURABLE, frame.getBytes(StandardCharsets.UTF_8), System.currentTimeMillis()));
        new PendingWorktreeStore(workspace).save(new PendingWorktreeStore.Pending(projectId,outerSession,innerSession));
        return frame;
    }

    /** VM receives the durable HOST receive confirmation and may release its outgoing package. */
    public String acceptWorktreeReceivedAndPrepareFinal(String frame, String projectId, String innerSession,
                                                       String identity, byte[] key) throws IOException {
        if (config.role() != Role.HOST) throw new IOException("仅 HOST 接受 WORKTREE 接收确认");
        ReliableTransferEngine.Message received = ReliableTransferEngine.decode(identity, key, frame);
        if (received.type() != ReliableTransferEngine.Type.RECEIVED || received.direction() != ReliableTransferEngine.Direction.HOST_TO_VM
                || !received.scopeId().equals(projectId) || !received.transferId().equals(innerSession))
            throw new IOException("WORKTREE 接收确认与当前发送包不匹配");
        ReliableTransferEngine engine = ReliableTransferEngine.sender(identity, key, new TransferSessionStore(workspace));
        engine.acceptReceived(frame, received.session(), projectId, innerSession);
        ReliableTransferEngine.Message fin = new ReliableTransferEngine.Message(received.session(), projectId, innerSession,
                ReliableTransferEngine.Kind.PROJECT, ReliableTransferEngine.Direction.HOST_TO_VM,
                ReliableTransferEngine.Type.FINAL, 0, received.total(), new byte[0]);
        return engine.prepare(fin);
    }

    /** VM keeps the small RECEIVED response through a bounded retry grace and answers duplicate FINAL frames. */
    public String acceptWorktreeFinalAndPrepareAck(String frame, String identity, byte[] key) throws IOException {
        if (config.role() != Role.VM) throw new IOException("仅 VM 接受 HOST WORKTREE 最终确认");
        ReliableTransferEngine.Message fin = ReliableTransferEngine.decode(identity, key, frame);
        if (fin.type() != ReliableTransferEngine.Type.FINAL || fin.direction() != ReliableTransferEngine.Direction.HOST_TO_VM)
            throw new IOException("不是 HOST 的 WORKTREE 最终确认");
        TransferCompletionStore records = new TransferCompletionStore(workspace);
        TransferCompletionStore.Record current = records.find(fin.session()).orElseThrow(() -> new IOException("缺少已持久接收的 WORKTREE"));
        require(current.scopeId().equals(fin.scopeId()) && current.transferId().equals(fin.transferId())
                && current.direction() == ReliableTransferEngine.Direction.HOST_TO_VM
                && current.phase().ordinal() >= TransferCompletionStore.Phase.RECEIVED_DURABLE.ordinal(), "最终确认与已接收 WORKTREE 不匹配");
        if (current.phase() == TransferCompletionStore.Phase.COMPLETE) return new String(current.response(), StandardCharsets.UTF_8);
        ReliableTransferEngine.Message ack = new ReliableTransferEngine.Message(fin.session(), fin.scopeId(), fin.transferId(),
                fin.kind(), fin.direction(), ReliableTransferEngine.Type.FINAL_ACK, 0, fin.total(), new byte[0]);
        String response = ReliableTransferEngine.encode(identity, key, ack);
        records.advance(new TransferCompletionStore.Record(fin.session(), fin.scopeId(), fin.transferId(), fin.kind(), fin.direction(),
                TransferCompletionStore.Phase.COMPLETE, response.getBytes(StandardCharsets.UTF_8), System.currentTimeMillis()));
        new TransferSessionStore(workspace).clearSession(fin.session());
        return response;
    }

    /** HOST releases the sent package only after receiving the matching final confirmation. */
    public void acceptWorktreeFinalAck(String frame, String projectId, String innerSession,
                                       String identity, byte[] key) throws IOException {
        if (config.role() != Role.HOST) throw new IOException("仅 HOST 接受 WORKTREE 最终回执");
        ReliableTransferEngine.Message ack = ReliableTransferEngine.decode(identity, key, frame);
        if (ack.type() != ReliableTransferEngine.Type.FINAL_ACK || ack.direction() != ReliableTransferEngine.Direction.HOST_TO_VM
                || !ack.scopeId().equals(projectId) || !ack.transferId().equals(innerSession))
            throw new IOException("WORKTREE 最终回执与当前发送包不匹配");
        new TransferCompletionStore(workspace).advance(new TransferCompletionStore.Record(ack.session(), projectId, innerSession,
                ReliableTransferEngine.Kind.PROJECT, ReliableTransferEngine.Direction.HOST_TO_VM,
                TransferCompletionStore.Phase.COMPLETE, new byte[0], System.currentTimeMillis()));
        new TransferSessionStore(workspace).clearSession(ack.session());
    }

    /** HOST applies under the existing transaction, then persists and emits the existing exact-pending business ACK. */
    public String receiveAndApplyAckFrame(String outerSession, int total, String projectId, Payload payload,
                                          String identity, byte[] key) throws IOException {
        if (config.role() != Role.HOST || payload.git() == null) throw new IOException("仅 HOST 可确认 VM Bundle 应用");
        String pendingSnapshot = payload.git().snapshot();
        TransferCompletionStore records = new TransferCompletionStore(workspace);
        var old = records.find(outerSession);
        Payload receipt;
        if (old.isPresent()) {
            TransferCompletionStore.Record record = old.get();
            require(record.scopeId().equals(projectId) && record.transferId().equals(pendingSnapshot)
                    && record.direction() == ReliableTransferEngine.Direction.VM_TO_HOST, "业务完成记录与待应用 Bundle 不匹配");
            if (record.phase() == TransferCompletionStore.Phase.BUSINESS_APPLIED
                    || record.phase() == TransferCompletionStore.Phase.COMPLETE) {
                if (record.phase() == TransferCompletionStore.Phase.COMPLETE)
                    return new String(record.response(), StandardCharsets.UTF_8);
                receipt = Codec.decode(record.response());
            } else throw new IOException("VM Bundle 仍处于传输中，不能应用");
        } else {
            BundleSync bundle = new BundleSync(workspace, config);
            Path baseline = SafeFiles.state(workspace).resolve("baselines").resolve(projectId + ".gz");
            boolean alreadyApplied = false;
            if (Files.exists(baseline)) {
                // This is only an idempotency probe, not incremental reconstruction.
                // A new full snapshot may replace a baseline with an older exclusion policy.
                Payload existing = Codec.decode(SafeFiles.read(baseline));
                alreadyApplied = existing.type() == Type.SNAPSHOT && existing.project().equals(projectId)
                        && existing.policy().equals(config.policy()) && existing.git() != null
                        && existing.git().snapshot().equals(pendingSnapshot) && existing.head().equals(payload.head())
                        && existing.git().parent().equals(payload.git().parent());
            }
            if (!alreadyApplied) {
                SyncEngine engine = new SyncEngine(workspace, config);
                SyncEngine.Preview preview = engine.preview(projectId, payload);
                if (!preview.conflicts.isEmpty()) throw new IOException("HOST Bundle 预览出现冲突，未应用");
                engine.apply(preview);
            }
            receipt = bundle.receipt(projectId);
            byte[] ackBytes = Codec.encode(receipt);
            records.advance(new TransferCompletionStore.Record(outerSession, projectId, pendingSnapshot,
                    ReliableTransferEngine.Kind.PROJECT, ReliableTransferEngine.Direction.VM_TO_HOST,
                    TransferCompletionStore.Phase.BUSINESS_APPLIED, ackBytes, System.currentTimeMillis()));
        }
        byte[] ackBytes = Codec.encode(receipt);
        ReliableTransferEngine.Message ack = new ReliableTransferEngine.Message(outerSession, projectId, pendingSnapshot,
                ReliableTransferEngine.Kind.PROJECT, ReliableTransferEngine.Direction.VM_TO_HOST,
                ReliableTransferEngine.Type.BUSINESS_ACK, 0, total, ackBytes);
        return ReliableTransferEngine.sender(identity, key, new TransferSessionStore(workspace)).prepareCompletion(ack);
    }

    /** Replays the durable response when a peer retries the final inner data block. */
    public String replayCompletedDataResponse(String frame, String identity, byte[] key) throws IOException {
        ReliableTransferEngine.Message data = ReliableTransferEngine.decode(identity, key, frame);
        if (data.type() != ReliableTransferEngine.Type.DATA) return null;
        var found = new TransferCompletionStore(workspace).find(data.session());
        if (found.isEmpty()) return null;
        TransferCompletionStore.Record record = found.get();
        if (!record.scopeId().equals(data.scopeId()) || !record.transferId().equals(data.transferId())
                || record.kind() != data.kind() || record.direction() != data.direction()) return null;
        if (record.phase() == TransferCompletionStore.Phase.COMPLETE || record.phase() == TransferCompletionStore.Phase.RECEIVED_DURABLE)
            return record.response().length == 0 ? null : new String(record.response(), StandardCharsets.UTF_8);
        if (record.phase() == TransferCompletionStore.Phase.BUSINESS_APPLIED) {
            ReliableTransferEngine.Message ack = new ReliableTransferEngine.Message(data.session(), data.scopeId(), data.transferId(),
                    data.kind(), data.direction(), ReliableTransferEngine.Type.BUSINESS_ACK, 0, data.total(), record.response());
            return ReliableTransferEngine.receiver(identity, key, new TransferSessionStore(workspace)).prepareCompletion(ack);
        }
        if (record.phase() == TransferCompletionStore.Phase.WAIT_FINAL_ACK)
            return new TransferSessionStore(workspace).readFrame(data.session());
        return null;
    }

    /** VM validates the original V2 ACK against BundleSync's exact pending snapshot before emitting FINAL. */
    public String acceptBusinessAckAndPrepareFinal(String frame, String identity, byte[] key) throws IOException {
        if (config.role() != Role.VM) throw new IOException("僅 VM 接受 Bundle 業務回执");
        ReliableTransferEngine.Message ack = ReliableTransferEngine.decode(identity, key, frame);
        if (ack.type() != ReliableTransferEngine.Type.BUSINESS_ACK || ack.direction() != ReliableTransferEngine.Direction.VM_TO_HOST
                || ack.kind() != ReliableTransferEngine.Kind.PROJECT) throw new IOException("不是 VM Bundle 业务回执");
        TransferCompletionStore records = new TransferCompletionStore(workspace);
        var prior = records.find(ack.session());
        if (prior.isPresent() && prior.get().phase() == TransferCompletionStore.Phase.COMPLETE) {
            TransferCompletionStore.Record record=prior.get();
            require(record.scopeId().equals(ack.scopeId())&&record.transferId().equals(ack.transferId())
                    &&record.kind()==ack.kind()&&record.direction()==ack.direction(),"重复业务回执与已完成会话不匹配");
            return new String(record.response(),StandardCharsets.UTF_8);
        }
        ReliableTransferEngine sender = ReliableTransferEngine.sender(identity,key,new TransferSessionStore(workspace));
        sender.validateBusinessAck(frame,ack.session(),ack.scopeId(),ack.transferId());
        Payload receipt = Codec.decode(ack.payload());
        new BundleSync(workspace, config).acceptReceipt(ack.scopeId(), receipt);
        sender.acceptBusinessAck(frame,ack.session(),ack.scopeId(),ack.transferId());
        var current = records.find(ack.session());
        if (current.isPresent() && current.get().phase() == TransferCompletionStore.Phase.COMPLETE)
            return new String(current.get().response(), StandardCharsets.UTF_8);
        records.advance(new TransferCompletionStore.Record(ack.session(), ack.scopeId(), ack.transferId(), ack.kind(), ack.direction(),
                TransferCompletionStore.Phase.WAIT_FINAL_ACK, ack.payload(), System.currentTimeMillis()));
        ReliableTransferEngine.Message finalMessage = new ReliableTransferEngine.Message(ack.session(), ack.scopeId(), ack.transferId(),
                ack.kind(), ack.direction(), ReliableTransferEngine.Type.FINAL, 0, ack.total(), new byte[0]);
        return sender.prepareCompletion(finalMessage);
    }

    /** HOST returns one durable FINAL_ACK frame; duplicate FINAL replays the stored ciphertext without applying again. */
    public String acceptFinalAndPrepareAck(String frame, String identity, byte[] key) throws IOException {
        if (config.role() != Role.HOST) throw new IOException("僅 HOST 接受最终确认");
        ReliableTransferEngine.Message fin = ReliableTransferEngine.decode(identity, key, frame);
        if (fin.type() != ReliableTransferEngine.Type.FINAL || fin.direction() != ReliableTransferEngine.Direction.VM_TO_HOST)
            throw new IOException("不是 VM 的最终确认");
        TransferCompletionStore records = new TransferCompletionStore(workspace);
        var current = records.find(fin.session()).orElseThrow(() -> new IOException("缺少已应用 Bundle 的完成记录"));
        require(current.scopeId().equals(fin.scopeId()) && current.transferId().equals(fin.transferId())
                && current.phase().ordinal() >= TransferCompletionStore.Phase.BUSINESS_APPLIED.ordinal(), "最终确认与已应用 Bundle 不匹配");
        if (current.phase() == TransferCompletionStore.Phase.COMPLETE) return new String(current.response(), StandardCharsets.UTF_8);
        ReliableTransferEngine.Message finalAck = new ReliableTransferEngine.Message(fin.session(), fin.scopeId(), fin.transferId(),
                fin.kind(), fin.direction(), ReliableTransferEngine.Type.FINAL_ACK, 0, fin.total(), new byte[0]);
        String response = ReliableTransferEngine.encode(identity, key, finalAck);
        records.advance(new TransferCompletionStore.Record(fin.session(), fin.scopeId(), fin.transferId(), fin.kind(), fin.direction(),
                TransferCompletionStore.Phase.COMPLETE, response.getBytes(StandardCharsets.UTF_8), System.currentTimeMillis()));
        new TransferSessionStore(workspace).clearSession(fin.session());
        return response;
    }

    public void acceptFinalAck(String frame, String identity, byte[] key) throws IOException {
        if (config.role() != Role.VM) throw new IOException("僅 VM 接受最终回执");
        ReliableTransferEngine.Message ack = ReliableTransferEngine.decode(identity, key, frame);
        if (ack.type() != ReliableTransferEngine.Type.FINAL_ACK || ack.direction() != ReliableTransferEngine.Direction.VM_TO_HOST)
            throw new IOException("不是 HOST 的最终回执");
        TransferCompletionStore records = new TransferCompletionStore(workspace);
        TransferCompletionStore.Record current = records.find(ack.session()).orElseThrow(() -> new IOException("缺少待确认的 VM Bundle"));
        require(current.scopeId().equals(ack.scopeId()) && current.transferId().equals(ack.transferId())
                && current.phase().ordinal() >= TransferCompletionStore.Phase.WAIT_FINAL_ACK.ordinal(), "最终回执与待确认 Bundle 不匹配");
        if (current.phase() == TransferCompletionStore.Phase.COMPLETE) return;
        String finalFrame = new TransferSessionStore(workspace).readFrame(ack.session());
        records.advance(new TransferCompletionStore.Record(ack.session(), ack.scopeId(), ack.transferId(), ack.kind(), ack.direction(),
                TransferCompletionStore.Phase.COMPLETE, finalFrame.getBytes(StandardCharsets.UTF_8), System.currentTimeMillis()));
        new TransferSessionStore(workspace).clearSession(ack.session());
    }
}
