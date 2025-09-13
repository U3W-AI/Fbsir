package com.cube.openAI.utils;

import com.cube.openAI.pojos.UserInfo;

/**
 * @author muyou
 * dateStart 2024/8/4 9:34
 * dateNow   2025/9/9 13:35
 */
public class ThreadUserInfo {
    public static ThreadLocal<UserInfo> userInfo = new ThreadLocal<>();

    public static UserInfo getUserInfo() {
        return userInfo.get();
    }
    public static void setUserInfo(UserInfo userInfo) {
        ThreadUserInfo.userInfo.set(userInfo);
    }
    public static void removeUserInfo() {
        userInfo.remove();
    }
}
