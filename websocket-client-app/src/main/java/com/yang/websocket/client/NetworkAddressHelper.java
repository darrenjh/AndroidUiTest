package com.yang.websocket.client;

import android.content.Context;
import android.net.DhcpInfo;
import android.net.wifi.WifiManager;
import android.util.Log;

import java.util.Locale;

/**
 * 网络地址辅助类，用于在客户端自动获取热点网关 IP。
 */
public final class NetworkAddressHelper {
    private static final String TAG = "NetworkAddressHelper";

    private NetworkAddressHelper() {
    }

    /**
     * 读取当前 Wi-Fi DHCP 网关地址，热点场景下通常就是服务端手机地址。
     */
    public static String getGatewayIp(Context context) {
        try {
            WifiManager wifiManager = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wifiManager == null) {
                return "";
            }
            DhcpInfo dhcpInfo = wifiManager.getDhcpInfo();
            if (dhcpInfo == null || dhcpInfo.gateway == 0) {
                return "";
            }
            return intToIpv4(dhcpInfo.gateway);
        } catch (Exception exception) {
            Log.e(TAG, "get gateway ip failed", exception);
            return "";
        }
    }

    /**
     * 将 Android DHCP 返回的小端 int 地址转换为 IPv4 字符串。
     */
    private static String intToIpv4(int address) {
        return String.format(Locale.US, "%d.%d.%d.%d",
                address & 0xff,
                address >> 8 & 0xff,
                address >> 16 & 0xff,
                address >> 24 & 0xff);
    }
}
