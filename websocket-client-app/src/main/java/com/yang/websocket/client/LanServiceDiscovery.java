package com.yang.websocket.client;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.net.wifi.WifiManager;
import android.util.Log;

import com.yang.websocket.core.WsConstants;

import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Map;

/**
 * 局域网 DNS-SD 发现器，负责扫描并解析 WebSocket 服务端地址。
 */
public class LanServiceDiscovery {
    private static final String TAG = "LanServiceDiscovery";

    private final NsdManager nsdManager;
    private final WifiManager wifiManager;
    private final Callback callback;
    private final ArrayDeque<NsdServiceInfo> resolveQueue = new ArrayDeque<>();

    private WifiManager.MulticastLock multicastLock;
    private NsdManager.DiscoveryListener discoveryListener;
    private boolean discovering;
    private boolean resolving;

    /**
     * 创建局域网服务发现器。
     */
    public LanServiceDiscovery(Context context, Callback callback) {
        Context appContext = context.getApplicationContext();
        this.nsdManager = (NsdManager) appContext.getSystemService(Context.NSD_SERVICE);
        this.wifiManager = (WifiManager) appContext.getSystemService(Context.WIFI_SERVICE);
        this.callback = callback;
    }

    /**
     * 开始扫描同一局域网内的 WebSocket 服务。
     */
    public synchronized void start() {
        if (discovering) {
            callback.onDiscoveryLog("DNS-SD 正在扫描，忽略重复启动");
            return;
        }
        if (nsdManager == null) {
            callback.onDiscoveryFailed("DNS-SD 服务不可用", -1);
            return;
        }
        acquireMulticastLock();
        discoveryListener = createDiscoveryListener();
        discovering = true;
        callback.onDiscoveryLog("准备扫描 DNS-SD 服务：" + WsConstants.DISCOVERY_SERVICE_TYPE);
        nsdManager.discoverServices(WsConstants.DISCOVERY_SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener);
    }

    /**
     * 停止扫描并释放 multicast lock。
     */
    public synchronized void stop() {
        if (!discovering || nsdManager == null || discoveryListener == null) {
            releaseMulticastLock();
            return;
        }
        try {
            nsdManager.stopServiceDiscovery(discoveryListener);
        } catch (IllegalArgumentException exception) {
            Log.e(TAG, "stop discovery ignored", exception);
            discovering = false;
            discoveryListener = null;
            resolveQueue.clear();
            resolving = false;
            releaseMulticastLock();
        }
    }

    private NsdManager.DiscoveryListener createDiscoveryListener() {
        return new NsdManager.DiscoveryListener() {
            /**
             * DNS-SD 扫描启动成功。
             */
            @Override
            public void onDiscoveryStarted(String serviceType) {
                Log.d(TAG, "discovery started: " + serviceType);
                callback.onDiscoveryStarted();
            }

            /**
             * 发现服务后进入串行解析队列。
             */
            @Override
            public void onServiceFound(NsdServiceInfo serviceInfo) {
                Log.d(TAG, "service found: " + serviceInfo);
                callback.onDiscoveryLog("发现服务，准备解析：" + serviceInfo.getServiceName());
                enqueueResolve(serviceInfo);
            }

            /**
             * 服务丢失时记录日志。
             */
            @Override
            public void onServiceLost(NsdServiceInfo serviceInfo) {
                Log.d(TAG, "service lost: " + serviceInfo);
                callback.onDiscoveryLog("服务离线：" + serviceInfo.getServiceName());
            }

            /**
             * 扫描停止成功。
             */
            @Override
            public void onDiscoveryStopped(String serviceType) {
                Log.d(TAG, "discovery stopped: " + serviceType);
                synchronized (LanServiceDiscovery.this) {
                    discovering = false;
                    discoveryListener = null;
                    resolveQueue.clear();
                    resolving = false;
                }
                releaseMulticastLock();
                callback.onDiscoveryStopped();
            }

            /**
             * 启动扫描失败。
             */
            @Override
            public void onStartDiscoveryFailed(String serviceType, int errorCode) {
                Log.e(TAG, "start discovery failed, errorCode=" + errorCode);
                stopAfterFailure("DNS-SD 启动扫描失败", errorCode);
            }

            /**
             * 停止扫描失败。
             */
            @Override
            public void onStopDiscoveryFailed(String serviceType, int errorCode) {
                Log.e(TAG, "stop discovery failed, errorCode=" + errorCode);
                stopAfterFailure("DNS-SD 停止扫描失败", errorCode);
            }
        };
    }

    private synchronized void enqueueResolve(NsdServiceInfo serviceInfo) {
        if (serviceInfo == null) {
            return;
        }
        resolveQueue.offer(serviceInfo);
        resolveNextLocked();
    }

    private synchronized void resolveNextLocked() {
        if (resolving || nsdManager == null) {
            return;
        }
        final NsdServiceInfo serviceInfo = resolveQueue.poll();
        if (serviceInfo == null) {
            return;
        }
        resolving = true;
        try {
            nsdManager.resolveService(serviceInfo, new NsdManager.ResolveListener() {
                /**
                 * 服务解析失败后继续解析下一个候选。
                 */
                @Override
                public void onResolveFailed(NsdServiceInfo serviceInfo, int errorCode) {
                    Log.e(TAG, "resolve service failed, errorCode=" + errorCode);
                    callback.onDiscoveryFailed("DNS-SD 解析失败", errorCode);
                    synchronized (LanServiceDiscovery.this) {
                        resolving = false;
                        resolveNextLocked();
                    }
                }

                /**
                 * 服务解析成功后输出 Endpoint。
                 */
                @Override
                public void onServiceResolved(NsdServiceInfo serviceInfo) {
                    Log.d(TAG, "service resolved: " + serviceInfo);
                    LanServiceEndpoint endpoint = buildEndpoint(serviceInfo);
                    if (endpoint != null) {
                        callback.onServiceResolved(endpoint);
                    }
                    synchronized (LanServiceDiscovery.this) {
                        resolving = false;
                        resolveNextLocked();
                    }
                }
            });
        } catch (IllegalArgumentException exception) {
            Log.e(TAG, "resolve service ignored", exception);
            resolving = false;
            resolveNextLocked();
        }
    }

    private LanServiceEndpoint buildEndpoint(NsdServiceInfo serviceInfo) {
        InetAddress host = serviceInfo.getHost();
        if (host == null || serviceInfo.getPort() <= 0) {
            callback.onDiscoveryLog("解析结果缺少 host 或 port：" + serviceInfo.getServiceName());
            return null;
        }
        Map<String, byte[]> attributes = serviceInfo.getAttributes();
        String path = readAttribute(attributes, WsConstants.DISCOVERY_ATTR_PATH, WsConstants.DEFAULT_PATH);
        String protocol = readAttribute(attributes, WsConstants.DISCOVERY_ATTR_PROTOCOL, "");
        String version = readAttribute(attributes, WsConstants.DISCOVERY_ATTR_VERSION, "");
        return new LanServiceEndpoint(
                serviceInfo.getServiceName(),
                host.getHostAddress(),
                serviceInfo.getPort(),
                path,
                protocol,
                version,
                "DNS-SD");
    }

    private String readAttribute(Map<String, byte[]> attributes, String key, String defaultValue) {
        if (attributes == null || !attributes.containsKey(key)) {
            return defaultValue;
        }
        byte[] value = attributes.get(key);
        if (value == null || value.length == 0) {
            return defaultValue;
        }
        return new String(value, StandardCharsets.UTF_8);
    }

    private void acquireMulticastLock() {
        if (wifiManager == null || multicastLock != null) {
            return;
        }
        multicastLock = wifiManager.createMulticastLock("testapp-ws-mdns");
        multicastLock.setReferenceCounted(false);
        multicastLock.acquire();
        callback.onDiscoveryLog("已获取 Wi-Fi multicast lock");
    }

    private void releaseMulticastLock() {
        if (multicastLock == null) {
            return;
        }
        try {
            if (multicastLock.isHeld()) {
                multicastLock.release();
            }
        } finally {
            multicastLock = null;
            callback.onDiscoveryLog("已释放 Wi-Fi multicast lock");
        }
    }

    private synchronized void stopAfterFailure(String stage, int errorCode) {
        discovering = false;
        discoveryListener = null;
        resolveQueue.clear();
        resolving = false;
        releaseMulticastLock();
        callback.onDiscoveryFailed(stage, errorCode);
    }

    /**
     * DNS-SD 发现事件回调。
     */
    public interface Callback {
        void onDiscoveryStarted();

        void onServiceResolved(LanServiceEndpoint endpoint);

        void onDiscoveryStopped();

        void onDiscoveryFailed(String stage, int errorCode);

        void onDiscoveryLog(String message);
    }
}
