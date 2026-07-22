package com.yang.websocket.core;

import com.google.protobuf.InvalidProtocolBufferException;
import com.yang.websocket.core.proto.WsProto;

/**
 * WebSocket 消息编解码器，统一 Protobuf 字段，避免两端协议漂移。
 */
public final class WsMessageCodec {
    private WsMessageCodec() {
    }

    /**
     * 将消息对象编码为 Protobuf 二进制数据。
     */
    public static byte[] encode(WsMessage message) {
        WsProto.Envelope.Builder builder = WsProto.Envelope.newBuilder()
                .setMsgId(safe(message.getMsgId()))
                .setTimestamp(message.getTimestamp())
                .setFrom(toProtoRole(message.getFrom()))
                .setType(toProtoType(message.getType()));

        String payload = safe(message.getPayload());
        if (WsConstants.TYPE_HELLO.equals(message.getType())) {
            builder.setHello(WsProto.HelloBody.newBuilder()
                    .setText(payload)
                    .setProtocolVersion(WsConstants.PROTOCOL_VERSION));
        } else if (WsConstants.TYPE_CHAT.equals(message.getType())) {
            builder.setChat(WsProto.ChatBody.newBuilder().setText(payload));
        } else if (WsConstants.TYPE_PING.equals(message.getType())) {
            builder.setPing(WsProto.PingBody.newBuilder().setSourceMsgId(payload));
        } else if (WsConstants.TYPE_PONG.equals(message.getType())) {
            builder.setPong(WsProto.PongBody.newBuilder().setSourceMsgId(payload));
        } else if (WsConstants.TYPE_ACK.equals(message.getType())) {
            builder.setAck(WsProto.AckBody.newBuilder().setSourceMsgId(payload));
        } else if (WsConstants.TYPE_ERROR.equals(message.getType())) {
            builder.setError(WsProto.ErrorBody.newBuilder().setText(payload));
        }

        return builder.build().toByteArray();
    }

    /**
     * 将 Protobuf 二进制数据解码为消息对象。
     */
    public static WsMessage decode(byte[] data) throws InvalidProtocolBufferException {
        WsProto.Envelope envelope = WsProto.Envelope.parseFrom(data);
        return new WsMessage(
                fromProtoType(envelope.getType()),
                envelope.getMsgId(),
                envelope.getTimestamp(),
                fromProtoRole(envelope.getFrom()),
                readPayload(envelope));
    }

    /**
     * 生成适合日志展示的消息摘要，避免直接打印二进制乱码。
     */
    public static String summarize(WsMessage message, int byteCount) {
        return "type=" + message.getType()
                + " from=" + message.getFrom()
                + " msgId=" + message.getMsgId()
                + " payload=" + message.getPayload()
                + " bytes=" + byteCount;
    }

    private static String readPayload(WsProto.Envelope envelope) {
        switch (envelope.getBodyCase()) {
            case HELLO:
                return envelope.getHello().getText();
            case CHAT:
                return envelope.getChat().getText();
            case PING:
                return envelope.getPing().getSourceMsgId();
            case PONG:
                return envelope.getPong().getSourceMsgId();
            case ACK:
                return envelope.getAck().getSourceMsgId();
            case ERROR:
                return envelope.getError().getText();
            case BODY_NOT_SET:
            default:
                return "";
        }
    }

    private static WsProto.Envelope.Type toProtoType(String type) {
        if (WsConstants.TYPE_HELLO.equals(type)) {
            return WsProto.Envelope.Type.HELLO;
        } else if (WsConstants.TYPE_CHAT.equals(type)) {
            return WsProto.Envelope.Type.CHAT;
        } else if (WsConstants.TYPE_PING.equals(type)) {
            return WsProto.Envelope.Type.PING;
        } else if (WsConstants.TYPE_PONG.equals(type)) {
            return WsProto.Envelope.Type.PONG;
        } else if (WsConstants.TYPE_ACK.equals(type)) {
            return WsProto.Envelope.Type.ACK;
        } else if (WsConstants.TYPE_ERROR.equals(type)) {
            return WsProto.Envelope.Type.ERROR;
        }
        return WsProto.Envelope.Type.TYPE_UNKNOWN;
    }

    private static String fromProtoType(WsProto.Envelope.Type type) {
        if (type == WsProto.Envelope.Type.HELLO) {
            return WsConstants.TYPE_HELLO;
        } else if (type == WsProto.Envelope.Type.CHAT) {
            return WsConstants.TYPE_CHAT;
        } else if (type == WsProto.Envelope.Type.PING) {
            return WsConstants.TYPE_PING;
        } else if (type == WsProto.Envelope.Type.PONG) {
            return WsConstants.TYPE_PONG;
        } else if (type == WsProto.Envelope.Type.ACK) {
            return WsConstants.TYPE_ACK;
        } else if (type == WsProto.Envelope.Type.ERROR) {
            return WsConstants.TYPE_ERROR;
        }
        return "unknown";
    }

    private static WsProto.Envelope.Role toProtoRole(String role) {
        if (WsConstants.ROLE_SERVER.equals(role)) {
            return WsProto.Envelope.Role.SERVER;
        } else if (WsConstants.ROLE_CLIENT.equals(role)) {
            return WsProto.Envelope.Role.CLIENT;
        }
        return WsProto.Envelope.Role.ROLE_UNKNOWN;
    }

    private static String fromProtoRole(WsProto.Envelope.Role role) {
        if (role == WsProto.Envelope.Role.SERVER) {
            return WsConstants.ROLE_SERVER;
        } else if (role == WsProto.Envelope.Role.CLIENT) {
            return WsConstants.ROLE_CLIENT;
        }
        return "unknown";
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
