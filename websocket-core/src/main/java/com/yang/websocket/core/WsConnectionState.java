package com.yang.websocket.core;

/**
 * WebSocket 连接状态，供 UI 层和连接实现统一表达当前阶段。
 */
public enum WsConnectionState {
    IDLE,
    CONNECTING,
    CONNECTED,
    CLOSING,
    CLOSED,
    FAILED
}
