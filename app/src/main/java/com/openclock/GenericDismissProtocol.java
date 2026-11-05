package com.openclock;

import android.util.JsonReader;
import android.util.JsonToken;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

/** Strict, duplicate-key rejecting protocol shared by phone and watch. */
final class GenericDismissProtocol {
    static final String REQUEST="/openclock/v1/dismiss-all";
    static final String REPLY="/openclock/v1/dismiss-all-reply";
    static final long LIFETIME=20_000;
    final String id; final long created, expires; final boolean ok;
    private GenericDismissProtocol(String id,long created,long expires,boolean ok) {
        this.id=id; this.created=created; this.expires=expires; this.ok=ok;
    }
    static GenericDismissProtocol parse(byte[] bytes,boolean reply) throws Exception {
        if(bytes==null||bytes.length==0||bytes.length>512) throw new IllegalArgumentException();
        String text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        JsonReader r=new JsonReader(new StringReader(text)); r.setLenient(false);
        Set<String> keys=new HashSet<>(); String id=null; long created=0,expires=0; boolean ok=false;
        r.beginObject();
        while(r.hasNext()) {
            String key=r.nextName(); if(!keys.add(key)) throw new IllegalArgumentException();
            switch(key) {
                case "requestId": if(r.peek()!=JsonToken.STRING) throw new IllegalArgumentException(); id=r.nextString(); break;
                case "createdAt": if(r.peek()!=JsonToken.NUMBER) throw new IllegalArgumentException(); created=Long.parseLong(r.nextString()); break;
                case "expiresAt": if(r.peek()!=JsonToken.NUMBER) throw new IllegalArgumentException(); expires=Long.parseLong(r.nextString()); break;
                case "ok": if(!reply||r.peek()!=JsonToken.BOOLEAN) throw new IllegalArgumentException(); ok=r.nextBoolean(); break;
                default: throw new IllegalArgumentException();
            }
        }
        r.endObject(); if(r.peek()!=JsonToken.END_DOCUMENT || keys.size()!=(reply?4:3) ||
            !keys.contains("requestId")||!keys.contains("createdAt")||!keys.contains("expiresAt")||
            (reply&&!keys.contains("ok"))||id==null||!id.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")||
            created<=0||created>Long.MAX_VALUE-LIFETIME||expires!=created+LIFETIME) throw new IllegalArgumentException();
        return new GenericDismissProtocol(id,created,expires,ok);
    }
    static byte[] encode(String id,long created,long expires,Boolean ok) throws Exception {
        org.json.JSONObject o=new org.json.JSONObject().put("requestId",id).put("createdAt",created).put("expiresAt",expires);
        if(ok!=null) o.put("ok",ok);
        return o.toString().getBytes(StandardCharsets.UTF_8);
    }
    private GenericDismissProtocol() { throw new AssertionError(); }
}
