package com.cube.openAI.controller;

import com.cube.openAI.pojos.ChatCompletionRequest;
import com.cube.openAI.pojos.ChatCompletionResponse;
import com.cube.openAI.pojos.ChatCompletionStreamResponse;
import com.cube.openAI.pojos.Message;
import com.cube.openAI.config.ModelRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import javax.validation.Valid;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * @author muyou
 * dateStart 2024/8/4 9:34
 * dateNow   2025/9/9 17:16
 */
@RestController
@RequestMapping("/v1") // 基础路径与OpenAI一致
public class ChatController {

    @Autowired
    private ModelRegistry modelRegistry;
    @Autowired
    private ObjectMapper objectMapper;

    // 核心接口：兼容OpenAI的聊天接口
    @PostMapping(value = "/chat/completions",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE + ";charset=UTF-8")
    public Object createCompletion(
            @Valid @RequestBody ChatCompletionRequest request) {
        // 1. 验证是否需要流式输出
        if (request.isStream()) {
            String id = "chatcmpl-" + UUID.randomUUID().toString().substring(0, 10);
            long timestamp = System.currentTimeMillis() / 1000;

            // 模拟AI生成的内容
            String fullResponse = "这是一个流式输出的示例。"
                    + "AI模型暂时不支持流式输出！";
            String[] chunks = fullResponse.split("。");

            return Flux.range(0, chunks.length)
                    .delayElements(Duration.ofMillis(300))
                    .map(i -> {
                        try {
                            // 构建响应对象
                            ChatCompletionStreamResponse response = ChatCompletionStreamResponse.builder()
                                    .id(id)
                                    .object("chat.completion.chunk")
                                    .created(timestamp)
                                    .model(request.getModel())
                                    .choices(List.of(
                                            ChatCompletionStreamResponse.Choice.builder()
                                                    .index(0)
                                                    .delta(ChatCompletionStreamResponse.Delta.builder()
                                                            .content(chunks[i] + "。")
                                                            .build())
                                                    .finishReason(i == chunks.length - 1 ? "stop" : null)
                                                    .build()
                                    ))
                                    .build();

                            // 关键修复：直接返回JSON字符串，不带data:前缀和\n\n后缀
                            return objectMapper.writeValueAsString(response);
                        } catch (Exception e) {
                            return "{\"error\": {\"message\": \"序列化失败: " + e.getMessage() + "\"}}";
                        }
                    });
        }

        // 1. 验证模型是否存在
        var model = modelRegistry.getModel(request.getModel());
        if (model == null) {
            throw new IllegalArgumentException("模型不存在：" + request.getModel());
        }

        // 2. 调用模型生成结果
        String responseText = model.generate(
                request.getMessages(),
                request.getTemperature(),
                request.getMaxTokens()
        );
        // 3. 构建符合OpenAI规范的响应
        long timestamp = System.currentTimeMillis() / 1000; // 秒级时间戳
        ChatCompletionResponse response = ChatCompletionResponse.builder()
                .id("chatcmpl-" + UUID.randomUUID().toString().substring(0, 10))
                .created(timestamp)
                .model(request.getModel())
                .choices(List.of(
                        ChatCompletionResponse.Choice.builder()
                                .index(0)
                                .message(new Message("assistant", responseText))
                                .finishReason("stop")
                                .build()
                ))
                .usage(ChatCompletionResponse.Usage.builder()
                        .promptTokens(calculatePromptTokens(request.getMessages()))
                        .completionTokens(responseText.length())
                        .totalTokens(calculatePromptTokens(request.getMessages()) + responseText.length())
                        .build())
                .build();

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE + ";charset=UTF-8")
                .body(response);
    }

    // 简单计算输入令牌数（实际需根据模型tokenizer实现）
    private int calculatePromptTokens(List<Message> messages) {
        return messages.stream().mapToInt(m -> m.getContent().length()).sum();
    }
}