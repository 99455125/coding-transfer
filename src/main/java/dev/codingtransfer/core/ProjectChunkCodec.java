package dev.codingtransfer.core;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import static dev.codingtransfer.core.Model.*;

/** Project clipboard framing and per-block validation; no persistent state. */
final class ProjectChunkCodec {
    private ProjectChunkCodec() {}
    record Part(Map<String,String> fields, int index, int count, String data,int protocol) {
        String session(){return fields.get("SESSION");}
        String signature(){
            return "VMSYNC/"+protocol+"\nPARTS:"+count+"\n"+fields.entrySet().stream().filter(e -> !Set.of("PART","CHUNK256").contains(e.getKey()))
                .sorted(Map.Entry.comparingByKey()).map(e->e.getKey()+":"+e.getValue()+"\n").reduce("",String::concat);
        }
    }
    public static List<String> split(Payload payload, int chunkKb) throws IOException {
        require(chunkKb>=4 && chunkKb<=TransferLimits.MAX_CHUNK_KB,"分块大小范围 4–1048576 KiB");
        byte[] encoded=Codec.encode(payload);String data=Base64.getEncoder().encodeToString(encoded), checksum=hash(encoded),session=UUID.randomUUID().toString();
        int width=chunkKb*1024, count=(int)(((long)data.length()+width-1)/width);List<String> result=new ArrayList<>();
        boolean work=payload.type()==Type.WORKTREE,host=work||payload.type()==Type.ACK;
        for(int i=0;i<count;i++){
            String chunk=data.substring(i*width,(int)Math.min((i+1L)*width,data.length()));
            result.add("VMSYNC/"+payload.version()+"\nTYPE:"+payload.type()+"\nSOURCE:"+(host?"HOST":"VM")+"\nTARGET:"+(host?"VM":"HOST")
                +"\nPROJECT:"+payload.project()+"\nSESSION:"+session+"\nPART:"+(i+1)+"/"+count+"\nENCODING:GZIP+BASE64\nSHA256:"+checksum
                +"\nCHUNK256:"+hash(chunk.getBytes(StandardCharsets.US_ASCII))+"\n"+(work?"BASE:":"HEAD:")+payload.head()+"\nDATA:\n"+chunk);
        }
        return List.copyOf(result);
    }
    static Part parse(String text)throws IOException{
        require(text!=null && text.length()<=TransferLimits.MAX_PACKET_CHARS,"剪贴板为空或分块过大");
        text=text.strip().replace("\r\n","\n");int dataAt=text.indexOf("\nDATA:\n");
        require(dataAt>0 && dataAt<2048,"无效 VMSYNC 分块");String[] lines=text.substring(0,dataAt).split("\n");
        require(lines[0].matches("VMSYNC/[123]"),"不支持的剪贴板协议");int protocol=Integer.parseInt(lines[0].substring(7));Map<String,String> fields=new TreeMap<>();
        for(int i=1;i<lines.length;i++){
            int colon=lines[i].indexOf(':');require(colon>0,"无效分块头");
            require(fields.putIfAbsent(lines[i].substring(0,colon),lines[i].substring(colon+1))==null,"重复分块头");
        }
        String type=fields.get("TYPE");boolean work="WORKTREE".equals(type),host=work||"ACK".equals(type);
        require((protocol==1&&(work||"SNAPSHOT".equals(type)))||(protocol==2&&("SNAPSHOT".equals(type)||"DELTA".equals(type)||"ACK".equals(type)))||(protocol==3&&work),"无效 payload 类型/协议版本");
        require(fields.keySet().equals(Set.of("TYPE","SOURCE","TARGET","PROJECT","SESSION","PART","ENCODING","SHA256","CHUNK256",work?"BASE":"HEAD")),"未知/缺失分块头");
        require(fields.get("SOURCE").equals(host?"HOST":"VM") && fields.get("TARGET").equals(host?"VM":"HOST"),"非法传输方向");
        projectId(fields.get("PROJECT"));require(fields.get("SESSION").matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"),"无效 session");
        require(fields.get("ENCODING").equals("GZIP+BASE64") && hashValue(fields.get("SHA256")) && hashValue(fields.get("CHUNK256")),"无效编码/checksum");
        require(headValue(fields.get(work?"BASE":"HEAD")),"无效 BASE/HEAD");
        require(fields.get("PART").matches("[1-9][0-9]{0,8}/[1-9][0-9]{0,8}"),"无效分块序号");
        String[] part=fields.get("PART").split("/");int index=Integer.parseInt(part[0]),count=Integer.parseInt(part[1]);
        require(index<=count,"分块数量超限");String data=text.substring(dataAt+7);
        require(!data.isEmpty() && data.length()<=TransferLimits.MAX_DATA_CHARS && data.matches("[A-Za-z0-9+/]+={0,2}"),"无效 Base64 分块");
        require(hash(data.getBytes(StandardCharsets.US_ASCII)).equals(fields.get("CHUNK256")),"CHECKSUM_ERROR: 分块校验失败，请重新复制");
        return new Part(fields,index,count,data,protocol);
    }
}
