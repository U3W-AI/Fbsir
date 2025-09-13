package com.cube.openAI.utils;

import cn.hutool.core.lang.UUID;
import com.cube.common.core.redis.RedisCache;
import com.cube.openAI.constants.OpenAIExceptionConstants;
import com.cube.openAI.pojos.Message;
import com.cube.openAI.pojos.UserInfo;
import com.cube.openAI.pojos.UserInfoRequest;
import com.cube.wechat.selfapp.app.config.MyWebSocketHandler;

import java.util.List;

/**
 * @author muyou
 * dateStart 2024/8/4 9:34
 * dateNow   2025/9/13 9:37
 */
public class AIResultUtil {
    public static String waitForResult(List<Message> messages, String aiName, String roles) throws InterruptedException {
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
            userInfoRequest.setTaskId(UUID.randomUUID().toString());
            myWebSocketHandler.sendMsgToAI(userInfo.getCropId(), userInfoRequest);
            for(int i = 0; i < 20; i++) {
                Object cacheObject = redisCache.getCacheObject("openAI:" + userInfo.getUserId() + ":" + userInfoRequest.getAiName() + ":" + userInfoRequest.getTaskId());
                if(cacheObject != null) {
                    return cacheObject.toString();
                }
                Thread.sleep(10000);
            }
            throw new RuntimeException(OpenAIExceptionConstants.MODEL_EXECUTE_ERROR);
        } catch (Exception e) {
            throw e;
        }
    }
}
