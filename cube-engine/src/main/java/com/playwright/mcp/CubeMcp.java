package com.playwright.mcp;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.io.IoUtil;
import com.alibaba.fastjson.JSONObject;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.playwright.config.WechatMpConfig;
import com.playwright.constants.WxExceptionConstants;
import com.playwright.controller.AIGCController;
import com.playwright.controller.BrowserController;
import com.playwright.entity.UserInfoRequest;
import com.playwright.entity.mcp.ImgInfo;
import com.playwright.entity.mcp.Item;
import com.playwright.entity.mcp.McpResult;
import com.playwright.entity.mcp.WcOfficeAccount;
import com.playwright.utils.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.chanjar.weixin.common.error.WxErrorException;
import me.chanjar.weixin.mp.api.WxMpService;
import me.chanjar.weixin.mp.api.impl.WxMpServiceImpl;
import me.chanjar.weixin.mp.bean.draft.WxMpAddDraft;
import me.chanjar.weixin.mp.bean.draft.WxMpDraftArticles;
import me.chanjar.weixin.mp.bean.draft.WxMpDraftInfo;
import me.chanjar.weixin.mp.bean.material.*;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.ParseException;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.aspectj.apache.bcel.classfile.Field;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.*;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * dateStart 2024/8/4 9:34
 * dateNow   2025/8/20 10:14
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class CubeMcp {
    @Value("${cube.url}")
    private String url;
    @Value("${cube.datadir}")
    private String tmpUrl;
    @Autowired
    private AIGCController aigcController;

    private final UserInfoUtil userInfoUtil;
    private final BrowserController browserController;
    private final WeChatUtils weChatUtils;
    private final WechatMpConfig wechatMpConfig;
    private WxMpService wxMpService;

    @Tool(name = "豆包AI", description = "通过用户信息调用ai,需要用户unionId,ai配置信息,提示词")
    public McpResult dbMcp(@ToolParam(description = "用户调用信息,包括用户unionId,用户提示词,用户选择的ai配置信息")
                           UserInfoRequest userInfoRequest) {
        return startAi(userInfoRequest, "豆包", "dbMcp", "zj-db");
    }

    @Tool(name = "腾讯元宝T1", description = "通过用户信息调用ai,需要用户unionId,ai配置信息,提示词")
    public McpResult ybT1Mcp(@ToolParam(description = "用户调用信息,包括用户unionId,用户提示词,用户选择的ai配置信息")
                             UserInfoRequest userInfoRequest) {
        return startAi(userInfoRequest, "腾讯元宝T1", "ybT1Mcp", "yb-hunyuan-pt");
    }

    @Tool(name = "腾讯元宝DS", description = "通过用户信息调用ai,需要用户unionId,ai配置信息,提示词")
    public McpResult ybDsMcp(@ToolParam(description = "用户调用信息,包括用户unionId,用户提示词,用户选择的ai配置信息")
                             UserInfoRequest userInfoRequest) {
        return startAi(userInfoRequest, "腾讯元宝DS", "ybDsMcp", "yb-deepseek-pt");
    }

    private McpResult startAi(UserInfoRequest userInfoRequest, String aiName, String methodName, String aiConfig) {
        try {
            userInfoRequest.setTaskId(UUID.randomUUID().toString());
            String roles = userInfoRequest.getRoles();
            String unionId = userInfoRequest.getUnionId();
            String userId = null;
            if (unionId != null && !unionId.isEmpty()) {
                userId = userInfoUtil.getUserIdByUnionId(unionId);
            }
            if (userId == null) {
                return McpResult.fail("您无权限访问,请联系管理员", "");
            }
            userInfoRequest.setUserId(userId);
            String result = null;
            if (aiName.contains("腾讯元宝T1") || aiName.contains("腾讯元宝DS")) {
                result = browserController.checkYBLogin(userId);
            }
            if (aiName.contains("豆包")) {
                result = browserController.checkDBLogin(userId);
            }
            //TODO 后续添加其他AI的登录判断

            if (result == null || result.equals("false") || result.equals("未登录")) {
                return McpResult.fail("您未登录" + aiName, "");
            }
            if (roles != null && roles.contains(aiConfig)) {
                McpResult mcpResult = null;
                if (aiName.contains("腾讯元宝T1") || aiName.contains("腾讯元宝DS")) {
                    mcpResult = aigcController.startYB(userInfoRequest);
                }
                if (aiName.contains("豆包")) {
                    mcpResult = aigcController.startDB(userInfoRequest);
                }
                //TODO 后续添加对其他AI判断执行

                if (mcpResult == null || mcpResult.getCode() != 200) {
                    return McpResult.fail(aiName + "调用失败,请稍后重试", null);
                }
                if (mcpResult.getShareUrl() == null) {
                    return McpResult.fail("对话链接获取失败,请稍后重试", "");
                }
                return McpResult.success(aiName + "调用成功", mcpResult.getShareUrl());
            }
            return McpResult.fail("暂不支持该AI", "");
        } catch (Exception e) {
            UserLogUtil.sendExceptionLog(userInfoRequest.getUserId(),
                    aiName + "任务执行异常", methodName, e, url + "/saveLogInfo");
            return McpResult.fail(aiName + "调用异常,请联系管理员", null);
        }
    }

    @Tool(name = "投递到公众号", description = "通过用户信息发布公众号文章,需要用户unionId,ai配置信息,提示词")
    public McpResult publishToOffice(@ToolParam(description = "用户调用信息,包括用户unionId,用户提示词,用户选择的ai配置信息")
                                     UserInfoRequest userInfoRequest) {
        try {
            String roles = userInfoRequest.getRoles();
            String unionId = userInfoRequest.getUnionId();
            String userId = null;
            if (unionId != null && !unionId.isEmpty()) {
                userId = userInfoUtil.getUserIdByUnionId(unionId);
            }
            if (userId == null) {
                return McpResult.fail("您无权限访问,请联系管理员", "");
            }
            userInfoRequest.setUserId(userId);
            String result = browserController.checkYBLogin(userId);
            if (result == null || result.equals("false") || result.equals("未登录")) {
                return McpResult.fail("您未登录腾讯元宝", "");
            }
            String znpbPrompt = """
                    请根据以上链接或内容，以及已知的图片链接等信息整理为适合微信公众号服务号发布的 HTML 格式文章。要求严格遵循微信公众号图文消息规范，确保适配草稿箱接口 content 字段可正常解析：
                    角色： 你是一位专业的微信服务号内容编辑和HTML开发者，精通微信公众平台的排版规范和API要求。
                                        
                    任务： 根据我提供的主题，生成一篇可以直接通过微信服务号API发布文章的HTML代码。
                                        
                    核心要求与约束：
                                        
                    标题格式（强制要求）：
                                        
                    文章标题必须是整个HTML代码的第一句。
                                        
                    标题必须使用中文书名号《》包裹，例如：《这是一篇示例标题》。
                                        
                    绝对禁止使用任何HTML标签（如<h1>、<p>、<strong>）来包裹标题。
                                        
                    标题后直接换行，开始正文的HTML代码。
                                        
                    内容格式（微信规范）：
                    
                    不要出现无用的字符，比如[1](@ref)
                    
                    正文部分使用HTML标签进行排版，整体包裹在一个<div>标签内。
                                        
                    使用<p>标签表示段落，段落之间不要空行，微信会自动处理段间距。
                                        
                    使用<strong>标签加粗文本，而不是<b>标签。
                                        
                    使用<em>标签倾斜文本，而不是<i>标签。
                                        
                    如有列表，使用<ul>和<li>标签。
                                        
                    图片使用<img>标签，并确保提供正确的src（URL地址），同时必须包含data-ratio和data-w属性。示例：
                    <img data-ratio="0.75" data-w="800" src="https://example.com/image.jpg">
                    
                    你需要根据图片的描述信息，选择合适的图片并插入。
                                        
                    可以适当使用内联样式，但必须简单，如style="text-align: center;"用于居中，style="font-size: 14px; color: #999;"用于说明文字。
                                        
                    输出格式：
                                        
                    最终输出必须是纯净的、完整的、可直接使用的HTML代码块。
                                        
                    除了必要的HTML标签外，不要包含任何额外的解释、注释、引言或Markdown代码块标记（如html）。
                    """;
            List<Item> images = getMaterialByType("image", unionId);
            List<ImgInfo> imgInfoList = new ArrayList<>();
            for (Item image : images) {
                String name = image.getName();
                if(name.contains(unionId))  {
                    ImgInfo imgInfo = new ImgInfo();
                    imgInfo.setImgDescription(name.substring(name.indexOf("-")));
                    imgInfo.setImgUrl(image.getUrl());
                    imgInfoList.add(imgInfo);
                }
            }
            userInfoRequest.setUserPrompt(userInfoRequest.getUserPrompt() + "图片信息:" + imgInfoList.toString() + znpbPrompt);
            McpResult mcpResult = aigcController.startYBOffice(userInfoRequest);
            if (mcpResult == null) {
                return McpResult.fail("腾讯元宝DS调用失败,请稍后重试", null);
            }


            String shareUrl = mcpResult.getShareUrl();
            String contentText = mcpResult.getResult();
            int first = contentText.indexOf("《");
            int second = contentText.indexOf("》", first + 1);
            String title = contentText.substring(first + 1, second);
            contentText = contentText.substring(second + 1, contentText.lastIndexOf(">") + 1);
            contentText = contentText.replaceAll("\r\n\r\n", "");
            if (shareUrl != null && !shareUrl.isEmpty()) {
                shareUrl = "原文链接：" + shareUrl + "<br><br>";
                contentText = shareUrl + contentText;
            }
            // 2. 构建草稿对象（直接使用已有素材的media_id）
            WcOfficeAccount wo = weChatUtils.getOfficeAccountByUserId(userId);
            if (wo == null) {
                throw new RuntimeException(WxExceptionConstants.WX_AUTH_EXCEPTION);
            }
            this.wxMpService = wechatMpConfig.getWxMpService(unionId);
            WxMpDraftArticles draft = new WxMpDraftArticles();
            draft.setTitle(title);
            draft.setContent(contentText); // 包含图片标签的最终内容
            draft.setThumbMediaId(wo.getMediaId()); // 直接使用已有封面图media_id
            draft.setShowCoverPic(1); // 显示封面
            WxMpAddDraft wxMpAddDraft = WxMpAddDraft.builder().articles(List.of(draft)).build();
            // 3. 调用微信接口上传草稿
            String mediaId = wxMpService.getDraftService().addDraft(wxMpAddDraft);
            String publishedArticleUrl = getPublishedArticleUrl(mediaId);
            if (publishedArticleUrl == null || publishedArticleUrl.isEmpty()) {
                return McpResult.fail("发布失败,请稍后重试", null);
            } else {
                return McpResult.success(publishedArticleUrl, "");
            }
        } catch (Exception e) {
            UserLogUtil.sendExceptionLog(userInfoRequest.getUserId(),
                    "投递到公众号任务执行异常", "ybMcp", e, url + "/saveLogInfo");
            return McpResult.fail("投递到公众号异常,请联系管理员", null);
        }
    }

    /**
     * 发布草稿并获取正式图文的永久URL
     *
     * @param draftMediaId 草稿的media_id
     * @return 正式图文的URL
     */
    public String getPublishedArticleUrl(String draftMediaId) throws WxErrorException {
        WxMpDraftInfo draft = wxMpService.getDraftService()
                .getDraft(draftMediaId);
        String url = "";
        List<WxMpDraftArticles> newsItem = draft.getNewsItem();
        for (WxMpDraftArticles wxMpDraftArticles : newsItem) {
            url = wxMpDraftArticles.getUrl();
        }
        return url;
    }

    @Tool(name = "获取图片素材", description = "获取图片素材")
    public McpResult getMaterial(@ToolParam(description = "用户调用信息,包括用户unionId,用户提示词,用户选择的ai配置信息")
                                 UserInfoRequest userInfoRequest) throws WxErrorException {
        try {
            List<Item> image = getMaterialByType("image", userInfoRequest.getUnionId());
            return McpResult.success(JSONObject.toJSONString(image), "");
        } catch (Exception e) {
            throw e;
        }
    }

    @Tool(name = "上传图片素材", description = "上传图片素材")
    public McpResult uploadMaterial(@ToolParam(description = "用户调用信息,必须包含用户unionId,图片描述，图片路径")
                                    UserInfoRequest userInfoRequest,
                                    @ToolParam(description = "图片描述信息")
                                    String imgDescription) throws Exception {
        try {
            String imgUrl = uploadMaterialByUrl("image", userInfoRequest.getImageUrl(), userInfoRequest.getUnionId(), imgDescription);
            if (imgUrl == null || imgUrl.isEmpty()) {
                return McpResult.fail("上传图片素材失败", "");
            }
            return McpResult.success(imgUrl, "");
        } catch (Exception e) {
            throw e;
        }
    }
    @Tool(name = "生成图片", description = "生成图片")
    public McpResult generateImage(@ToolParam(description = "用户调用信息,必须包含unionId,图片描述")
                                   UserInfoRequest userInfoRequest,
                                   @ToolParam(description = "图片描述信息")
                                   String imgDescription) throws Exception {
        try {
            userInfoRequest.setTaskId(UUID.randomUUID().toString());
            String roles = "zj-db,";
            String unionId = userInfoRequest.getUnionId();
            String userId = null;
            if (unionId != null && !unionId.isEmpty()) {
                userId = userInfoUtil.getUserIdByUnionId(unionId);
            }
            if (userId == null) {
                return McpResult.fail("您无权限访问,请联系管理员", "");
            }
            userInfoRequest.setUserId(userId);
            userInfoRequest.setRoles(roles);
            userInfoRequest.setUserPrompt(imgDescription + "\n根据以上描述信息,生成对应的图片");
            userInfoRequest.setImageDescription(imgDescription);
            return aigcController.startDBImg(userInfoRequest);
        } catch (Exception e) {
            throw e;
        }
    }

    public String uploadMaterialByUrl(String type, String url, String unionId, String description) throws Exception {
        try {
            InputStream inputStream = HttpUtil.getImageStreamByHttpClient(url);
            if (inputStream == null) {
                throw new RuntimeException(WxExceptionConstants.WX_URL_INVALID_EXCEPTION);
            }
            return uploadImgMaterial(unionId, inputStream, description);
        } catch (WxErrorException e) {
            throw e;
        }
    }
    public McpResult uploadMaterialByStream(String type, InputStream inputStream, String unionId, String description) throws Exception {
        try {
            String imgUrl = uploadImgMaterial(unionId, inputStream, description);
            return McpResult.success("图片生成成功", imgUrl);
        } catch (WxErrorException e) {
            throw e;
        }
    }
    private String uploadImgMaterial(String unionId, InputStream inputStream, String description) throws WxErrorException, IOException {
        try {
            this.wxMpService = wechatMpConfig.getWxMpService(unionId);
            if (wxMpService == null) {
                throw new RuntimeException(WxExceptionConstants.WX_AUTH_EXCEPTION);
            }
            String imgUrl = tmpUrl + "/img/" + unionId + "-" + description + ".png";
            File file = new File(imgUrl);
            FileOutputStream fileOutputStream = new FileOutputStream(file);
            IoUtil.copy(inputStream, fileOutputStream);
            WxMpMaterial wxMpMaterial = new WxMpMaterial();
            wxMpMaterial.setFile(file);
            wxMpMaterial.setName(description);
            WxMpMaterialUploadResult result = wxMpService.getMaterialService().materialFileUpload("image", wxMpMaterial);
            String mediaId = result.getMediaId();
            System.out.println(mediaId);
            imgUrl = result.getUrl();
            return imgUrl;
        } catch (Exception e) {
            throw new RuntimeException(WxExceptionConstants.WX_UPLOAD_IMG_EXCEPTION);
        }
    }

    public List<Item> getMaterialByType(String type, String unionId) throws WxErrorException {
        try {
            int page = 0;
            int count = 20;
            WxMpService wxMpService = wechatMpConfig.getWxMpService(unionId);
            if (wxMpService == null) {
                throw new RuntimeException(WxExceptionConstants.WX_AUTH_EXCEPTION);
            }
            WxMpMaterialFileBatchGetResult result = wxMpService.getMaterialService()
                    .materialFileBatchGet(type, page, count);
            // 总素材数
            int totalCount = result.getTotalCount();
            System.out.println("图片素材总数：" + totalCount);

            List<Item> items = new ArrayList<>();
            // 本次查询到的素材列表
            List<WxMpMaterialFileBatchGetResult.WxMaterialFileBatchGetNewsItem> materials = result.getItems();
            for (WxMpMaterialFileBatchGetResult.WxMaterialFileBatchGetNewsItem material : materials) {
                Item item = new Item();
                item.setMedia_id(material.getMediaId());
                item.setName(material.getName());
                item.setUrl(material.getUrl());
                item.setUpdate_time(material.getUpdateTime());
                items.add(item);
            }
            return items;
        } catch (Exception e) {
            throw new RuntimeException("获取" + type + "素材失败");
        }
    }
}
