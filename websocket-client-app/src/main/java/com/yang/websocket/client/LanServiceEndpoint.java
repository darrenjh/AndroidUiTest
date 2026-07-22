package com.yang.websocket.client;

/**
 * DNS-SD 解析出的局域网 WebSocket 服务端地址。
 */
public class LanServiceEndpoint {
    private final String serviceName;
    private final String host;
    private final int port;
    private final String path;
    private final String protocol;
    private final String version;
    private final String source;

    /**
     * 创建局域网服务端地址对象。
     */
    public LanServiceEndpoint(String serviceName, String host, int port, String path, String protocol, String version, String source) {
        this.serviceName = serviceName;
        this.host = host;
        this.port = port;
        this.path = path;
        this.protocol = protocol;
        this.version = version;
        this.source = source;
    }

    /**
     * 获取服务名称。
     */
    public String getServiceName() {
        return serviceName;
    }

    /**
     * 获取服务端主机地址。
     */
    public String getHost() {
        return host;
    }

    /**
     * 获取服务端端口。
     */
    public int getPort() {
        return port;
    }

    /**
     * 获取 WebSocket 路径。
     */
    public String getPath() {
        return path;
    }

    /**
     * 获取服务端声明的协议名称。
     */
    public String getProtocol() {
        return protocol;
    }

    /**
     * 获取服务端声明的协议版本。
     */
    public String getVersion() {
        return version;
    }

    /**
     * 获取地址来源。
     */
    public String getSource() {
        return source;
    }

    /**
     * 拼接 WebSocket 连接地址。
     */
    public String buildUrl() {
        String urlPath = path == null || path.length() == 0 ? "/" : path;
        if (!urlPath.startsWith("/")) {
            urlPath = "/" + urlPath;
        }
        return "ws://" + formatHost(host) + ":" + port + urlPath;
    }

    /**
     * 生成适合页面日志展示的摘要。
     */
    public String describe() {
        return serviceName + " " + buildUrl() + " proto=" + protocol + " version=" + version + " source=" + source;
    }

    private String formatHost(String host) {
        if (host != null && host.contains(":") && !host.startsWith("[")) {
            return "[" + host + "]";
        }
        return host;
    }
}
