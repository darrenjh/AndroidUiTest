package com.yang.websocket.core;

/**
 * WebSocket 演示链路的公共常量，避免服务端和客户端各自硬编码。
 */
public final class WsConstants {
    public static final int DEFAULT_PORT = 9000;
    public static final String DEFAULT_PATH = "/ws";

    public static final String PROTOCOL_NAME = "protobuf";
    public static final String PROTOCOL_VERSION = "1";

    public static final String DISCOVERY_SERVICE_NAME = "TestAppWsServer";
    public static final String DISCOVERY_SERVICE_TYPE = "_testapp-ws._tcp.";
    public static final String DISCOVERY_ATTR_PATH = "path";
    public static final String DISCOVERY_ATTR_PROTOCOL = "proto";
    public static final String DISCOVERY_ATTR_VERSION = "version";

    public static final String ROLE_SERVER = "server";
    public static final String ROLE_CLIENT = "client";

    public static final String TYPE_HELLO = "hello";
    public static final String TYPE_CHAT = "chat";
    public static final String TYPE_PING = "ping";
    public static final String TYPE_PONG = "pong";
    public static final String TYPE_ACK = "ack";
    public static final String TYPE_ERROR = "error";

    private WsConstants() {
    }
}
