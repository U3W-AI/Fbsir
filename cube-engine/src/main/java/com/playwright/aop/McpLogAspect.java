package com.playwright.aop;

import com.playwright.config.WechatMpConfig;
import com.playwright.constants.WxExceptionConstants;
import com.playwright.entity.LogInfo;
import com.playwright.entity.UserInfoRequest;
import com.playwright.entity.mcp.McpResult;
import com.playwright.utils.LogMsgUtil;
import com.playwright.utils.RestUtils;
import com.playwright.utils.UserInfoUtil;
import com.playwright.utils.UserLogUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.chanjar.weixin.mp.api.WxMpService;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.*;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.util.Arrays;

/**
 * dateStart 2024/8/4 9:34
 * dateNow   2025/8/23 10:23
 */
@Component
@Aspect
@Slf4j
@RequiredArgsConstructor
public class McpLogAspect {
    @Value("${cube.url}")
    private String url;
    private final LogMsgUtil logMsgUtil;
    private final UserInfoUtil userInfoUtil;
    private final WechatMpConfig wechatMpConfig;
    @Pointcut("execution(* com.playwright.mcp.*.*(..))")
    public void logPointCut() {
    }

    @Around("logPointCut()")
    public Object logAround(ProceedingJoinPoint joinPoint) throws Throwable {
        long start = 0;
        LogInfo logInfo = new LogInfo();
        logInfo.setUserId("");
        String description = "无";
        UserInfoRequest userInfoRequest = null;
        try {
            start = System.currentTimeMillis();
            MethodSignature signature = (MethodSignature) joinPoint.getSignature();
//        方法名
            Method method = signature.getMethod();
            String methodName = method.getName();
            log.info("进入方法：{}", methodName);
            logInfo.setMethodName(methodName);
//        获取Operation注解上的summary
            description = "";
            if (method.isAnnotationPresent(Tool.class)) {
                Tool tool = method.getAnnotation(Tool.class);
                description = tool.name();
                logInfo.setDescription(description);
            }
//        参数
            Object[] args = joinPoint.getArgs();
            if (args.length > 0) {
                Object arg = args[0];
                if (arg instanceof UserInfoRequest) {
                    userInfoRequest = (UserInfoRequest) arg;
                    String unionId = userInfoRequest.getUnionId();
                    String userId = userInfoUtil.getUserIdByUnionId(unionId);
                    logInfo.setUserId(userId);
                }
            }
            logInfo.setMethodParams(Arrays.toString(args));
        } catch (Exception e) {
            UserLogUtil.sendExceptionLog("无", "aop异常", "logAround", e, url + "/saveLogInfo");
        }
        try {
//            获取微信用户认证
            if(userInfoRequest != null && userInfoRequest.getUnionId() != null) {
                WxMpService wxMpService = wechatMpConfig.getWxMpService(userInfoRequest.getUnionId());
                if(wxMpService == null) {
                    String s = wechatMpConfig.setWxMpService(userInfoRequest.getUnionId());
                    if(s.contains("false")) {
                        throw new RuntimeException(WxExceptionConstants.WX_AUTH_EXCEPTION);
                    }
                }
            }
//        执行方法，无异常情况
            Object result = joinPoint.proceed();
            long end = System.currentTimeMillis();
            McpResult mcpResult = null;
            if(result instanceof McpResult) {
                mcpResult = (McpResult) result;
            }
            if(mcpResult == null) {
                return McpResult.fail("aop返回异常", "");
            }
            logInfo.setExecutionTimeMillis(end - start);
            logInfo.setExecutionResult(mcpResult.getResult() + mcpResult.getShareUrl());
            logInfo.setIsSuccess(mcpResult.getCode() == 200 ? 1 : 0);
            RestUtils.post(url + "/saveLogInfo", logInfo);
            return result;
        } catch (Throwable e) {
            return McpResult.fail(e.getMessage(), "");
        }
    }
}
