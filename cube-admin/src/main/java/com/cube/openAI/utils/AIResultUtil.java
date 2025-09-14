package com.cube.openAI.utils;

import cn.hutool.core.lang.UUID;
import com.cube.common.core.redis.RedisCache;
import com.cube.openAI.constants.OpenAIExceptionConstants;
import com.cube.openAI.pojos.ChatCompletionStreamResponse;
import com.cube.openAI.pojos.Message;
import com.cube.openAI.pojos.UserInfo;
import com.cube.openAI.pojos.UserInfoRequest;
import com.cube.wechat.selfapp.app.config.MyWebSocketHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * @author muyou
 * dateStart 2024/8/4 9:34
 * dateNow   2025/9/13 9:37
 */
public class AIResultUtil {
    public static String waitForResult(List<Message> messages, String aiName, String roles, boolean isStream) throws InterruptedException {
        try {
            MyWebSocketHandler myWebSocketHandler = SpringContextUtils.getBean(MyWebSocketHandler.class);
            RedisCache redisCache = SpringContextUtils.getBean(RedisCache.class);
            UserInfo userInfo = ThreadUserInfo.getUserInfo();
            if(userInfo == null) {
                throw new RuntimeException(OpenAIExceptionConstants.USER_NOT_FOUND);
            }
            UserInfoRequest userInfoRequest = new UserInfoRequest();
            userInfoRequest.setUserId(userInfo.getUserId());
            userInfoRequest.setRoles(roles);
            userInfoRequest.setCorpId(userInfo.getCropId());
            userInfoRequest.setUserPrompt(messages.toString());
            userInfoRequest.setType("openAI");
            userInfoRequest.setAiName(aiName);
            String taskId = UUID.randomUUID().toString();
            userInfoRequest.setTaskId(taskId);
            myWebSocketHandler.sendMsgToAI(userInfo.getCropId(), userInfoRequest);
            if(!isStream) {
                for (int i = 0; i < 20; i++) {
                    Object cacheObject = redisCache.getCacheObject("openAI:" + userInfo.getUserId() + ":" + userInfoRequest.getAiName() + ":" + userInfoRequest.getTaskId());
                    if (cacheObject != null) {
                        return cacheObject.toString();
                    }
                    Thread.sleep(10000);
                }
            } else {
                return taskId;
            }
            throw new RuntimeException(OpenAIExceptionConstants.MODEL_EXECUTE_ERROR);
        } catch (Exception e) {
            throw e;
        }
    }
    public static Flux<String> waitForResultByStream(List<Message> messages, String aiName, String roles, String modelName) throws InterruptedException {
        try {
            aiName = aiName + "-stream";
            String taskId = AIResultUtil.waitForResult(messages, aiName, roles, true);
            ObjectMapper objectMapper = SpringContextUtils.getBean(ObjectMapper.class);
            RedisCache redisCache = SpringContextUtils.getBean(RedisCache.class);
            String id = "chatcmpl-" + java.util.UUID.randomUUID().toString().substring(0, 10);
            long timestamp = System.currentTimeMillis() / 1000;

            String userId = ThreadUserInfo.getUserInfo().getUserId();
            String key = "openAI:" + userId + ":" + aiName + ":" + taskId;

            AtomicReference<String> currentContent = new AtomicReference<>("");
            AtomicReference<String> lastContent = new AtomicReference<>("");
            return Flux
                    .interval(Duration.ofSeconds(2))
                    .delayElements(Duration.ofMillis(300))
                    .flatMap(i -> {
                        String newContent = "";
                        String cacheStr= redisCache.getCacheObject(key);
                        if(cacheStr != null) {
                            lastContent.set(cacheStr);
                        }
                        if(lastContent.get().length() > currentContent.get().length()) {
                            newContent = lastContent.get().substring(currentContent.get().length());
                            currentContent.set(lastContent.get());
                        }
                        String[] chunks = newContent.split("(?<=[。？！，；：])");
                        return Flux
                                .fromArray(chunks)
                                .delayElements(Duration.ofMillis(300))
                                .map(chunk -> {
                                    boolean isLastChunk = chunk.equals(chunks[chunks.length - 1]);
                                    ChatCompletionStreamResponse response = ChatCompletionStreamResponse.builder()
                                            .id(id)
                                            .object("chat.completion.chunk")
                                            .created(timestamp)
                                            .model(modelName)
                                            .choices(List.of(
                                                    ChatCompletionStreamResponse.Choice.builder()
                                                            .index(0)
                                                            .delta(ChatCompletionStreamResponse.Delta.builder()
                                                                    .content(chunk)
                                                                    .build())
                                                            .finishReason(isLastChunk ? "stop" : null)
                                                            .build()
                                            ))
                                            .build();

                                    try {
                                        return objectMapper.writeValueAsString(response);
                                    } catch (Exception e) {
                                        return "{\"error\": {\"message\": \"序列化失败: " + e.getMessage() + "\"}}";
                                    }
                                })
                                .takeWhile(content -> !content.contains("END"));
                    });
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }
}
