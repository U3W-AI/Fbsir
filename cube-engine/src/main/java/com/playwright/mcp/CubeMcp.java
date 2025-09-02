package com.playwright.mcp;

import cn.hutool.json.JSONUtil;
import com.alibaba.fastjson.JSONObject;
import com.playwright.constants.WxExceptionConstants;
import com.playwright.controller.AIGCController;
import com.playwright.controller.BrowserController;
import com.playwright.entity.UserInfoRequest;
import com.playwright.entity.mcp.*;
import com.playwright.utils.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.chanjar.weixin.common.error.WxErrorException;
import org.apache.hc.core5.http.ParseException;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.*;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * @author muyou
 * dateStart 2024/8/4 9:34
 * dateNow   2025/8/20 10:14
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class CubeMcp {
    @Value("${cube.url}")
    private String url;
    @Autowired
    private AIGCController aigcController;

    private final UserInfoUtil userInfoUtil;
    private final BrowserController browserController;

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
            String prompt = userInfoRequest.getUserPrompt();
//            先检查传递内容是否是链接
            String regex = "https?://[^\s]+";
            Pattern pattern = Pattern.compile(regex);
            Matcher matcher = pattern.matcher(prompt);
            String promptUrl = null;
            if (matcher.find()) {
                promptUrl = matcher.group();
            }
            String content = null;
            if(promptUrl != null) {
                String getContentPrompt = promptUrl + " 获取以上链接内容";
//                无需深度思考和联网搜索
                userInfoRequest.setRoles("yb-deepseek-pt, znpb");
                userInfoRequest.setUserPrompt(getContentPrompt);
                McpResult mcpResult = aigcController.startYBOffice(userInfoRequest);
                content = mcpResult.getResult();
                if(mcpResult.getCode() != 200 || content == null || content.isEmpty()) {
                    return McpResult.fail("获取链接内容失败,请稍后重试", "");
                }
            }
            userInfoRequest.setUserPrompt(content);
            // 获取提示词
            String json = HttpUtil.doGet(url.substring(0, url.lastIndexOf("/")) + "/media/getCallWord/wechat_layout", null);
            JSONObject jsonObject = JSONObject.parseObject(json);
            String znpbPrompt = jsonObject.get("data").toString();

            // 获取图片信息
            McpResult mcp = getMaterial(userInfoRequest);
            String listJson = mcp.getResult();
            String thumbMediaId = null;
            List<Item> images = JSONUtil.toList(listJson, Item.class);
            List<ImgInfo> imgInfoList = new ArrayList<>();

            for (Item image : images) {
                String name = image.getName();
                if (name.contains(unionId)) {
                    if(thumbMediaId == null && name.contains("封面")) {
                        thumbMediaId = image.getMedia_id();
                        continue;
                    }
                    ImgInfo imgInfo = new ImgInfo();
                    imgInfo.setImgDescription(name.substring(name.indexOf("-")));
                    imgInfo.setImgUrl(image.getUrl());
                    imgInfoList.add(imgInfo);
                }
            }
            if(imgInfoList.isEmpty()) {
                userInfoRequest.setUserPrompt(userInfoRequest.getUserPrompt() + " " + znpbPrompt);
            } else {
                userInfoRequest.setUserPrompt(userInfoRequest.getUserPrompt() + ", 图片信息:" + imgInfoList.toString() + " " + znpbPrompt);
            }
//            设置为默认AI配置
            userInfoRequest.setRoles("znpb-ds,yb-deepseek-pt,yb-deepseek-sdsk,yb-deepseek-lwss,");
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

            String postUrl = url.substring(0, url.lastIndexOf("/")) + "/wx/publishToOffice";
            Map<String, Object> map = new HashMap<>();
            map.put("title", title);
            map.put("contentText", contentText);
            map.put("unionId", unionId);
            map.put("shareUrl", shareUrl);
            map.put("thumbMediaId", thumbMediaId);
            String jsonResult = HttpUtil.doPostJson(postUrl, map);
            ResultBody resultBody = getResult(jsonResult);
            long code = resultBody.getCode();
            String res = resultBody.getData().toString();
//            成功获取
            if (code == 200 && res != null && !res.isEmpty()) {
                return McpResult.success("草稿保存成功", res);
            } else {
                return McpResult.fail(res, null);
            }
        } catch (Exception e) {
            UserLogUtil.sendExceptionLog(userInfoRequest.getUserId(),
                    "投递到公众号任务执行异常", "ybMcp", e, url + "/saveLogInfo");
            return McpResult.fail("投递到公众号异常,请联系管理员", null);
        }
    }

    @Tool(name = "获取图片素材", description = "获取图片素材")
    public McpResult getMaterial(@ToolParam(description = "用户调用信息,必须包括用户unionId")
                                 UserInfoRequest userInfoRequest) throws WxErrorException, IOException, ParseException {
        try {
            String unionId = userInfoRequest.getUnionId();
            if (unionId == null || unionId.isEmpty()) {
                throw new RuntimeException(WxExceptionConstants.WX_PARAMETER_EXCEPTION);
            }
            Map<String, Object> map = new HashMap<>();
            String postUrl = url.substring(0, url.lastIndexOf("/")) + "/wx/getMaterial";
            map.put("unionId", unionId);
            map.put("type", "image");
            String json = HttpUtil.doPostJson(postUrl, map);
            ResultBody resultBody = getResult(json);
            long code = resultBody.getCode();
            Object res = resultBody.getData();
            List<Item> itemList = JSONObject.parseArray(res.toString(), Item.class);
//            成功获取
            if (code == 200 && itemList != null && !itemList.isEmpty()) {
                return McpResult.success(JSONObject.toJSONString(itemList), null);
            } else {
                return McpResult.fail("获取图片失败", null);
            }
        } catch (Exception e) {
            throw e;
        }
    }

    @Tool(name = "上传图片素材", description = "上传图片素材")
    public McpResult uploadImgMaterial(@ToolParam(description = "用户调用信息,必须包含用户unionId")
                                       UserInfoRequest userInfoRequest,
                                       @ToolParam(description = "图片描述信息")
                                       String imgDescription,
                                       @ToolParam(description = "图片路径")
                                       String imageUrl) throws Exception {
        try {
            userInfoRequest.setImageUrl(imageUrl);
            userInfoRequest.setImageDescription(imgDescription);
            McpResult mcpResult = uploadMaterialByUrl("image", userInfoRequest.getImageUrl(), userInfoRequest.getUnionId(), userInfoRequest.getImageDescription());
            if (mcpResult == null || mcpResult.getShareUrl() == null || mcpResult.getShareUrl().isEmpty()) {
                return McpResult.fail("上传图片素材失败", "");
            }
            return mcpResult;
        } catch (Exception e) {
            throw e;
        }
    }

    @Tool(name = "生成图片", description = "调用豆包生成图片")
    public McpResult generateImgMaterial(@ToolParam(description = "用户调用信息,必须包含unionId")
                                         UserInfoRequest userInfoRequest,
                                         @ToolParam(description = "图片描述信息")
                                         String imgDescription) throws Exception {
        try {
            userInfoRequest.setImageDescription(imgDescription);
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
            return aigcController.startDBImg(userInfoRequest);
        } catch (Exception e) {
            throw e;
        }
    }

    @Tool(name = "上传公众号文章封面", description = "设置公众号文章封面, 未设置则使用上一次或者默认封面")
    public McpResult uploadCoverImgMaterial(@ToolParam(description = "用户调用信息,必须包含用户unionId")
                                            UserInfoRequest userInfoRequest,
                                            @ToolParam(description = "图片路径")
                                            String imageUrl) throws Exception {
        try {
            userInfoRequest.setImageUrl(imageUrl);
            userInfoRequest.setImageDescription("封面");
            McpResult mcpResult = uploadMaterialByUrl("image", userInfoRequest.getImageUrl(), userInfoRequest.getUnionId(), userInfoRequest.getImageDescription());
            if (mcpResult == null || mcpResult.getShareUrl() == null || mcpResult.getShareUrl().isEmpty()) {
                return McpResult.fail("上传图片素材失败", "");
            }
            return mcpResult;
        } catch (Exception e) {
            throw e;
        }
    }

    @Tool(name = "AI生成公众号文章封面", description = "调用豆包生成图片并设置公众号文章封面, 未设置则使用上一次或者默认封面")
    public McpResult generateCoverImgMaterial(@ToolParam(description = "用户调用信息,必须包含用户unionId")
                                              UserInfoRequest userInfoRequest,
                                              @ToolParam(description = "图片描述信息")
                                              String imgDescription) throws Exception {
        try {
            McpResult mcpResult = generateImgMaterial(userInfoRequest,  "生成公众号文章封面," + imgDescription);
            if (mcpResult == null || mcpResult.getShareUrl() == null || mcpResult.getShareUrl().isEmpty()) {
                return McpResult.fail("上传图片素材失败", "");
            }
            return mcpResult;
        } catch (Exception e) {
            throw e;
        }
    }


    public McpResult uploadMaterialByUrl(String type, String url, String unionId, String description) throws Exception {
        try {
            if (type.equals("image")) {
                InputStream inputStream = HttpUtil.getImageStreamByHttpClient(url);
                if (inputStream == null) {
                    throw new RuntimeException(WxExceptionConstants.WX_URL_INVALID_EXCEPTION);
                }
                String imgUrl = uploadImgMaterial(unionId, inputStream, description);
                if (imgUrl == null) {
                    return McpResult.fail("图片链接获取失败", null);
                }
                return McpResult.success("图片生成成功", imgUrl);
            }
            //TODO 对不同素材的判断,后续会添加视频,图文等
            return McpResult.fail("上传素材失败", "");
        } catch (WxErrorException e) {
            throw e;
        }
    }

    public McpResult uploadMaterialByStream(String type, InputStream inputStream, String unionId, String description) throws Exception {
        try {
            if (type.equals("image")) {
                String imgUrl = uploadImgMaterial(unionId, inputStream, description);
                if (imgUrl == null) {
                    return McpResult.fail("图片链接获取失败", null);
                }
                return McpResult.success("图片生成成功", imgUrl);
            }
            return McpResult.fail("上传素材失败", "");
            //TODO 对不同素材的判断,后续会添加视频,图文等
        } catch (WxErrorException e) {
            throw e;
        }
    }

    private String uploadImgMaterial(String unionId, InputStream inputStream, String description) throws WxErrorException, IOException {
        try {
            String postUrl = url.substring(0, url.lastIndexOf("/")) + "/wx/uploadMaterial";
            Map<String, Object> map = new HashMap<>();
            map.put("type", "image");
            map.put("unionId", unionId);
            if(description.contains("文章封面")) {
                map.put("imgDescription", "封面");
            } else {
                map.put("imgDescription", description);
            }
            String json = HttpUtil.doPostWithFile(postUrl, map, "multipartFile",
                    inputStream, "image/png", "imgMaterial.png");
            ResultBody resultBody = getResult(json);
            long code = resultBody.getCode();
            String res = resultBody.getData().toString();
//            成功获取
            if (code == 200 && res != null && !res.isEmpty()) {
                return res;
            } else {
                return null;
            }
        } catch (Exception e) {
            throw new RuntimeException(WxExceptionConstants.WX_UPLOAD_IMG_EXCEPTION);
        }
    }

    private ResultBody getResult(String json) {
        return JSONObject.parseObject(json).toJavaObject(ResultBody.class);
    }
}
