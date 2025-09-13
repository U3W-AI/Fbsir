package com.cube.openAI.config;

import com.cube.openAI.model.*;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

// 管理所有模型，通过model名称路由
@Component
public class ModelRegistry {
    private final Map<String, AIModel> modelMap = new HashMap<>();

    // 初始化时注册所有模型
    public ModelRegistry() {
        modelMap.put("model-baiDuAI", new BaiDuAI());
        modelMap.put("model-deepseek", new DeepSeek());
        modelMap.put("model-douBao", new DouBao());
        modelMap.put("model-yuanBaoT1", new YuanBaoT1());
        modelMap.put("model-yuanBaoDS", new YuanBaoDS());
    }

    // 根据model名称获取模型实例
    public AIModel getModel(String modelName) {
        return modelMap.get(modelName);
    }

    // 获取所有可用模型名称
    public List<String> getAllModelNames() {
        return modelMap.keySet().stream().toList();
    }
}