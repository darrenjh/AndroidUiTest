package com.yang.websocket.client;

import android.util.Log;

import com.yang.websocket.core.WsConnectionState;
import com.yang.websocket.core.WsConstants;
import com.yang.websocket.core.WsMessage;
import com.yang.websocket.core.WsMessageCodec;

import com.google.protobuf.InvalidProtocolBufferException;

import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

/**
 * OkHttp WebSocket 客户端封装，UI 层不直接持有 OkHttp 的连接细节。
 */
public class OkHttpWsClient {
    private static final String TAG = "OkHttpWsClient";

    private final Callback callback;
    private final OkHttpClient okHttpClient;

    private WebSocket webSocket;
    private String currentUrl = "";
    private volatile WsConnectionState state = WsConnectionState.IDLE;

    /**
     * 创建 OkHttp WebSocket 客户端。
     */
    public OkHttpWsClient(Callback callback) {
        this.callback = callback;
        this.okHttpClient = new OkHttpClient.Builder()
                .connectTimeout(8, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .pingInterval(15, TimeUnit.SECONDS)
                .build();
    }

    /**
     * 连接指定 WebSocket 地址。
     */
    public synchronized void connect(String url) {
        if (state == WsConnectionState.CONNECTING || state == WsConnectionState.CONNECTED) {
            logToAll("连接已存在，忽略重复连接");
            return;
        }
        currentUrl = url;
        updateState(WsConnectionState.CONNECTING);
        Request request = new Request.Builder().url(url).build();
        logToAll("准备建立 OkHttp WebSocket 连接：" + url);
        webSocket = okHttpClient.newWebSocket(request, new InnerListener(url));
    }

    /**
     * 主动关闭当前 WebSocket 连接。
     */
    public synchronized void disconnect() {
        if (webSocket == null) {
            logToAll("当前没有连接，忽略断开操作");
            updateState(WsConnectionState.CLOSED);
            return;
        }
        updateState(WsConnectionState.CLOSING);
        logToAll("准备关闭 WebSocket 连接");
        webSocket.close(1000, "client close");
    }

    /**
     * 发送自定义聊天消息。
     */
    public boolean sendChat(String text) {
        return sendMessage(WsMessage.create(WsConstants.TYPE_CHAT, WsConstants.ROLE_CLIENT, text));
    }

    /**
     * 释放 OkHttp 连接池和后台线程。
     */
    public synchronized void release() {
        disconnect();
        okHttpClient.dispatcher().executorService().shutdown();
        okHttpClient.connectionPool().evictAll();
        logToAll("OkHttp WebSocket 资源已释放");
    }

    private boolean sendMessage(WsMessage message) {
        WebSocket socket = webSocket;
        if (socket == null || state != WsConnectionState.CONNECTED) {
            logToAll("未连接服务端，无法发送消息");
            return false;
        }
        byte[] encodedBytes = WsMessageCodec.encode(message);
        boolean accepted = socket.send(ByteString.of(encodedBytes));
        if (accepted) {
            callback.onMessageSent(message, encodedBytes.length);
            Log.d(TAG, "send message, " + WsMessageCodec.summarize(message, encodedBytes.length));
        } else {
            logToAll("OkHttp 未接受发送任务");
        }
        return accepted;
    }

    private void sendSystemMessage(WsMessage message) {
        WebSocket socket = webSocket;
        if (socket == null) {
            return;
        }
        byte[] encodedBytes = WsMessageCodec.encode(message);
        boolean accepted = socket.send(ByteString.of(encodedBytes));
        if (accepted) {
            callback.onMessageSent(message, encodedBytes.length);
        }
        Log.d(TAG, "send system message, " + WsMessageCodec.summarize(message, encodedBytes.length));
    }

    private void handleIncomingMessage(byte[] data) {
        try {
            WsMessage message = WsMessageCodec.decode(data);
            callback.onMessageReceived(message, data.length);
            Log.d(TAG, "receive message, " + WsMessageCodec.summarize(message, data.length));
            if (WsConstants.TYPE_PING.equals(message.getType())) {
                sendSystemMessage(WsMessage.create(WsConstants.TYPE_PONG, WsConstants.ROLE_CLIENT, message.getMsgId()));
            } else if (!WsConstants.TYPE_ACK.equals(message.getType()) && !WsConstants.TYPE_PONG.equals(message.getType())) {
                sendSystemMessage(WsMessage.ack(WsConstants.ROLE_CLIENT, message.getMsgId()));
            }
        } catch (InvalidProtocolBufferException exception) {
            Log.e(TAG, "decode server protobuf failed, bytes=" + data.length, exception);
            callback.onFailure("解析服务端消息失败", exception);
        }
    }

    private void updateState(WsConnectionState newState) {
        state = newState;
        Log.d(TAG, "state changed: " + newState.name());
        callback.onStateChanged(newState);
    }

    private void logToAll(String message) {
        Log.d(TAG, message);
        callback.onLog(message);
    }

    /**
     * 客户端事件回调，隔离 OkHttp 的具体 API。
     */
    public interface Callback {
        void onStateChanged(WsConnectionState state);

        void onConnected(String url);

        void onMessageReceived(WsMessage message, int byteCount);

        void onMessageSent(WsMessage message, int byteCount);

        void onClosed(int code, String reason);

        void onFailure(String stage, Throwable throwable);

        void onLog(String message);
    }

    private class InnerListener extends WebSocketListener {
        private final String url;

        /**
         * 保存当前连接地址，便于日志回传。
         */
        InnerListener(String url) {
            this.url = url;
        }

        /**
         * WebSocket 握手成功后发送 hello。
         */
        @Override
        public void onOpen(WebSocket webSocket, Response response) {
            Log.d(TAG, "websocket opened, url=" + url);
            updateState(WsConnectionState.CONNECTED);
            callback.onConnected(url);
            sendSystemMessage(WsMessage.create(WsConstants.TYPE_HELLO, WsConstants.ROLE_CLIENT, "client ready"));
        }

        /**
         * 收到服务端文本消息时记录协议错误。
         */
        @Override
        public void onMessage(WebSocket webSocket, String text) {
            Log.d(TAG, "receive unsupported text message, length=" + text.length());
            callback.onFailure("收到非 Protobuf 文本消息", null);
        }

        /**
         * 收到服务端二进制消息后进入 Protobuf 解析流程。
         */
        @Override
        public void onMessage(WebSocket webSocket, ByteString bytes) {
            handleIncomingMessage(bytes.toByteArray());
        }

        /**
         * 收到关闭帧后继续完成关闭流程。
         */
        @Override
        public void onClosing(WebSocket webSocket, int code, String reason) {
            Log.d(TAG, "websocket closing, code=" + code + ", reason=" + reason);
            updateState(WsConnectionState.CLOSING);
            webSocket.close(1000, null);
        }

        /**
         * 连接完全关闭。
         */
        @Override
        public void onClosed(WebSocket webSocket, int code, String reason) {
            Log.d(TAG, "websocket closed, code=" + code + ", reason=" + reason);
            OkHttpWsClient.this.webSocket = null;
            updateState(WsConnectionState.CLOSED);
            callback.onClosed(code, reason);
        }

        /**
         * 捕获连接或收发过程错误。
         */
        @Override
        public void onFailure(WebSocket webSocket, Throwable throwable, Response response) {
            Log.e(TAG, "websocket failure, url=" + currentUrl, throwable);
            OkHttpWsClient.this.webSocket = null;
            updateState(WsConnectionState.FAILED);
            callback.onFailure("WebSocket 连接失败", throwable);
        }
    }
}
