package com.fastagent.model;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 一条模型配置。字段与 WorkBuddy 的 {@code models.json} 保持同构，
 * 因此该文件可以直接互换使用。
 *
 * <p>几个约定：
 * <ul>
 *   <li>{@code id} 既是唯一键，也是<b>发给模型服务的模型名</b>（WorkBuddy 即如此，
 *       例如 {@code deepseek-ai/DeepSeek-V4-Flash}）；</li>
 *   <li>{@code url} 是<b>完整接口地址</b>（如 {@code https://api.siliconflow.cn/v1/chat/completions}），
 *       解析时会自动拆成 baseUrl + endpointPath；</li>
 *   <li>{@code active} 是本应用的扩展字段，标记当前使用的模型。
 *       未选中时不出现在 JSON 里，从而不影响与其他实现互通。</li>
 * </ul>
 */
public class ModelEntry {

    private String id;
    private String name;
    private String vendor;
    private String url;
    private String apiKey;
    private boolean supportsToolCall = true;
    private boolean supportsImages = false;
    private boolean supportsReasoning = false;

    /** 是否为当前使用的模型（本应用扩展，false 时不落盘） */
    @JsonInclude(JsonInclude.Include.NON_DEFAULT)
    private boolean active = false;

    public static ModelEntry of(String id, String name, String vendor, String url, String apiKey,
                                boolean supportsToolCall, boolean supportsImages, boolean supportsReasoning) {
        ModelEntry m = new ModelEntry();
        m.id = id;
        m.name = name;
        m.vendor = vendor;
        m.url = url;
        m.apiKey = apiKey;
        m.supportsToolCall = supportsToolCall;
        m.supportsImages = supportsImages;
        m.supportsReasoning = supportsReasoning;
        return m;
    }

    public ModelEntry copy() {
        return of(id, name, vendor, url, apiKey, supportsToolCall, supportsImages, supportsReasoning)
                .withActive(active);
    }

    public ModelEntry withActive(boolean v) {
        this.active = v;
        return this;
    }

    /** 归一化：去空白、补默认值，避免手改坏文件后启动异常 */
    public void normalize() {
        if (id != null) {
            id = id.trim();
        }
        if (name == null || name.isBlank()) {
            name = id;
        } else {
            name = name.trim();
        }
        if (vendor == null || vendor.isBlank()) {
            vendor = "Custom";
        } else {
            vendor = vendor.trim();
        }
        if (url != null) {
            url = url.trim();
            while (url.endsWith("/")) {
                url = url.substring(0, url.length() - 1);
            }
        }
        if (apiKey != null) {
            apiKey = apiKey.trim();
        }
    }

    /** 可用的最小条件：有 id、有 url、有 Key */
    public boolean usable() {
        return id != null && !id.isBlank()
                && url != null && !url.isBlank()
                && hasApiKey();
    }

    public boolean hasApiKey() {
        return apiKey != null && !apiKey.isBlank();
    }

    /** 脱敏展示：sk-abc****xyz */
    public String maskedApiKey() {
        if (!hasApiKey()) {
            return "";
        }
        String k = apiKey;
        if (k.length() <= 10) {
            return "****";
        }
        return k.substring(0, 6) + "****" + k.substring(k.length() - 4);
    }

    /** 从完整 URL 取 baseUrl（去掉末尾的 /chat/completions） */
    public String baseUrl() {
        String u = url == null ? "" : url;
        int i = u.indexOf("/chat/completions");
        return i >= 0 ? u.substring(0, i) : u;
    }

    /** 接口路径：URL 里带了就用 URL 的，否则用 OpenAI 默认值 */
    public String endpointPath() {
        String u = url == null ? "" : url;
        int i = u.indexOf("/chat/completions");
        return i >= 0 ? u.substring(i) : "/chat/completions";
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getVendor() {
        return vendor;
    }

    public void setVendor(String vendor) {
        this.vendor = vendor;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public boolean isSupportsToolCall() {
        return supportsToolCall;
    }

    public void setSupportsToolCall(boolean supportsToolCall) {
        this.supportsToolCall = supportsToolCall;
    }

    public boolean isSupportsImages() {
        return supportsImages;
    }

    public void setSupportsImages(boolean supportsImages) {
        this.supportsImages = supportsImages;
    }

    public boolean isSupportsReasoning() {
        return supportsReasoning;
    }

    public void setSupportsReasoning(boolean supportsReasoning) {
        this.supportsReasoning = supportsReasoning;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }
}
