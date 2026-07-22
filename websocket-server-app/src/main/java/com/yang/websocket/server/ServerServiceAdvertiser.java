package com.yang.websocket.server;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.util.Log;

import com.yang.websocket.core.WsConstants;

/**
 * DNS-SD 服务发布器，让同一局域网客户端可以自动发现 WebSocket 服务端。
 */
public class ServerServiceAdvertiser {
    private static final String TAG = "ServerServiceAdvertiser";

    private final NsdManager nsdManager;
    private final Callback callback;

    private NsdManager.RegistrationListener registrationListener;

    /**
     * 创建服务发布器。
     */
    public ServerServiceAdvertiser(Context context, Callback callback) {
        this.nsdManager = (NsdManager) context.getApplicationContext().getSystemService(Context.NSD_SERVICE);
        this.callback = callback;
    }

    /**
     * 发布当前 WebSocket 服务地址和协议元数据。
     */
    public synchronized void register(final int port) {
        if (nsdManager == null) {
            callback.onAdvertiseError("DNS-SD 服务不可用", -1);
            return;
        }
        unregister();

        NsdServiceInfo serviceInfo = new NsdServiceInfo();
        serviceInfo.setServiceName(WsConstants.DISCOVERY_SERVICE_NAME);
        serviceInfo.setServiceType(WsConstants.DISCOVERY_SERVICE_TYPE);
        serviceInfo.setPort(port);
        serviceInfo.setAttribute(WsConstants.DISCOVERY_ATTR_PATH, WsConstants.DEFAULT_PATH);
        serviceInfo.setAttribute(WsConstants.DISCOVERY_ATTR_PROTOCOL, WsConstants.PROTOCOL_NAME);
        serviceInfo.setAttribute(WsConstants.DISCOVERY_ATTR_VERSION, WsConstants.PROTOCOL_VERSION);

        final NsdManager.RegistrationListener listener = new NsdManager.RegistrationListener() {
            /**
             * 服务发布失败。
             */
            @Override
            public void onRegistrationFailed(NsdServiceInfo serviceInfo, int errorCode) {
                Log.e(TAG, "register service failed, errorCode=" + errorCode);
                synchronized (ServerServiceAdvertiser.this) {
                    if (registrationListener == this) {
                        registrationListener = null;
                    }
                }
                callback.onAdvertiseError("DNS-SD 发布失败", errorCode);
            }

            /**
             * 服务发布成功。
             */
            @Override
            public void onServiceRegistered(NsdServiceInfo serviceInfo) {
                Log.d(TAG, "service registered: " + serviceInfo.getServiceName());
                callback.onServiceRegistered(serviceInfo.getServiceName(), port, WsConstants.DEFAULT_PATH);
            }

            /**
             * 服务注销失败。
             */
            @Override
            public void onUnregistrationFailed(NsdServiceInfo serviceInfo, int errorCode) {
                Log.e(TAG, "unregister service failed, errorCode=" + errorCode);
                callback.onAdvertiseError("DNS-SD 注销失败", errorCode);
            }

            /**
             * 服务注销成功。
             */
            @Override
            public void onServiceUnregistered(NsdServiceInfo serviceInfo) {
                Log.d(TAG, "service unregistered");
                synchronized (ServerServiceAdvertiser.this) {
                    if (registrationListener == this) {
                        registrationListener = null;
                    }
                }
                callback.onServiceUnregistered();
            }
        };

        registrationListener = listener;
        callback.onAdvertiseLog("准备发布 DNS-SD 服务：" + WsConstants.DISCOVERY_SERVICE_TYPE + " port=" + port);
        nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, listener);
    }

    /**
     * 注销已经发布的 DNS-SD 服务。
     */
    public synchronized void unregister() {
        if (nsdManager == null || registrationListener == null) {
            return;
        }
        try {
            callback.onAdvertiseLog("准备注销 DNS-SD 服务");
            nsdManager.unregisterService(registrationListener);
        } catch (IllegalArgumentException exception) {
            Log.e(TAG, "unregister service ignored", exception);
            registrationListener = null;
        }
    }

    /**
     * DNS-SD 发布事件回调。
     */
    public interface Callback {
        void onServiceRegistered(String serviceName, int port, String path);

        void onServiceUnregistered();

        void onAdvertiseError(String stage, int errorCode);

        void onAdvertiseLog(String message);
    }
}
