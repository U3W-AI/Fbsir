package com.playwright.utils;

import com.alibaba.fastjson.JSONObject;
import com.playwright.constants.WxExceptionConstants;
import com.playwright.entity.mcp.WcOfficeAccount;
import com.playwright.entity.mcp.WechatInfo;
import lombok.RequiredArgsConstructor;
import org.apache.hc.core5.http.ParseException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URISyntaxException;
import java.util.HashMap;
import java.util.Map;

/**
 * @author muyou
 * dateStart 2024/8/4 9:34
 * dateNow   2025/8/29 16:39
 */
@Component
@RequiredArgsConstructor
public class WeChatUtils {
    @Value("${cube.url}")
    private String url;
    private final UserInfoUtil userInfoUtil;

    /**
     * 获取token
     */
    public String getOfficeAccessToken(String appId, String secret) {
        String accessTokenUrl = "https://api.weixin.qq.com/cgi-bin/token?grant_type=client_credential&appid=" + appId + "&secret=" + secret;
        JSONObject jsonObject = RestUtils.get(accessTokenUrl);
        int errCode = jsonObject.getIntValue("errcode");
        if (errCode == 0) {
            return jsonObject.getString("access_token");
        }
        return null;
    }

    /**
     * 获取用户公众号信息包括token
     * @param unionId 用户唯一标识
     */
    public WechatInfo getWechatInfoByUnionId(String unionId) throws URISyntaxException, IOException, ParseException {
        try {
            String userId = userInfoUtil.getUserIdByUnionId(unionId);
            WcOfficeAccount wo = getOfficeAccountByUserId(userId);
            String accessToken = getOfficeAccessToken(wo.getAppId(), wo.getAppSecret());
            if(accessToken == null) {
                throw new RuntimeException(WxExceptionConstants.WX_AUTH_EXCEPTION);
            }
            WechatInfo wechatInfo = new WechatInfo();
            wechatInfo.setToken(accessToken);
            wechatInfo.setAppId(wo.getAppId());
            wechatInfo.setSecret(wo.getAppSecret());
            return wechatInfo;
        } catch (Exception e) {
            throw e;
        }
    }

    /**
     * 获取用户公众号基本信息
     * @param userId 用户id
     */
    public WcOfficeAccount getOfficeAccountByUserId(String userId) throws URISyntaxException, IOException, ParseException {
        try {
            Map<String, Object> map = new HashMap<>();
            map.put("userId", userId);
            String result = HttpUtil.doGet(url.substring(0, url.lastIndexOf("/")) + "/mini/getOfficeAccount", map);
            JSONObject jsonObject = JSONObject.parseObject(result);
            JSONObject data = JSONObject.parseObject(jsonObject.get("data").toString());
            return data.toJavaObject(WcOfficeAccount.class);

        } catch (Exception e) {
            throw new RuntimeException(WxExceptionConstants.WX_AUTH_EXCEPTION);
        }
     }
}
