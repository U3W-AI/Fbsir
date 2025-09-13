package com.cube.openAI.model;

import com.cube.openAI.pojos.Message;

import java.util.List;

// 所有AI模型的统一调用标准
public interface AIModel {
    String generate(List<Message> messages, Double temperature, Integer maxTokens);
}