package com.weaver.seconddev.wecom.util;

import org.apache.http.HttpEntity;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.conn.ssl.NoopHostnameVerifier;
import org.apache.http.conn.ssl.SSLConnectionSocketFactory;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.ssl.SSLContextBuilder;
import org.apache.http.util.EntityUtils;

import java.nio.charset.StandardCharsets;
import lombok.extern.slf4j.Slf4j;

/**
 * 轻量 HTTP 客户端封装（基于 Apache HttpClient 4.5，E10 环境自带）。
 *
 * <p>SSL 策略：默认按系统信任库正常校验企业微信官方证书；
 * 若部署环境经内网代理 / 网关转发且代理证书为自签，可设置系统属性
 * <code>-Dwecom.http.ssl.noop=true</code> 信任全部证书（仅建议在可控内网使用）。</p>
 *
 * @author DuJiang
 */
@Slf4j
public class HttpClientUtil {

    /** 是否信任全部证书（应对内网代理自签证书），默认关闭 */
    private static final boolean SSL_NOOP = Boolean.parseBoolean(
            System.getProperty("wecom.http.ssl.noop", "false"));

    /** 连接 / 读取超时（毫秒） */
    private static final int CONNECT_TIMEOUT = 10000;
    private static final int READ_TIMEOUT = 15000;

    private static volatile CloseableHttpClient httpClient;

    /**
     * 获取单例 HttpClient（懒加载）。
     *
     * @return HttpClient 实例
     * @author DuJiang
     */
    private static CloseableHttpClient getClient() {
        if (httpClient == null) {
            synchronized (HttpClientUtil.class) {
                if (httpClient == null) {
                    try {
                        SSLConnectionSocketFactory sslsf = buildSslSocketFactory();
                        RequestConfig config = RequestConfig.custom()
                                .setConnectTimeout(CONNECT_TIMEOUT)
                                .setSocketTimeout(READ_TIMEOUT)
                                .build();
                        httpClient = HttpClients.custom()
                                .setSSLSocketFactory(sslsf)
                                .setDefaultRequestConfig(config)
                                .setMaxConnTotal(20)
                                .setMaxConnPerRoute(10)
                                .build();
                    } catch (Exception e) {
                        throw new IllegalStateException("初始化 HttpClient 失败", e);
                    }
                }
            }
        }
        return httpClient;
    }

    /**
     * 构建 SSL 连接工厂：默认正常校验，开启 noop 时信任全部证书。
     *
     * @return SSL 连接工厂
     * @throws Exception SSL 上下文构建失败
     * @author DuJiang
     */
    private static SSLConnectionSocketFactory buildSslSocketFactory() throws Exception {
        if (SSL_NOOP) {
            return new SSLConnectionSocketFactory(
                    SSLContextBuilder.create()
                            .loadTrustMaterial(null, (chain, authType) -> true)
                            .build(),
                    NoopHostnameVerifier.INSTANCE);
        }
        return SSLConnectionSocketFactory.getSocketFactory();
    }

    /**
     * 发送 GET 请求，返回响应体字符串。
     *
     * @param url 完整 URL
     * @return 响应体
     * @author DuJiang
     */
    public static String get(String url) {
        log.info("[HttpClientUtil] GET 请求开始, url(脱敏)={}", maskUrl(url));
        long start = System.currentTimeMillis();
        HttpGet request = new HttpGet(url);
        request.setHeader("Accept", "application/json");
        try (CloseableHttpResponse response = getClient().execute(request)) {
            HttpEntity entity = response.getEntity();
            String body = entity == null ? "" : EntityUtils.toString(entity, StandardCharsets.UTF_8);
            log.info("[HttpClientUtil] GET 请求完成, url(脱敏)={}, 耗时={}ms, 状态码={}, 响应体长度={}",
                    maskUrl(url), System.currentTimeMillis() - start,
                    response.getStatusLine() == null ? -1 : response.getStatusLine().getStatusCode(),
                    body.length());
            return body;
        } catch (Exception e) {
            log.error("[HttpClientUtil] GET 请求失败, url(脱敏)={}, 耗时={}ms: {}",
                    maskUrl(url), System.currentTimeMillis() - start, e.getMessage(), e);
            throw new IllegalStateException("GET 请求失败: " + url + ", 原因: " + e.getMessage(), e);
        }
    }

    /**
     * 发送 POST JSON 请求，返回响应体字符串。
     *
     * @param url     完整 URL
     * @param jsonBody JSON 请求体
     * @return 响应体
     * @author DuJiang
     */
    public static String postJson(String url, String jsonBody) {
        log.info("[HttpClientUtil] POST 请求开始, url(脱敏)={}, 请求体: {}", maskUrl(url), jsonBody);
        long start = System.currentTimeMillis();
        HttpPost request = new HttpPost(url);
        request.setHeader("Content-Type", "application/json;charset=utf-8");
        request.setEntity(new StringEntity(jsonBody, ContentType.APPLICATION_JSON));
        try (CloseableHttpResponse response = getClient().execute(request)) {
            HttpEntity entity = response.getEntity();
            String body = entity == null ? "" : EntityUtils.toString(entity, StandardCharsets.UTF_8);
            log.info("[HttpClientUtil] POST 请求完成, url(脱敏)={}, 耗时={}ms, 状态码={}, 响应体: {}",
                    maskUrl(url), System.currentTimeMillis() - start,
                    response.getStatusLine() == null ? -1 : response.getStatusLine().getStatusCode(),
                    body);
            return body;
        } catch (Exception e) {
            log.error("[HttpClientUtil] POST 请求失败, url(脱敏)={}, 耗时={}ms: {}",
                    maskUrl(url), System.currentTimeMillis() - start, e.getMessage(), e);
            throw new IllegalStateException("POST 请求失败: " + url + ", 原因: " + e.getMessage(), e);
        }
    }

    /**
     * URL 脱敏：保留 scheme + host + 路径，隐藏查询参数（含 access_token / corpsecret 等敏感信息）。
     *
     * @param url 原始 URL
     * @return 脱敏后的 URL
     * @author DuJiang
     */
    private static String maskUrl(String url) {
        if (url == null || url.isEmpty()) {
            return "";
        }
        int idx = url.indexOf('?');
        return idx >= 0 ? url.substring(0, idx) + "?***" : url;
    }
}
