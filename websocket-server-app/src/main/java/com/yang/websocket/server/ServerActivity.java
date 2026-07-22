package com.yang.websocket.server;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.TextView;

import com.yang.websocket.core.WsConstants;
import com.yang.websocket.core.WsMessage;
import com.yang.websocket.core.WsMessageCodec;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * WebSocket 服务端页面，负责启动监听、展示地址、发送广播和输出关键日志。
 */
public class ServerActivity extends Activity implements LanWebSocketServer.Callback, ServerServiceAdvertiser.Callback {
    private static final String TAG = "WsServerActivity";

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm:ss.SSS", Locale.CHINA);

    private LanWebSocketServer webSocketServer;
    private ServerServiceAdvertiser serviceAdvertiser;
    private TextView statusTextView;
    private TextView discoveryTextView;
    private TextView ipTextView;
    private TextView clientTextView;
    private TextView logTextView;
    private EditText portEditText;
    private EditText messageEditText;
    private ScrollView logScrollView;

    /**
     * 初始化服务端页面和 WebSocket 服务封装。
     */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_server);
        bindViews();
        webSocketServer = new LanWebSocketServer(this);
        serviceAdvertiser = new ServerServiceAdvertiser(this, this);
        portEditText.setText(String.valueOf(WsConstants.DEFAULT_PORT));
        refreshIpList();
        appendLog("服务端页面已初始化");
    }

    /**
     * 页面销毁时停止服务，避免端口被旧实例占用。
     */
    @Override
    protected void onDestroy() {
        if (serviceAdvertiser != null) {
            serviceAdvertiser.unregister();
        }
        if (webSocketServer != null && webSocketServer.isRunning()) {
            webSocketServer.stop();
        }
        super.onDestroy();
    }

    private void bindViews() {
        statusTextView = findViewById(R.id.tv_server_status);
        discoveryTextView = findViewById(R.id.tv_discovery_status);
        ipTextView = findViewById(R.id.tv_ip_list);
        clientTextView = findViewById(R.id.tv_client_count);
        logTextView = findViewById(R.id.tv_server_log);
        portEditText = findViewById(R.id.et_server_port);
        messageEditText = findViewById(R.id.et_server_message);
        logScrollView = findViewById(R.id.scroll_server_log);
        Button startButton = findViewById(R.id.btn_start_server);
        Button stopButton = findViewById(R.id.btn_stop_server);
        Button sendButton = findViewById(R.id.btn_server_send);
        Button refreshIpButton = findViewById(R.id.btn_refresh_ip);

        startButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                startServer();
            }
        });
        stopButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                stopServer();
            }
        });
        sendButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                sendMessage();
            }
        });
        refreshIpButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                refreshIpList();
            }
        });
    }

    private void startServer() {
        int port = parsePort();
        appendLog("点击启动服务，端口=" + port);
        webSocketServer.start(port);
    }

    private void stopServer() {
        appendLog("点击停止服务");
        webSocketServer.stop();
    }

    private void sendMessage() {
        String text = messageEditText.getText().toString().trim();
        if (TextUtils.isEmpty(text)) {
            appendLog("发送内容为空，已忽略");
            return;
        }
        boolean sent = webSocketServer.broadcast(text);
        if (sent) {
            messageEditText.setText("");
        }
    }

    private int parsePort() {
        String portText = portEditText.getText().toString().trim();
        try {
            int port = Integer.parseInt(portText);
            if (port > 0 && port <= 65535) {
                return port;
            }
        } catch (NumberFormatException exception) {
            Log.e(TAG, "parse port failed", exception);
        }
        appendLog("端口非法，使用默认端口 " + WsConstants.DEFAULT_PORT);
        return WsConstants.DEFAULT_PORT;
    }

    private void refreshIpList() {
        List<String> addresses = LocalIpProvider.getLocalIpv4Addresses();
        StringBuilder builder = new StringBuilder();
        if (addresses.isEmpty()) {
            builder.append("未找到局域网 IPv4 地址。请确认手机热点或 Wi-Fi 已开启。");
        } else {
            for (String address : addresses) {
                builder.append(address).append('\n');
            }
        }
        ipTextView.setText(builder.toString().trim());
        appendLog("刷新本机 IP，数量=" + addresses.size());
    }

    private void appendLog(final String message) {
        Log.d(TAG, message);
        runOnUiThreadSafe(new Runnable() {
            @Override
            public void run() {
                String line = timeFormat.format(new Date()) + "  " + message + "\n";
                logTextView.append(line);
                logScrollView.post(new Runnable() {
                    @Override
                    public void run() {
                        logScrollView.fullScroll(View.FOCUS_DOWN);
                    }
                });
            }
        });
    }

    private void runOnUiThreadSafe(Runnable runnable) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            runnable.run();
        } else {
            mainHandler.post(runnable);
        }
    }

    /**
     * WebSocket 服务启动成功回调。
     */
    @Override
    public void onServerStarted(final int port) {
        runOnUiThreadSafe(new Runnable() {
            @Override
            public void run() {
                statusTextView.setText("服务已开启，监听端口 " + port);
                clientTextView.setText("当前客户端：0");
                appendLog("WebSocket 服务已开启");
                serviceAdvertiser.register(port);
            }
        });
    }

    /**
     * WebSocket 服务停止回调。
     */
    @Override
    public void onServerStopped() {
        runOnUiThreadSafe(new Runnable() {
            @Override
            public void run() {
                statusTextView.setText("服务已停止");
                discoveryTextView.setText("DNS-SD：未发布");
                clientTextView.setText("当前客户端：0");
                serviceAdvertiser.unregister();
                appendLog("WebSocket 服务已停止");
            }
        });
    }

    /**
     * 客户端接入回调。
     */
    @Override
    public void onClientConnected(String clientId) {
        appendLog("客户端已连接：" + clientId);
        clientTextView.post(new Runnable() {
            @Override
            public void run() {
                clientTextView.setText("当前客户端：已连接");
            }
        });
    }

    /**
     * 客户端断开回调。
     */
    @Override
    public void onClientDisconnected(String clientId, int code, String reason, boolean remote) {
        appendLog("客户端已断开：" + clientId + " code=" + code + " reason=" + reason + " remote=" + remote);
        clientTextView.post(new Runnable() {
            @Override
            public void run() {
                clientTextView.setText("当前客户端：可能已变化，请看日志");
            }
        });
    }

    /**
     * 接收消息回调。
     */
    @Override
    public void onMessageReceived(String clientId, WsMessage message, int byteCount) {
        appendLog("收到 " + clientId + " 消息 " + WsMessageCodec.summarize(message, byteCount));
    }

    /**
     * 发送消息回调。
     */
    @Override
    public void onMessageSent(String clientId, WsMessage message, int byteCount) {
        appendLog("已发送到 " + clientId + " " + WsMessageCodec.summarize(message, byteCount));
    }

    /**
     * 错误回调。
     */
    @Override
    public void onError(String stage, Throwable throwable) {
        String errorMessage = throwable == null ? "" : throwable.getMessage();
        appendLog(stage + "：" + errorMessage);
    }

    /**
     * 通用日志回调。
     */
    @Override
    public void onLog(String message) {
        appendLog(message);
    }

    /**
     * DNS-SD 服务发布成功回调。
     */
    @Override
    public void onServiceRegistered(final String serviceName, final int port, final String path) {
        runOnUiThreadSafe(new Runnable() {
            @Override
            public void run() {
                discoveryTextView.setText("DNS-SD：已发布 " + serviceName + " " + path + " port=" + port);
                appendLog("DNS-SD 服务已发布：" + serviceName + " path=" + path + " port=" + port);
            }
        });
    }

    /**
     * DNS-SD 服务注销成功回调。
     */
    @Override
    public void onServiceUnregistered() {
        runOnUiThreadSafe(new Runnable() {
            @Override
            public void run() {
                discoveryTextView.setText("DNS-SD：未发布");
                appendLog("DNS-SD 服务已注销");
            }
        });
    }

    /**
     * DNS-SD 发布错误回调。
     */
    @Override
    public void onAdvertiseError(String stage, int errorCode) {
        appendLog(stage + " errorCode=" + errorCode);
    }

    /**
     * DNS-SD 发布日志回调。
     */
    @Override
    public void onAdvertiseLog(String message) {
        appendLog(message);
    }
}
