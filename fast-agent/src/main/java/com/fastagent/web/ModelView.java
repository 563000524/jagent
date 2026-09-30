package com.fastagent.web;

import com.fastagent.model.ModelEntry;

/**
 * 模型视图。{@code apiKey} 是双向字段：
 * 读出时是脱敏串，写入时若非空且不含 {@code ****} 则视为「用户填了新 Key」。
 */
public class ModelView {

    private String id;
    private String name;
    private String vendor;
    private String url;
    private String apiKey;
    private boolean supportsToolCall = true;
    private boolean supportsImages = false;
    private boolean supportsReasoning = false;
    private boolean active;

    // 只读派生字段
    private boolean hasApiKey;
    private String apiKeyMasked;

    public static ModelView of(ModelEntry e) {
        ModelView v = new ModelView();
        v.id = e.getId();
        v.name = e.getName();
        v.vendor = e.getVendor();
        v.url = e.getUrl();
        v.apiKey = e.maskedApiKey();
        v.supportsToolCall = e.isSupportsToolCall();
        v.supportsImages = e.isSupportsImages();
        v.supportsReasoning = e.isSupportsReasoning();
        v.active = e.isActive();
        v.hasApiKey = e.hasApiKey();
        v.apiKeyMasked = e.maskedApiKey();
        return v;
    }

    public ModelEntry toEntry() {
        return ModelEntry.of(id, name, vendor, url, apiKey,
                supportsToolCall, supportsImages, supportsReasoning).withActive(active);
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

    public boolean isHasApiKey() {
        return hasApiKey;
    }

    public void setHasApiKey(boolean hasApiKey) {
        this.hasApiKey = hasApiKey;
    }

    public String getApiKeyMasked() {
        return apiKeyMasked;
    }

    public void setApiKeyMasked(String apiKeyMasked) {
        this.apiKeyMasked = apiKeyMasked;
    }
}
