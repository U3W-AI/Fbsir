package com.cube.openAI.model;


import com.cube.openAI.pojos.Message;
import com.cube.openAI.utils.AIResultUtil;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * @author muyou
 * dateStart 2024/8/4 9:34
 * dateNow   2025/9/8 17:20
 */
@Slf4j
public class BaiDuAI implements AIModel{
    @Override
    public String generate(List<Message> messages, Double temperature, Integer maxTokens) {
        try {
            return AIResultUtil.waitForResult(messages, "baidu", "baidu-agent");
        } catch (InterruptedException e) {
            log.error(e.getMessage());
            return e.getMessage();
        }
    }
}
