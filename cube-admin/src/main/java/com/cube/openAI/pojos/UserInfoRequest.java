package com.cube.openAI.pojos;

import lombok.Data;

@Data
public class UserInfoRequest {

    private String userPrompt;

    private String userId;

    private String corpId;

    private String unionId;

    private String taskId;
    private String roles;

    private String toneChatId;

    private String ybDsChatId;

    private String dbChatId;

    private String baiduChatId;

    private String isNewChat;

    private String draftContent;

    private String aiName;

    private String status;

    private String type;

    private String imageUrl;
    private String imageDescription;

    private String shareUrl;

    private String shareImgUrl;
}
