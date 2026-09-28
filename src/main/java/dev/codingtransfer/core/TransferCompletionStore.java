package dev.codingtransfer.core;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Bounded, small completion/duplicate-response records; never stores project or file contents. */
public final class TransferCompletionStore {
    private static final int MAGIC = 0x45534352;
    private static final int VERSION = 1;
    private static final int MAX_RECORDS = 512;
    private static final int MAX_RESPONSE = 64 * 1024;
    private final Path root;

    public enum Phase { RECEIVED_DURABLE, BUSINESS_APPLIED, WAIT_FINAL_ACK, COMPLETE }
    public record Record(String session, String scopeId, String transferId, ReliableTransferEngine.Kind kind,
                         ReliableTransferEngine.Direction direction, Phase phase, byte[] response, long updatedAt) {
        public Record { response = response == null ? new byte[0] : response.clone(); }
        @Override public byte[] response() { return response.clone(); }
    }

    public TransferCompletionStore(Path workspace) throws IOException {
        root = SafeFiles.state(workspace).resolve("transfer-completions");
        SafeFiles.noLinks(root); Files.createDirectories(root); SafeFiles.noLinks(root);
    }

    public synchronized void advance(Record next) throws IOException {
        validate(next);
        Path path = path(next.session());
        Record old = Files.exists(path) ? decode(SafeFiles.read(path, 128 * 1024)) : null;
        if (old == null) {
            if (countRecords() >= MAX_RECORDS) cleanupExpired(Instant.now());
            if (countRecords() >= MAX_RECORDS) throw new IOException("待确认传输记录已达上限，请先完成或清理已完成记录");
        } else {
            if (!sameIdentity(old, next)) throw new IOException("传输完成记录身份不一致");
            if (next.phase().ordinal() < old.phase().ordinal()) throw new IOException("传输完成状态不能回退");
            if (next.phase() == old.phase() && old.response().length > 0 && !Arrays.equals(old.response(), next.response()))
                throw new IOException("同一传输状态的完成回执不能替换");
            if (next.response().length == 0 && old.response().length > 0)
                next = new Record(next.session(), next.scopeId(), next.transferId(), next.kind(), next.direction(),
                        next.phase(), old.response(), next.updatedAt());
        }
        SafeFiles.atomicWrite(path, encode(next));
    }

    public synchronized Optional<Record> find(String session) throws IOException {
        Path path = path(session);
        return Files.exists(path) ? Optional.of(decode(SafeFiles.read(path, 128 * 1024))) : Optional.empty();
    }
    public synchronized Optional<Record> findLatest(ReliableTransferEngine.Kind kind,String scopeId,ReliableTransferEngine.Direction direction,Phase phase)throws IOException{
        Record latest=null;try(var files=Files.list(root)){for(Path file:files.toList()){SafeFiles.noLinks(file);if(!Files.isRegularFile(file)||!file.getFileName().toString().matches("[0-9a-f-]{36}\\.record"))throw new IOException("完成记录目录含未知文件");Record r=decode(SafeFiles.read(file,128*1024));if(r.kind()==kind&&r.scopeId().equals(scopeId)&&r.direction()==direction&&r.phase()==phase&&(latest==null||r.updatedAt()>latest.updatedAt()))latest=r;}}return Optional.ofNullable(latest);
    }

    /** Removes only expired COMPLETE records; unfinished protocol decisions are never pruned. */
    public synchronized int cleanupExpired(Instant now) throws IOException {
        int removed = 0;
        try (var files = Files.list(root)) {
            for (Path file : files.toList()) {
                SafeFiles.noLinks(file);
                if (!Files.isRegularFile(file) || !file.getFileName().toString().matches("[0-9a-f-]{36}\\.record"))
                    throw new IOException("完成记录目录含未知文件，拒绝清理");
                Record record = decode(SafeFiles.read(file, 128 * 1024));
                if (record.phase() == Phase.COMPLETE && record.updatedAt() < now.minus(Duration.ofDays(30)).toEpochMilli()) {
                    Files.delete(file); removed++;
                }
            }
        }
        return removed;
    }

    private int countRecords() throws IOException {
        try (var files = Files.list(root)) {
            int count = 0;
            for (Path file : files.toList()) {
                SafeFiles.noLinks(file);
                if (!Files.isRegularFile(file) || !file.getFileName().toString().matches("[0-9a-f-]{36}\\.record"))
                    throw new IOException("完成记录目录含未知文件");
                count++;
            }
            return count;
        }
    }

    private Path path(String session) throws IOException {
        try { if (!UUID.fromString(session).toString().equals(session)) throw new IllegalArgumentException(); }
        catch (RuntimeException e) { throw new IOException("无效传输会话", e); }
        Path path = root.resolve(session + ".record"); SafeFiles.noLinks(path); return path;
    }

    private static void validate(Record record) throws IOException {
        if (record == null || record.scopeId() == null || !record.scopeId().matches("[A-Za-z0-9_.:-]{1,128}")
                || record.transferId() == null || !record.transferId().matches("[A-Za-z0-9_.:-]{1,128}")
                || record.kind() == null || record.direction() == null || record.phase() == null
                || record.updatedAt() <= 0 || record.response().length > MAX_RESPONSE)
            throw new IOException("传输完成记录字段无效");
        if (record.direction() == ReliableTransferEngine.Direction.HOST_TO_VM
                && record.phase() != Phase.RECEIVED_DURABLE && record.phase() != Phase.COMPLETE)
            throw new IOException("HOST 到 VM 只允许持久接收和传输完成状态");
        if (record.direction() != ReliableTransferEngine.Direction.HOST_TO_VM && record.phase() == Phase.RECEIVED_DURABLE)
            throw new IOException("VM 到 HOST / 文件接收必须先完成人工授权的业务动作");
        if (record.phase() == Phase.BUSINESS_APPLIED || record.phase() == Phase.WAIT_FINAL_ACK) {
            if (record.response().length == 0) throw new IOException("业务完成状态必须保留业务 ACK");
        }
        try { if (!UUID.fromString(record.session()).toString().equals(record.session())) throw new IllegalArgumentException(); }
        catch (RuntimeException e) { throw new IOException("无效传输会话", e); }
    }

    private static boolean sameIdentity(Record a, Record b) {
        return a.session().equals(b.session()) && a.scopeId().equals(b.scopeId()) && a.transferId().equals(b.transferId())
                && a.kind() == b.kind() && a.direction() == b.direction();
    }

    private static byte[] encode(Record record) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeInt(MAGIC); out.writeByte(VERSION); out.writeUTF(record.session()); out.writeUTF(record.scopeId());
            out.writeUTF(record.transferId()); out.writeByte(record.kind().ordinal()); out.writeByte(record.direction().ordinal());
            out.writeByte(record.phase().ordinal()); out.writeLong(record.updatedAt()); byte[] response = record.response();
            out.writeInt(response.length); out.write(response);
        }
        return bytes.toByteArray();
    }

    private static Record decode(byte[] bytes) throws IOException {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            if (in.readInt() != MAGIC || in.readUnsignedByte() != VERSION) throw new IOException("完成记录版本无效");
            String session = in.readUTF(), scope = in.readUTF(), transfer = in.readUTF();
            int kind = in.readUnsignedByte(), direction = in.readUnsignedByte(), phase = in.readUnsignedByte(); long updated = in.readLong();
            int length = in.readInt();
            if (kind >= ReliableTransferEngine.Kind.values().length || direction >= ReliableTransferEngine.Direction.values().length
                    || phase >= Phase.values().length || length < 0 || length > MAX_RESPONSE || length != in.available())
                throw new IOException("完成记录长度/字段无效");
            Record result = new Record(session, scope, transfer, ReliableTransferEngine.Kind.values()[kind],
                    ReliableTransferEngine.Direction.values()[direction], Phase.values()[phase], in.readNBytes(length), updated);
            validate(result); return result;
        } catch (java.io.EOFException e) { throw new IOException("完成记录被截断", e); }
    }
}
