package com.yang.websocket.server;

import android.util.Log;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;

/**
 * 查询本机可被局域网访问的 IPv4 地址，用于服务端页面展示连接地址。
 */
public final class LocalIpProvider {
    private static final String TAG = "LocalIpProvider";

    private LocalIpProvider() {
    }

    /**
     * 枚举非回环网卡上的内网 IPv4 地址。
     */
    public static List<String> getLocalIpv4Addresses() {
        List<String> addresses = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> networkInterfaces = NetworkInterface.getNetworkInterfaces();
            if (networkInterfaces == null) {
                return addresses;
            }
            for (NetworkInterface networkInterface : Collections.list(networkInterfaces)) {
                if (networkInterface == null || networkInterface.isLoopback() || !networkInterface.isUp()) {
                    continue;
                }
                Enumeration<InetAddress> inetAddresses = networkInterface.getInetAddresses();
                for (InetAddress inetAddress : Collections.list(inetAddresses)) {
                    if (inetAddress instanceof Inet4Address && inetAddress.isSiteLocalAddress()) {
                        addresses.add(networkInterface.getName() + "  " + inetAddress.getHostAddress());
                    }
                }
            }
        } catch (SocketException exception) {
            Log.e(TAG, "get local ip failed", exception);
        }
        return addresses;
    }
}
