package com.yang.websocket.client;

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

import com.yang.websocket.core.WsConnectionState;
import com.yang.websocket.core.WsConstants;
import com.yang.websocket.core.WsMessage;
import com.yang.websocket.core.WsMessageCodec;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * WebSocket 客户端页面，负责输入服务端地址、建立 OkHttp 连接、发送消息和输出日志。
 */
public class ClientActivity extends Activity implements OkHttpWsClient.Callback, LanServiceDiscovery.Callback {
    private static final String TAG = "WsClientActivity";

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm:ss.SSS", Locale.CHINA);

    private OkHttpWsClient webSocketClient;
    private LanServiceDiscovery serviceDiscovery;
    private TextView statusTextView;
    private TextView discoveryTextView;
    private TextView logTextView;
    private EditText ipEditText;
    private EditText portEditText;
    private EditText pathEditText;
    private EditText messageEditText;
    private ScrollView logScrollView;

    /**
     * 初始化客户端页面和 OkHttp WebSocket 封装。
     */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_client);
        bindViews();
        webSocketClient = new OkHttpWsClient(this);
        serviceDiscovery = new LanServiceDiscovery(this, this);
        portEditText.setText(String.valueOf(WsConstants.DEFAULT_PORT));
        pathEditText.setText(WsConstants.DEFAULT_PATH);
        fillGatewayIp(false);
        serviceDiscovery.start();
        appendLog("客户端页面已初始化");
    }

    /**
     * 页面销毁时释放 OkHttp 连接资源。
     */
    @Override
    protected void onDestroy() {
        if (serviceDiscovery != null) {
            serviceDiscovery.stop();
        }
        if (webSocketClient != null) {
            webSocketClient.release();
        }
        super.onDestroy();
    }

    private void bindViews() {
        statusTextView = findViewById(R.id.tv_client_status);
        discoveryTextView = findViewById(R.id.tv_client_discovery_status);
        logTextView = findViewById(R.id.tv_client_log);
        ipEditText = findViewById(R.id.et_server_ip);
        portEditText = findViewById(R.id.et_client_port);
        pathEditText = findViewById(R.id.et_client_path);
        messageEditText = findViewById(R.id.et_client_message);
        logScrollView = findViewById(R.id.scroll_client_log);
        Button gatewayButton = findViewById(R.id.btn_fill_gateway);
        Button startDiscoveryButton = findViewById(R.id.btn_start_discovery);
        Button stopDiscoveryButton = findViewById(R.id.btn_stop_discovery);
        Button connectButton = findViewById(R.id.btn_connect);
        Button disconnectButton = findViewById(R.id.btn_disconnect);
        Button sendButton = findViewById(R.id.btn_client_send);

        gatewayButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                fillGatewayIp(true);
            }
        });
        startDiscoveryButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                serviceDiscovery.start();
            }
        });
        stopDiscoveryButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                serviceDiscovery.stop();
            }
        });
        connectButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                connect();
            }
        });
        disconnectButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                disconnect();
            }
        });
        sendButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                sendMessage();
            }
        });
    }

    private void fillGatewayIp(boolean fromClick) {
        String gatewayIp = NetworkAddressHelper.getGatewayIp(this);
        if (TextUtils.isEmpty(gatewayIp)) {
            if (fromClick) {
                appendLog("未读取到 Wi-Fi 网关 IP，请手动输入服务端热点 IP");
            }
            return;
        }
        ipEditText.setText(gatewayIp);
        appendLog("已填入 Wi-Fi 网关 IP：" + gatewayIp);
    }

    private void connect() {
        String url = buildWebSocketUrl();
        if (TextUtils.isEmpty(url)) {
            return;
        }
        appendLog("点击连接：" + url);
        webSocketClient.connect(url);
    }

    private void disconnect() {
        appendLog("点击断开连接");
        webSocketClient.disconnect();
    }

    private void sendMessage() {
        String text = messageEditText.getText().toString().trim();
        if (TextUtils.isEmpty(text)) {
            appendLog("发送内容为空，已忽略");
            return;
        }
        boolean sent = webSocketClient.sendChat(text);
        if (sent) {
            messageEditText.setText("");
        }
    }

    private String buildWebSocketUrl() {
        String host = ipEditText.getText().toString().trim();
        if (TextUtils.isEmpty(host)) {
            appendLog("服务端 IP 为空");
            return "";
        }
        int port = parsePort();
        String path = pathEditText.getText().toString().trim();
        if (TextUtils.isEmpty(path)) {
            path = WsConstants.DEFAULT_PATH;
        }
        if (!path.startsWith("/")) {
            path = "/" + path;
        }
        return "ws://" + formatHostForUrl(host) + ":" + port + path;
    }

    private String formatHostForUrl(String host) {
        if (host.contains(":") && !host.startsWith("[")) {
            return "[" + host + "]";
        }
        return host;
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
     * 连接状态变化回调。
     */
    @Override
    public void onStateChanged(final WsConnectionState state) {
        runOnUiThreadSafe(new Runnable() {
            @Override
            public void run() {
                statusTextView.setText("连接状态：" + state.name());
                appendLog("连接状态变化：" + state.name());
            }
        });
    }

    /**
     * 连接成功回调。
     */
    @Override
    public void onConnected(String url) {
        appendLog("已连接服务端：" + url);
    }

    /**
     * 接收消息回调。
     */
    @Override
    public void onMessageReceived(WsMessage message, int byteCount) {
        appendLog("收到消息 " + WsMessageCodec.summarize(message, byteCount));
    }

    /**
     * 发送消息回调。
     */
    @Override
    public void onMessageSent(WsMessage message, int byteCount) {
        appendLog("已发送 " + WsMessageCodec.summarize(message, byteCount));
    }

    /**
     * 连接关闭回调。
     */
    @Override
    public void onClosed(int code, String reason) {
        appendLog("连接已关闭 code=" + code + " reason=" + reason);
    }

    /**
     * 错误回调。
     */
    @Override
    public void onFailure(String stage, Throwable throwable) {
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
     * DNS-SD 扫描启动回调。
     */
    @Override
    public void onDiscoveryStarted() {
        runOnUiThreadSafe(new Runnable() {
            @Override
            public void run() {
                discoveryTextView.setText("DNS-SD：扫描中");
                appendLog("DNS-SD 扫描已启动");
            }
        });
    }

    /**
     * DNS-SD 解析到服务端地址回调。
     */
    @Override
    public void onServiceResolved(final LanServiceEndpoint endpoint) {
        runOnUiThreadSafe(new Runnable() {
            @Override
            public void run() {
                if (!WsConstants.PROTOCOL_NAME.equals(endpoint.getProtocol())
                        || !WsConstants.PROTOCOL_VERSION.equals(endpoint.getVersion())) {
                    appendLog("忽略协议不匹配服务：" + endpoint.describe());
                    return;
                }
                ipEditText.setText(endpoint.getHost());
                portEditText.setText(String.valueOf(endpoint.getPort()));
                pathEditText.setText(endpoint.getPath());
                discoveryTextView.setText("DNS-SD：已发现 " + endpoint.getServiceName());
                appendLog("已发现并填入服务端地址：" + endpoint.describe());
            }
        });
    }

    /**
     * DNS-SD 扫描停止回调。
     */
    @Override
    public void onDiscoveryStopped() {
        runOnUiThreadSafe(new Runnable() {
            @Override
            public void run() {
                discoveryTextView.setText("DNS-SD：已停止");
                appendLog("DNS-SD 扫描已停止");
            }
        });
    }

    /**
     * DNS-SD 扫描失败回调。
     */
    @Override
    public void onDiscoveryFailed(String stage, int errorCode) {
        appendLog(stage + " errorCode=" + errorCode + "，保留热点网关 IP 兜底");
        fillGatewayIp(false);
    }

    /**
     * DNS-SD 通用日志回调。
     */
    @Override
    public void onDiscoveryLog(String message) {
        appendLog(message);
    }
}
