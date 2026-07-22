package com.yang.websocket.core;

import java.util.UUID;

/**
 * WebSocket 传输消息模型，服务端和客户端只围绕该模型做协议交互。
 */
public final class WsMessage {
    private final String type;
    private final String msgId;
    private final long timestamp;
    private final String from;
    private final String payload;

    /**
     * 构造一条完整 WebSocket 消息。
     */
    public WsMessage(String type, String msgId, long timestamp, String from, String payload) {
        this.type = type;
        this.msgId = msgId;
        this.timestamp = timestamp;
        this.from = from;
        this.payload = payload;
    }

    /**
     * 创建带唯一编号和当前时间戳的消息。
     */
    public static WsMessage create(String type, String from, String payload) {
        return new WsMessage(type, UUID.randomUUID().toString(), System.currentTimeMillis(), from, payload);
    }

    /**
     * 创建确认消息，payload 保存被确认的消息编号。
     */
    public static WsMessage ack(String from, String sourceMsgId) {
        return create(WsConstants.TYPE_ACK, from, sourceMsgId);
    }

    /**
     * 获取消息类型。
     */
    public String getType() {
        return type;
    }

    /**
     * 获取消息唯一编号。
     */
    public String getMsgId() {
        return msgId;
    }

    /**
     * 获取消息产生时间。
     */
    public long getTimestamp() {
        return timestamp;
    }

    /**
     * 获取消息发送方角色。
     */
    public String getFrom() {
        return from;
    }

    /**
     * 获取业务文本内容。
     */
    public String getPayload() {
        return payload;
    }
}
