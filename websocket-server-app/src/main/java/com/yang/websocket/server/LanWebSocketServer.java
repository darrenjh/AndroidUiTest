package com.yang.websocket.server;

import android.util.Log;

import com.yang.websocket.core.WsConstants;
import com.yang.websocket.core.WsMessage;
import com.yang.websocket.core.WsMessageCodec;

import com.google.protobuf.InvalidProtocolBufferException;

import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 局域网 WebSocket 服务端封装，Activity 只通过回调观察状态，不直接依赖 Java-WebSocket。
 */
public class LanWebSocketServer {
    private static final String TAG = "LanWsServer";

    private final Callback callback;
    private final ConcurrentHashMap<WebSocket, String> clients = new ConcurrentHashMap<>();
    private final AtomicInteger clientIndex = new AtomicInteger(1);

    private InnerServer innerServer;
    private volatile boolean running;

    /**
     * 创建服务端封装对象。
     */
    public LanWebSocketServer(Callback callback) {
        this.callback = callback;
    }

    /**
     * 启动 WebSocket 监听端口。
     */
    public synchronized void start(int port) {
        if (running) {
            logToAll("服务已在运行，忽略重复启动");
            return;
        }
        try {
            logToAll("准备启动 WebSocket 服务，端口=" + port);
            innerServer = new InnerServer(new InetSocketAddress("0.0.0.0", port));
            innerServer.setConnectionLostTimeout(20);
            innerServer.start();
            running = true;
        } catch (Exception exception) {
            running = false;
            innerServer = null;
            Log.e(TAG, "start server failed", exception);
            callback.onError("启动服务失败", exception);
        }
    }

    /**
     * 停止 WebSocket 服务并清理连接。
     */
    public synchronized void stop() {
        if (!running || innerServer == null) {
            logToAll("服务未运行，忽略停止操作");
            return;
        }
        final InnerServer serverToStop = innerServer;
        innerServer = null;
        running = false;
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    logToAll("准备停止 WebSocket 服务");
                    serverToStop.stop(1000);
                } catch (Exception exception) {
                    Log.e(TAG, "stop server failed", exception);
                    callback.onError("停止服务失败", exception);
                } finally {
                    clients.clear();
                    callback.onServerStopped();
                    logToAll("WebSocket 服务已停止");
                }
            }
        }, "lan-ws-server-stop").start();
    }

    /**
     * 向全部在线客户端广播自定义 Protobuf 消息。
     */
    public boolean broadcast(String text) {
        if (!running) {
            logToAll("服务未启动，无法发送消息");
            return false;
        }
        if (clients.isEmpty()) {
            logToAll("当前没有客户端连接，无法发送消息");
            return false;
        }
        WsMessage message = WsMessage.create(WsConstants.TYPE_CHAT, WsConstants.ROLE_SERVER, text);
        byte[] encodedBytes = WsMessageCodec.encode(message);

        int sentCount = 0;
        for (Map.Entry<WebSocket, String> entry : clients.entrySet()) {
            WebSocket socket = entry.getKey();
            if (socket != null && socket.isOpen()) {
                socket.send(encodedBytes);
                sentCount++;
                callback.onMessageSent(entry.getValue(), message, encodedBytes.length);
                Log.d(TAG, "broadcast to " + entry.getValue() + ", " + WsMessageCodec.summarize(message, encodedBytes.length));
            }
        }
        logToAll("广播完成，发送客户端数=" + sentCount);
        return sentCount > 0;
    }

    /**
     * 判断服务是否正在运行。
     */
    public boolean isRunning() {
        return running;
    }

    private void sendSystemMessage(WebSocket socket, WsMessage message) {
        try {
            byte[] encodedBytes = WsMessageCodec.encode(message);
            socket.send(encodedBytes);
            String clientId = clients.get(socket);
            callback.onMessageSent(clientId == null ? "unknown" : clientId, message, encodedBytes.length);
            Log.d(TAG, "send system message, " + WsMessageCodec.summarize(message, encodedBytes.length));
        } catch (Exception exception) {
            Log.e(TAG, "send system message failed", exception);
            callback.onError("发送系统消息失败", exception);
        }
    }

    private void handleIncomingMessage(WebSocket socket, byte[] data) {
        try {
            WsMessage message = WsMessageCodec.decode(data);
            String clientId = clients.get(socket);
            if (clientId == null) {
                clientId = "unknown";
            }
            callback.onMessageReceived(clientId, message, data.length);
            Log.d(TAG, "receive message from " + clientId + ", " + WsMessageCodec.summarize(message, data.length));

            if (WsConstants.TYPE_PING.equals(message.getType())) {
                sendSystemMessage(socket, WsMessage.create(WsConstants.TYPE_PONG, WsConstants.ROLE_SERVER, message.getMsgId()));
            } else if (!WsConstants.TYPE_ACK.equals(message.getType()) && !WsConstants.TYPE_PONG.equals(message.getType())) {
                sendSystemMessage(socket, WsMessage.ack(WsConstants.ROLE_SERVER, message.getMsgId()));
            }
        } catch (InvalidProtocolBufferException exception) {
            Log.e(TAG, "decode client protobuf failed, bytes=" + data.length, exception);
            sendSystemMessage(socket, WsMessage.create(WsConstants.TYPE_ERROR, WsConstants.ROLE_SERVER, "消息不是合法 Protobuf"));
            callback.onError("解析客户端消息失败", exception);
        }
    }

    private void handleTextMessage(WebSocket socket, String rawText) {
        Log.d(TAG, "receive unsupported text message, length=" + rawText.length());
        sendSystemMessage(socket, WsMessage.create(WsConstants.TYPE_ERROR, WsConstants.ROLE_SERVER, "当前协议仅支持 Protobuf 二进制帧"));
        callback.onError("收到非 Protobuf 文本消息", null);
    }

    private byte[] readBytes(ByteBuffer byteBuffer) {
        byte[] data = new byte[byteBuffer.remaining()];
        byteBuffer.get(data);
        return data;
    }

    private String buildClientId(WebSocket socket) {
        SocketAddress remoteSocketAddress = socket == null ? null : socket.getRemoteSocketAddress();
        String address = remoteSocketAddress == null ? "unknown" : remoteSocketAddress.toString();
        return "client-" + clientIndex.getAndIncrement() + " " + address;
    }

    private void logToAll(String message) {
        Log.d(TAG, message);
        callback.onLog(message);
    }

    /**
     * 服务端事件回调，隔离 Java-WebSocket 的具体 API。
     */
    public interface Callback {
        void onServerStarted(int port);

        void onServerStopped();

        void onClientConnected(String clientId);

        void onClientDisconnected(String clientId, int code, String reason, boolean remote);

        void onMessageReceived(String clientId, WsMessage message, int byteCount);

        void onMessageSent(String clientId, WsMessage message, int byteCount);

        void onError(String stage, Throwable throwable);

        void onLog(String message);
    }

    private class InnerServer extends WebSocketServer {
        /**
         * 创建 Java-WebSocket 服务端实例。
         */
        InnerServer(InetSocketAddress address) {
            super(address);
        }

        /**
         * 客户端握手成功后登记连接并发送 hello。
         */
        @Override
        public void onOpen(WebSocket conn, ClientHandshake handshake) {
            String clientId = buildClientId(conn);
            clients.put(conn, clientId);
            Log.d(TAG, "client connected: " + clientId);
            callback.onClientConnected(clientId);
            sendSystemMessage(conn, WsMessage.create(WsConstants.TYPE_HELLO, WsConstants.ROLE_SERVER, "server ready"));
        }

        /**
         * 连接关闭时移除客户端。
         */
        @Override
        public void onClose(WebSocket conn, int code, String reason, boolean remote) {
            String clientId = clients.remove(conn);
            if (clientId == null) {
                clientId = "unknown";
            }
            Log.d(TAG, "client disconnected: " + clientId + ", code=" + code + ", reason=" + reason + ", remote=" + remote);
            callback.onClientDisconnected(clientId, code, reason, remote);
        }

        /**
         * 收到客户端文本消息时返回协议错误。
         */
        @Override
        public void onMessage(WebSocket conn, String message) {
            handleTextMessage(conn, message);
        }

        /**
         * 收到客户端二进制消息后进入 Protobuf 解析流程。
         */
        @Override
        public void onMessage(WebSocket conn, ByteBuffer message) {
            handleIncomingMessage(conn, readBytes(message));
        }

        /**
         * 捕获底层连接错误。
         */
        @Override
        public void onError(WebSocket conn, Exception ex) {
            Log.e(TAG, "websocket server error", ex);
            callback.onError("WebSocket 服务错误", ex);
        }

        /**
         * 服务线程启动成功后通知 UI。
         */
        @Override
        public void onStart() {
            Log.d(TAG, "websocket server started");
            callback.onServerStarted(getPort());
        }
    }
}
