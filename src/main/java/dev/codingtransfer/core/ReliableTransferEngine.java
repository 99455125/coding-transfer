package dev.codingtransfer.core;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.UUID;

/** Encrypted stop-and-wait block protocol. Durable inbox writes are supplied by the inner-protocol adapter. */
public final class ReliableTransferEngine {
    private static final int MAGIC = 0x4553594e;
    private static final int VERSION = 1;
    private final String identity;
    private final byte[] key;
    private final TransferSessionStore store;

    public static final class RejectedFrame extends IOException {public RejectedFrame(String message){super(message);}}

    public enum Kind { PROJECT, FILE }
    public enum Direction { HOST_TO_VM, VM_TO_HOST, FILE }
    public enum Type { INVITE, READY, DATA, BLOCK_ACK, MISSING, RECEIVED, BUSINESS_ACK, FINAL, FINAL_ACK, CANCEL, ERROR, REQUEST }
    public enum AckDisposition { ADVANCED, DUPLICATE }
    public record AckResult(AckDisposition disposition,int acknowledgedIndex,int nextIndex) {}
    public enum InviteDisposition { ACCEPTED, DUPLICATE_READY, ALREADY_ADVANCED }
    public record InviteResult(InviteDisposition disposition,String readyFrame) {}
    public enum ReadyDisposition { ACCEPTED, DUPLICATE }
    public record ReadyResult(ReadyDisposition disposition,int awaitingDataIndex) {}

    public record Message(String session, String scopeId, String transferId, Kind kind, Direction direction, Type type,
                          int index, int total, byte[] payload) {
        public Message {
            try {
                if (!UUID.fromString(session).toString().equals(session)) throw new IllegalArgumentException("会话 ID 无效");
            } catch (RuntimeException e) { throw new IllegalArgumentException("会话 ID 无效", e); }
            if (scopeId == null || !scopeId.matches("[A-Za-z0-9_.:-]{1,128}")
                    || transferId == null || !transferId.matches("[A-Za-z0-9_.:-]{1,128}")
                    || kind == null || direction == null || type == null || total < 1 || total > 1_000_000)
                throw new IllegalArgumentException("传输消息字段无效");
            if ((type == Type.DATA || type == Type.BLOCK_ACK || type == Type.MISSING || type == Type.REQUEST) ? index < 1 || index > total : index != 0)
                throw new IllegalArgumentException("传输块序号无效");
            if (payload == null || payload.length > TransferLimits.MAX_PACKET_CHARS)
                throw new IllegalArgumentException("传输消息数据为空或过大");
            if (type != Type.DATA && type != Type.BUSINESS_ACK && payload.length != 0)
                throw new IllegalArgumentException("此控制消息不允许携带数据");
            payload = payload.clone();
        }
        @Override public byte[] payload() { return payload.clone(); }
        @Override public boolean equals(Object other) {
            return other instanceof Message m && session.equals(m.session) && scopeId.equals(m.scopeId)
                    && transferId.equals(m.transferId) && kind == m.kind && direction == m.direction
                    && type == m.type && index == m.index && total == m.total && Arrays.equals(payload, m.payload);
        }
        @Override public int hashCode() { return 31 * java.util.Objects.hash(session, scopeId, transferId, kind, direction, type, index, total) + Arrays.hashCode(payload); }
    }

    @FunctionalInterface public interface BlockSink { void persist(Message data) throws IOException; }

    private ReliableTransferEngine(String identity, byte[] key, TransferSessionStore store) throws IOException {
        if (identity == null || !identity.matches("[A-Za-z0-9_-]{1,128}")) throw new IOException("传输识别串格式无效");
        if (key == null || key.length != 32) throw new IOException("共享密钥必须为 256 位");
        this.identity = identity;
        this.key = key.clone();
        this.store = store;
    }

    public static ReliableTransferEngine sender(String identity, byte[] key, TransferSessionStore store) throws IOException {
        return new ReliableTransferEngine(identity, key, store);
    }

    public static ReliableTransferEngine receiver(String identity, byte[] key, TransferSessionStore store) throws IOException {
        return new ReliableTransferEngine(identity, key, store);
    }

    public static String encode(String identity, byte[] key, Message message) throws IOException {
        return EncryptedFrameCodec.encode(identity, key, serialize(message));
    }

    public static Message decode(String identity, byte[] key, String frame) throws IOException {
        return deserialize(EncryptedFrameCodec.decode(identity, key, frame).plaintext());
    }

    /** Creates exactly one frame. Repeated calls with the same message return the stored ciphertext. */
    public String prepare(Message message) throws IOException {
        if(message.type()==Type.INVITE)store.saveContext(new TransferSessionStore.Context(message.session(),message.scopeId(),message.transferId(),message.kind(),message.direction(),message.total()));
        if (message.type() == Type.DATA && nextIndex(message.session()) != message.index())
            throw new IOException("发送游标与待发送块号不一致");
        if (store.hasFrame(message.session())) {
            String existing = store.readFrame(message.session());
            if (!decode(identity, key, existing).equals(message)) throw new IOException("当前传输帧尚未确认，不能前进");
            if(message.kind()==Kind.FILE&&message.type()==Type.DATA)recordPrepared(message);
            return existing;
        }
        String frame = encode(identity, key, message);
        store.saveFrame(message.session(), frame);
        if(message.kind()==Kind.FILE&&message.type()==Type.DATA)recordPrepared(message);
        return frame;
    }

    public String retry(String session) throws IOException { return store.readFrame(session); }

    /** Replaces the final block ACK only after durable validation/application has produced a completion response. */
    public String prepareCompletion(Message response) throws IOException {
        if (response.type() != Type.RECEIVED && response.type() != Type.BUSINESS_ACK
                && response.type() != Type.FINAL && response.type() != Type.FINAL_ACK)
            throw new IOException("不是传输完成消息");
        if (store.hasFrame(response.session())) {
            String current = store.readFrame(response.session());
            Message pending = decode(identity, key, current);
            if (pending.equals(response)) return current;
            boolean finalBlock = pending.type() == Type.BLOCK_ACK && (pending.kind()==Kind.FILE?fileProgress(response).confirmed.nextClearBit(1)>response.total():pending.index()==pending.total());
            boolean savedFileAck = pending.type() == Type.BUSINESS_ACK && response.type() == Type.FINAL_ACK
                    && pending.kind() == Kind.FILE;
            if ((!finalBlock && !savedFileAck) || !sameTransfer(pending, response))
                throw new IOException("最终块尚未持久确认，不能替换待确认帧");
            store.clearFrame(response.session());
        }
        return prepare(response);
    }

    /** Receiver-side explicit start: read the existing INVITE and respond READY. */
    public String acceptInviteAndReady(String frame) throws IOException {
        InviteResult result=acceptInviteResult(frame);
        if(result.readyFrame()==null)throw new IOException("邀请重复到达，但接收端已推进到数据阶段");
        return result.readyFrame();
    }

    public InviteResult acceptInviteResult(String frame) throws IOException {
        Message invite = decode(identity, key, frame);
        if (invite.type() != Type.INVITE) throw new RejectedFrame("接收需要先收到发送端邀请");
        TransferSessionStore.Context current=store.currentContext();
        if(current!=null&&(!current.session().equals(invite.session())||!current.scopeId().equals(invite.scopeId())
                ||!current.transferId().equals(invite.transferId())||current.kind()!=invite.kind()
                ||current.direction()!=invite.direction()||current.total()!=invite.total()))
            throw new RejectedFrame("已有其他传输会话正在进行");
        if (store.hasFrame(invite.session())) {
            String previous = store.readFrame(invite.session());
            Message ready = decode(identity, key, previous);
            if (ready.type() == Type.READY && sameTransfer(ready, invite)) return new InviteResult(InviteDisposition.DUPLICATE_READY,previous);
            if (sameTransfer(ready,invite))return new InviteResult(InviteDisposition.ALREADY_ADVANCED,null);
        }
        if (store.hasState(invite.session()))return new InviteResult(InviteDisposition.ALREADY_ADVANCED,null);
        store.saveContext(new TransferSessionStore.Context(invite.session(),invite.scopeId(),invite.transferId(),invite.kind(),invite.direction(),invite.total()));
        store.saveState(invite.session(), state(1));
        return new InviteResult(InviteDisposition.ACCEPTED,prepare(new Message(invite.session(), invite.scopeId(), invite.transferId(), invite.kind(), invite.direction(),
                Type.READY, 0, invite.total(), new byte[0])));
    }

    /** Pull-mode receiver: the durable current request owns all block retries. */
    public InviteResult acceptPullInviteResult(String frame)throws IOException {
        Message invite=decode(identity,key,frame);
        if(invite.type()!=Type.INVITE)throw new RejectedFrame("接收需要先收到发送端邀请");
        TransferSessionStore.Context context=store.currentContext();
        if(context!=null)requireContext(invite);
        else {
            store.saveContext(new TransferSessionStore.Context(invite.session(),invite.scopeId(),invite.transferId(),invite.kind(),invite.direction(),invite.total()));
            store.saveState(invite.session(),state(1));
        }
        if(store.hasFrame(invite.session())) {
            Message pending=decode(identity,key,store.readFrame(invite.session()));
            if(!sameTransfer(pending,invite))throw new RejectedFrame("邀请与待处理帧不匹配");
            if(pending.type()!=Type.REQUEST&&pending.type()!=Type.READY&&pending.type()!=Type.BLOCK_ACK)
                return new InviteResult(InviteDisposition.ALREADY_ADVANCED,null);
        }
        int next=nextIndex(invite.session());
        if(next>invite.total())return new InviteResult(InviteDisposition.ALREADY_ADVANCED,null);
        return new InviteResult(context==null?InviteDisposition.ACCEPTED:InviteDisposition.DUPLICATE_READY,saveRequest(invite,next));
    }

    /** REQUEST(n) acknowledges only the previously prepared contiguous prefix and asks for n. */
    public int acceptRequest(String frame)throws IOException {
        Message request=decode(identity,key,frame);
        if(request.type()!=Type.REQUEST)throw new RejectedFrame("收到的不是数据请求");
        requireContext(request);
        int cursor=nextIndex(request.session());
        Message pending=store.hasFrame(request.session())?decode(identity,key,store.readFrame(request.session())):null;
        if(request.index()<cursor) {
            // Legacy final BLOCK_ACK (or a crash before FINAL creation) may leave no pending DATA.
            // Only an explicit request for the last block may reopen this receipt-wait state.
            if(pending==null&&cursor==request.total()+1&&request.index()==request.total()) {
                if(request.kind()==Kind.FILE) {
                    FileProgress progress=fileProgress(request);
                    if(progress.prepared.nextClearBit(1)<=request.total())throw new RejectedFrame("末块恢复包含从未发送的文件块");
                    progress.confirmed.clear(request.total());saveFileProgress(request.session(),progress);
                }else store.saveState(request.session(),state(request.total()));
                return request.index();
            }
            throw new RejectedFrame("忽略已过期的数据请求");
        }
        if(pending!=null&&!sameTransfer(pending,request))throw new RejectedFrame("请求与待发送帧不匹配");
        if(pending!=null&&pending.type()==Type.INVITE) {
            if(request.index()!=1||cursor!=1)throw new RejectedFrame("首次请求必须从第 1 块开始");
            store.saveState(request.session(),state(1));store.clearFrame(request.session());return 1;
        }
        if(pending!=null&&pending.type()!=Type.DATA)throw new RejectedFrame("当前状态不接受数据请求");
        if(pending!=null&&request.index()==pending.index())return request.index();
        if(pending==null) {
            if(request.index()!=cursor)throw new RejectedFrame("请求不能跳过未发送的数据块");
            return cursor;
        }
        if(request.kind()==Kind.FILE) {
            FileProgress progress=fileProgress(request);
            if(progress.prepared.nextClearBit(1)<request.index())throw new RejectedFrame("请求确认了从未发送的文件块");
            progress.confirmed.set(1,request.index());saveFileProgress(request.session(),progress);
        }else {
            if(request.index()!=pending.index()+1||request.index()>cursor+1)
                throw new RejectedFrame("请求不能跳过未发送的数据块");
            store.saveState(request.session(),state(request.index()));
        }
        store.clearFrame(request.session());return request.index();
    }

    /** Persist first, then request the smallest missing block; stale DATA cannot rewind that request. */
    public String acceptRequestedData(String frame,BlockSink sink)throws IOException {
        Message data=decode(identity,key,frame);
        if(data.type()!=Type.DATA)throw new RejectedFrame("需要数据块消息");
        requireContext(data);
        Message pending=store.hasFrame(data.session())?decode(identity,key,store.readFrame(data.session())):null;
        if(pending!=null&&(!sameTransfer(pending,data)||!java.util.Set.of(Type.REQUEST,Type.READY,Type.BLOCK_ACK).contains(pending.type())))
            throw new RejectedFrame("接收端仍有未确认控制消息");
        int next=nextIndex(data.session());
        if(data.index()>next)throw new RejectedFrame("数据块不是接收端当前请求的块");
        sink.persist(data); // Inner inbox validates duplicate bytes; failure emits no request/ACK.
        if(data.kind()==Kind.FILE) {
            FileProgress progress=fileProgress(data);progress.confirmed.set(data.index());saveFileProgress(data.session(),progress);
            next=progress.confirmed.nextClearBit(1);
        }else if(data.index()==next) {next++;store.saveState(data.session(),state(next));}
        if(next<=data.total())return saveRequest(data,next);
        Message ack=new Message(data.session(),data.scopeId(),data.transferId(),data.kind(),data.direction(),Type.BLOCK_ACK,data.total(),data.total(),new byte[0]);
        return saveControl(ack);
    }

    /** Recover receiver control from durable coverage if interrupted between state and frame writes. */
    public String resumePullReceiver()throws IOException {
        var context=store.currentContext();if(context==null)return null;
        if(store.hasFrame(context.session())) {
            String frame=store.readFrame(context.session());Message pending=decode(identity,key,frame);
            requireContext(pending);
            if(!java.util.Set.of(Type.REQUEST,Type.READY,Type.BLOCK_ACK).contains(pending.type()))return frame;
        }
        int next=nextIndex(context.session());
        Message control=new Message(context.session(),context.scopeId(),context.transferId(),context.kind(),context.direction(),
                Type.REQUEST,next<=context.total()?next:context.total(),context.total(),new byte[0]);
        return saveControl(control);
    }

    private String saveRequest(Message transfer,int index)throws IOException {
        return saveControl(new Message(transfer.session(),transfer.scopeId(),transfer.transferId(),transfer.kind(),transfer.direction(),Type.REQUEST,index,transfer.total(),new byte[0]));
    }
    private String saveControl(Message message)throws IOException {
        if(store.hasFrame(message.session())) {
            String previous=store.readFrame(message.session());if(decode(identity,key,previous).equals(message))return previous;
        }
        String frame=encode(identity,key,message);store.clearFrame(message.session());store.saveFrame(message.session(),frame);return frame;
    }
    private void requireContext(Message message)throws IOException {
        TransferSessionStore.Context context=store.currentContext();
        if(context==null||!context.session().equals(message.session())||!context.scopeId().equals(message.scopeId())
                ||!context.transferId().equals(message.transferId())||context.kind()!=message.kind()
                ||context.direction()!=message.direction()||context.total()!=message.total())
            throw new RejectedFrame("消息与已接受传输会话不匹配");
    }

    /** Sender advances from INVITE to data only after a matching READY. */
    public void acceptReady(String frame, String session) throws IOException {
        acceptReadyResult(frame,session);
    }

    public ReadyResult acceptReadyResult(String frame,String session)throws IOException{
        Message ready = decode(identity, key, frame);
        if(ready.type()!=Type.READY)throw new RejectedFrame("收到的不是 READY");
        if(!store.hasFrame(session)){
            TransferSessionStore.Context current=store.currentContext();
            if(current!=null&&current.session().equals(session)&&current.scopeId().equals(ready.scopeId())
                    &&current.transferId().equals(ready.transferId())&&current.kind()==ready.kind()
                    &&current.direction()==ready.direction()&&current.total()==ready.total())
                return new ReadyResult(ReadyDisposition.DUPLICATE,nextIndex(session));
            throw new IOException("没有等待 READY 的邀请");
        }
        Message invite = decode(identity, key, store.readFrame(session));
        if(invite.type()==Type.DATA&&sameTransfer(invite,ready))return new ReadyResult(ReadyDisposition.DUPLICATE,invite.index());
        if (invite.type() != Type.INVITE || !sameTransfer(invite, ready)) throw new RejectedFrame("READY 与当前邀请不匹配");
        store.saveState(session, state(1));
        store.clearFrame(session);
        return new ReadyResult(ReadyDisposition.ACCEPTED,1);
    }

    /** Persists through the existing project/file inbox before creating the block ACK. */
    public String acceptData(String frame, BlockSink sink) throws IOException {
        Message incoming = decode(identity, key, frame);
        if (incoming.type() != Type.DATA) throw new IOException("需要数据块消息");
        if(incoming.kind()==Kind.FILE)return acceptFileData(incoming,sink);
        boolean hasPending = store.hasFrame(incoming.session());
        if (hasPending) {
            Message pending = decode(identity, key, store.readFrame(incoming.session()));
            if (pending.type() == Type.READY && sameTransfer(pending, incoming) && incoming.index() == 1) {
                store.clearFrame(incoming.session());
                hasPending = false;
            } else if (pending.type() != Type.BLOCK_ACK || !sameTransfer(pending, incoming)) {
                throw new RejectedFrame("接收端仍有未确认控制消息");
            }
        }
        if (hasPending) {
            Message pending = decode(identity, key, store.readFrame(incoming.session()));
            if (incoming.index() < pending.index()) {
                sink.persist(incoming);
                return encode(identity, key, new Message(incoming.session(), incoming.scopeId(), incoming.transferId(),
                        incoming.kind(), incoming.direction(), Type.BLOCK_ACK, incoming.index(), incoming.total(), new byte[0]));
            }
            if (incoming.index() > pending.index() + 1)
                return missing(pending, pending.index() + 1);
            sink.persist(incoming);
            if (incoming.index() == pending.index()) return store.readFrame(incoming.session());
            store.saveState(incoming.session(), state(incoming.index() + 1));
            store.clearFrame(incoming.session());
        } else {
            int next = nextIndex(incoming.session());
            if (incoming.index() > next) return missing(incoming, next);
            sink.persist(incoming);
            if (incoming.index() < next) return encode(identity, key, new Message(incoming.session(), incoming.scopeId(), incoming.transferId(),
                    incoming.kind(), incoming.direction(), Type.BLOCK_ACK, incoming.index(), incoming.total(), new byte[0]));
            store.saveState(incoming.session(), state(next + 1));
        }
        Message ack = new Message(incoming.session(), incoming.scopeId(), incoming.transferId(), incoming.kind(), incoming.direction(), Type.BLOCK_ACK,
                incoming.index(), incoming.total(), new byte[0]);
        return prepare(ack);
    }

    /** Clears the current DATA only when a matching encrypted ACK arrives. */
    public int acceptBlockAck(String frame) throws IOException {
        return acceptBlockAckResult(frame).acknowledgedIndex();
    }

    public AckResult acceptBlockAckResult(String frame) throws IOException {
        Message ack = decode(identity, key, frame);
        if (ack.type() != Type.BLOCK_ACK) throw new RejectedFrame("收到的不是块确认");
        if(ack.kind()==Kind.FILE)return acceptFileBlockAck(ack);
        int cursor = nextIndex(ack.session());
        if (!store.hasFrame(ack.session())) {
            if (ack.index() < cursor) return new AckResult(AckDisposition.DUPLICATE,ack.index(),cursor);
            throw new RejectedFrame("没有等待确认的数据块");
        }
        Message pending = decode(identity, key, store.readFrame(ack.session()));
        if (pending.type() != Type.DATA || !sameTransfer(pending, ack))
            throw new RejectedFrame("块确认与当前待发送块不匹配");
        if (ack.index() < pending.index() && ack.index() < cursor) return new AckResult(AckDisposition.DUPLICATE,ack.index(),cursor);
        if (pending.index() != ack.index()) throw new RejectedFrame("块确认与当前待发送块不匹配");
        if (cursor <= ack.index()) store.saveState(ack.session(), state(ack.index() + 1));
        store.clearFrame(ack.session());
        return new AckResult(AckDisposition.ADVANCED,ack.index(),ack.index()+1);
    }

    public void acceptReceived(String frame, String session, String scopeId, String transferId) throws IOException {
        Message received = decode(identity, key, frame);
        if (received.type() != Type.RECEIVED || !received.session().equals(session)
                || !received.scopeId().equals(scopeId) || !received.transferId().equals(transferId))
            throw new IOException("接收完成回执与当前项目包不匹配");
        int cursor = nextIndex(session);
        boolean finalDataPending = false;
        if (store.hasFrame(session)) {
            Message pending = decode(identity, key, store.readFrame(session));
            if(pending.type()==Type.FINAL&&sameTransfer(pending,received))return;
            if (pending.type() == Type.DATA && pending.index() == pending.total() && sameTransfer(pending, received)) {
                finalDataPending = true;
                store.saveState(session, state(received.total() + 1));
                store.clearFrame(session);
            } else if (pending.type() != Type.DATA || !sameTransfer(pending, received))
                throw new IOException("接收完成回执与待发送状态不匹配");
        }
        if (cursor <= received.total() && !(cursor == received.total() && finalDataPending))
            throw new IOException("项目块尚未全部确认");
    }

    /** File sender accepts the receiver's verified-reception ACK after all DATA blocks were confirmed. */
    public void validateBusinessAck(String frame, String session, String scopeId, String transferId) throws IOException {
        Message ack = decode(identity, key, frame);
        if (ack.type() != Type.BUSINESS_ACK || !ack.session().equals(session) || !ack.scopeId().equals(scopeId)
                || !ack.transferId().equals(transferId)) throw new IOException("业务回执与当前传输不匹配");
        if(ack.kind()==Kind.FILE){
            FileProgress progress=fileProgress(ack);
            if(progress.prepared.nextClearBit(1)<=ack.total())throw new IOException("仍有从未发送的文件块");
            if(store.hasFrame(session)){Message pending=decode(identity,key,store.readFrame(session));if((pending.type()!=Type.DATA&&pending.type()!=Type.FINAL)||!sameTransfer(pending,ack))throw new IOException("业务回执与待发送状态不匹配");}
            return;
        }
        int cursor = nextIndex(session);
        if (store.hasFrame(session)) {
            Message pending = decode(identity, key, store.readFrame(session));
            if(pending.type()==Type.FINAL&&sameTransfer(pending,ack))return;
            if (pending.type() != Type.DATA || pending.index() != pending.total() || !sameTransfer(pending, ack)
                    || cursor != ack.total())
                throw new IOException("业务回执与待发送状态不匹配");
        } else if (cursor <= ack.total()) {
            throw new IOException("文件块尚未全部确认");
        }
    }

    /** Consumes the final unacknowledged DATA only after its business receipt has been validated. */
    public void acceptBusinessAck(String frame, String session, String scopeId, String transferId) throws IOException {
        validateBusinessAck(frame, session, scopeId, transferId);
        Message accepted=decode(identity,key,frame);
        if(store.hasFrame(session)&&decode(identity,key,store.readFrame(session)).type()==Type.FINAL)return;
        if(accepted.kind()==Kind.FILE){
            FileProgress progress=fileProgress(accepted);progress.confirmed.set(1,accepted.total()+1);saveFileProgress(session,progress);store.clearFrame(session);return;
        }
        int cursor = nextIndex(session);
        if (store.hasFrame(session)) {
            store.clearFrame(session);
            store.saveState(session, state(cursor + 1));
        }
    }

    public int acceptMissing(String frame) throws IOException {
        Message missing = decode(identity, key, frame);
        if (missing.type() != Type.MISSING || !store.hasFrame(missing.session())) throw new IOException("没有等待补传的数据块");
        Message pending = decode(identity, key, store.readFrame(missing.session()));
        if (pending.type() != Type.DATA || !sameTransfer(pending, missing) || missing.index() > pending.index())
            throw new RejectedFrame("缺块请求与当前传输不匹配");
        if(missing.kind()==Kind.FILE){
            FileProgress progress=fileProgress(missing);if(!progress.prepared.get(missing.index()))throw new RejectedFrame("未发送的文件块不可补传");
            progress.confirmed.clear(missing.index());saveFileProgress(missing.session(),progress);
        }else store.saveState(missing.session(), state(missing.index()));
        store.clearFrame(missing.session());
        return missing.index();
    }

    private String missing(Message transfer, int missingIndex) throws IOException {
        Message request = new Message(transfer.session(), transfer.scopeId(), transfer.transferId(), transfer.kind(),
                transfer.direction(), Type.MISSING, missingIndex, transfer.total(), new byte[0]);
        // A missing request is an ephemeral response when a different ACK is already cached.
        if (store.hasFrame(transfer.session())) return encode(identity, key, request);
        return prepare(request);
    }

    public int nextIndex(String session) throws IOException {
        if (!store.hasState(session)) return 1;
        byte[] bytes = store.readState(session);
        if(bytes.length>=4&&java.nio.ByteBuffer.wrap(bytes).getInt()==FILE_STATE_MAGIC)return decodeFileProgress(bytes).confirmed.nextClearBit(1);
        String value = new String(bytes, StandardCharsets.US_ASCII);
        if (!value.matches("NEXT:[1-9][0-9]{0,6}")) throw new IOException("传输游标状态损坏");
        try { return Integer.parseInt(value.substring(5)); }
        catch (NumberFormatException e) { throw new IOException("传输游标状态无效", e); }
    }

    private static final int FILE_STATE_MAGIC=0x46425331;
    private record FileProgress(int total,java.util.BitSet prepared,java.util.BitSet confirmed){}
    private FileProgress fileProgress(Message m)throws IOException{
        var c=store.currentContext();
        if(c==null||!c.session().equals(m.session())||!c.scopeId().equals(m.scopeId())||!c.transferId().equals(m.transferId())
                ||c.kind()!=m.kind()||c.direction()!=m.direction()||c.total()!=m.total())throw new RejectedFrame("文件帧与会话上下文不匹配");
        if(store.hasState(m.session())){
            byte[] bytes=store.readState(m.session());
            if(bytes.length>=4&&java.nio.ByteBuffer.wrap(bytes).getInt()==FILE_STATE_MAGIC){
                FileProgress p=decodeFileProgress(bytes);if(p.total()!=m.total())throw new IOException("文件块状态数量不一致");return p;
            }
        }
        // Legacy NEXT state is a contiguous prefix. Persist sparse state on the next update.
        int next=nextIndex(m.session());if(next>m.total()+1)throw new IOException("文件块状态越界");
        java.util.BitSet prepared=new java.util.BitSet(),confirmed=new java.util.BitSet();
        confirmed.set(1,next);prepared.or(confirmed);
        if(store.hasFrame(m.session())){
            Message pending=decode(identity,key,store.readFrame(m.session()));
            if(pending.type()==Type.DATA&&sameTransfer(pending,m))prepared.set(pending.index());
        }
        return new FileProgress(m.total(),prepared,confirmed);
    }
    private void recordPrepared(Message data)throws IOException{
        FileProgress p=fileProgress(data);p.prepared.set(data.index());saveFileProgress(data.session(),p);
    }
    private String acceptFileData(Message data,BlockSink sink)throws IOException{
        FileProgress progress=fileProgress(data);
        Message pending=store.hasFrame(data.session())?decode(identity,key,store.readFrame(data.session())):null;
        if(pending!=null&&(!sameTransfer(pending,data)||(pending.type()!=Type.READY&&pending.type()!=Type.BLOCK_ACK)))
            throw new RejectedFrame("接收端仍有未确认控制消息");
        sink.persist(data); // Existing file inbox validates duplicate bytes before ACK.
        progress.confirmed.set(data.index());saveFileProgress(data.session(),progress);
        if(pending!=null&&pending.type()==Type.BLOCK_ACK&&pending.index()==data.index())return store.readFrame(data.session());
        store.clearFrame(data.session());
        return prepare(new Message(data.session(),data.scopeId(),data.transferId(),data.kind(),data.direction(),Type.BLOCK_ACK,data.index(),data.total(),new byte[0]));
    }
    private AckResult acceptFileBlockAck(Message ack)throws IOException{
        FileProgress p=fileProgress(ack);
        if(!p.prepared.get(ack.index()))throw new RejectedFrame("ACK 对应的文件块从未发送");
        boolean duplicate=p.confirmed.get(ack.index());
        Message pending=store.hasFrame(ack.session())?decode(identity,key,store.readFrame(ack.session())):null;
        if(pending!=null&&(!sameTransfer(pending,ack)||pending.type()!=Type.DATA))throw new RejectedFrame("块确认与当前待发送状态不匹配");
        p.confirmed.set(ack.index());saveFileProgress(ack.session(),p);
        if(pending!=null&&pending.index()==ack.index())store.clearFrame(ack.session());
        return new AckResult(duplicate?AckDisposition.DUPLICATE:AckDisposition.ADVANCED,ack.index(),p.confirmed.nextClearBit(1));
    }
    private void saveFileProgress(String session,FileProgress p)throws IOException{
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        try(DataOutputStream out=new DataOutputStream(bytes)){
            out.writeInt(FILE_STATE_MAGIC);out.writeInt(p.total());
            for(java.util.BitSet bits:new java.util.BitSet[]{p.prepared,p.confirmed}){byte[] data=bits.toByteArray();out.writeInt(data.length);out.write(data);}
        }
        store.saveState(session,bytes.toByteArray());
    }
    private static FileProgress decodeFileProgress(byte[] bytes)throws IOException{
        try(DataInputStream in=new DataInputStream(new ByteArrayInputStream(bytes))){
            if(in.readInt()!=FILE_STATE_MAGIC)throw new IOException("文件块状态版本无效");
            int total=in.readInt();if(total<1||total>1_000_000)throw new IOException("文件块状态数量无效");
            java.util.BitSet[] sets=new java.util.BitSet[2];
            for(int i=0;i<2;i++){
                int length=in.readInt();if(length<0||length>(total+8)/8)throw new IOException("文件块集合长度无效");
                byte[] value=in.readNBytes(length);if(value.length!=length)throw new IOException("文件块集合不完整");
                sets[i]=java.util.BitSet.valueOf(value);if(sets[i].get(0)||sets[i].length()>total+1)throw new IOException("文件块集合越界");
            }
            if(in.read()!=-1)throw new IOException("文件块状态含多余内容");
            return new FileProgress(total,sets[0],sets[1]);
        }
    }

    private static byte[] state(int nextIndex) { return ("NEXT:" + nextIndex).getBytes(StandardCharsets.US_ASCII); }

    private static boolean sameTransfer(Message a, Message b) {
        return a.session().equals(b.session()) && a.scopeId().equals(b.scopeId())
                && a.transferId().equals(b.transferId()) && a.kind() == b.kind()
                && a.direction() == b.direction() && a.total() == b.total();
    }

    private static byte[] serialize(Message message) throws IOException {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream out = new DataOutputStream(bytes)) {
                out.writeInt(MAGIC); out.writeByte(VERSION); out.writeByte(message.kind().ordinal());
                out.writeByte(message.direction().ordinal()); out.writeByte(message.type().ordinal());
                out.writeUTF(message.session()); out.writeUTF(message.scopeId()); out.writeUTF(message.transferId());
                out.writeInt(message.index()); out.writeInt(message.total());
                byte[] payload = message.payload(); out.writeInt(payload.length); out.write(payload);
            }
            return bytes.toByteArray();
        } catch (RuntimeException e) { throw new IOException("无法编码传输消息", e); }
    }

    private static Message deserialize(byte[] bytes) throws IOException {
        if (bytes == null || bytes.length > TransferLimits.MAX_PACKET_CHARS + 128) throw new IOException("传输消息过大");
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            if (in.readInt() != MAGIC || in.readUnsignedByte() != VERSION) throw new IOException("不支持的传输消息版本");
            int kind = in.readUnsignedByte(), direction = in.readUnsignedByte(), type = in.readUnsignedByte();
            if (kind >= Kind.values().length || direction >= Direction.values().length || type >= Type.values().length)
                throw new IOException("传输消息类型无效");
            String session = in.readUTF(), scopeId = in.readUTF(), transferId = in.readUTF(); int index = in.readInt(), total = in.readInt(), length = in.readInt();
            if (length < 0 || length > TransferLimits.MAX_PACKET_CHARS || length != in.available()) throw new IOException("传输消息长度无效");
            byte[] payload = in.readNBytes(length);
            try { return new Message(session, scopeId, transferId, Kind.values()[kind], Direction.values()[direction], Type.values()[type], index, total, payload); }
            catch (IllegalArgumentException e) { throw new IOException("传输消息字段无效", e); }
        } catch (java.io.EOFException e) { throw new IOException("传输消息被截断", e); }
    }
}
