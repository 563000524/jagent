package com.fastagent.web;

import com.fastagent.config.AppConfig;

import java.util.List;
import java.util.Map;

/** 模型配置列表视图：一次返回前端渲染整块面板所需的全部内容。 */
public class ModelsView {

    private List<ModelView> models;
    private String activeId;
    private String modelsPath;
    private Map<String, AppConfig.Preset> presets;

    public static ModelsView of(List<ModelView> models, String activeId, String modelsPath,
                                Map<String, AppConfig.Preset> presets) {
        ModelsView v = new ModelsView();
        v.models = models;
        v.activeId = activeId;
        v.modelsPath = modelsPath;
        v.presets = presets;
        return v;
    }

    public List<ModelView> getModels() {
        return models;
    }

    public void setModels(List<ModelView> models) {
        this.models = models;
    }

    public String getActiveId() {
        return activeId;
    }

    public void setActiveId(String activeId) {
        this.activeId = activeId;
    }

    public String getModelsPath() {
        return modelsPath;
    }

    public void setModelsPath(String modelsPath) {
        this.modelsPath = modelsPath;
    }

    public Map<String, AppConfig.Preset> getPresets() {
        return presets;
    }

    public void setPresets(Map<String, AppConfig.Preset> presets) {
        this.presets = presets;
    }
}
