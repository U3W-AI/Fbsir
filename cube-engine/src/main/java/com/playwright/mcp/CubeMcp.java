package com.playwright.mcp;

import com.alibaba.fastjson.JSONObject;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Download;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.opencsv.CSVReader;
import com.opencsv.CSVReaderBuilder;
import com.playwright.controller.AIGCController;
import com.playwright.controller.BrowserController;
import com.playwright.entity.UserInfoRequest;
import com.playwright.entity.mcp.Company;
import com.playwright.entity.mcp.McpResult;
import com.playwright.utils.BrowserUtil;
import com.playwright.utils.UserInfoUtil;
import com.playwright.utils.UserLogUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
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
    private final AIGCController aigcController;
    private final BrowserUtil browserUtil;
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
            if(aiName.contains("腾讯元宝T1") || aiName.contains("腾讯元宝DS")) {
                result = browserController.checkYBLogin(userId);
            }
            if(aiName.contains("豆包")) {
                result = browserController.checkDBLogin(userId);
            }
            //TODO 后续添加其他AI的登录判断

            if (result == null || result.equals("false") || result.equals("未登录")) {
                return McpResult.fail("您未登录" + aiName, "");
            }
            if (roles != null && roles.contains(aiConfig)) {
                McpResult mcpResult = null;
                if(aiName.contains("腾讯元宝T1") || aiName.contains("腾讯元宝DS")) {
                    mcpResult = aigcController.startYB(userInfoRequest);
                }
                if(aiName.contains("豆包")) {
                    mcpResult = aigcController.startDB(userInfoRequest);
                }
                //TODO 后续添加对其他AI判断执行

                if (mcpResult == null || mcpResult.getCode() != 200) {
                    return McpResult.fail(aiName + "调用失败,请稍后重试", null);
                }
                if(mcpResult.getShareUrl() ==  null) {
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
                    请根据以上链接或内容整理为适合微信公众号服务号发布的 HTML 格式文章。要求严格遵循微信公众号图文消息规范，确保适配草稿箱接口 content 字段可正常解析：
                    1.	整体结构清晰，使用符合微信规范的 HTML 标签进行格式化，支持<h1>-<h6>标题标签（建议<h2>-<h4>为主，避免层级过深）
                    2.	重要信息必须使用<strong>标签加粗，避免使用<b>标签
                    3.	代码块统一用<pre><code>标记\s
                    4.	列表严格使用<ul><li>（无序列表）或<ol><li>（有序列表），列表内不嵌套复杂结构
                    5.	所有正文段落必须用<p>标签包裹，段落间保留适当间距，提升可读性
                    6.	支持简单表格，使用<table>标签，建议添加<thead>和<tbody>区分表头和内容，\s
                    7.	超链接使用<a href="链接地址" target="_blank">链接文本</a>，外部链接需添加target="_blank"
                    8.	可使用<br>标签进行强制换行，避免使用空<p>标签占位
                    9.	标题需放在内容最前端，用《》包裹且不添加任何 HTML 标签，标题后直接跟随格式化后的正文内容
                    10.	禁止使用<script>、<style>等可能被微信过滤的标签，避免使用复杂 CSS 样式
                    11.	删除所有不必要的格式标记和空标签，保持代码简洁，确保接口提交成功
                    12.	如果一个大点有多个小点，，注意缩进，可通过text-indent属性设置首行缩进如em，保证美观 （重点注意）
                    13.	每个段落或者每一个大要点(小要点不需要)之间要空一行出来，可以使用<br>换行或者<p>空内容进行空白换行（重点注意），整体要美观
                    14.	也可以定义部分内容的颜色如<span style="color: 颜色值;">需要设置颜色的文字</span>
                    15.  不要出现其他无关的话，只能生成要求的内容（重点注意）
                    16.  不允许出现编号，如"一，二”等
                    """;
            userInfoRequest.setUserPrompt(userInfoRequest.getUserPrompt() + znpbPrompt);
            McpResult mcpResult = aigcController.startYBOffice(userInfoRequest);
            if (mcpResult == null) {
                return McpResult.fail("腾讯元宝DS调用失败,请稍后重试", null);
            }
//            发布到公众号
            CloseableHttpClient httpClient = HttpClients.createDefault();
            // 1. 构建请求URL（不含参数）
            String postUrl = url.substring(0, url.lastIndexOf("/")) + "/mini/pushAutoOffice";
            HttpPost httpPost = new HttpPost(postUrl);

            // 2. 构建请求参数（Map）
            Map<String, String> params = new HashMap<>();
            params.put("userId", userId);
            params.put("aiName", "");
            params.put("contentText", mcpResult.getResult());
            params.put("shareUrl", mcpResult.getShareUrl());
            params.put("num", "1");

            // 3. 将参数转换为JSON字符串，设置为请求体
            String jsonParams = new ObjectMapper().writeValueAsString(params);
            StringEntity entity = new StringEntity(jsonParams, ContentType.parse("UTF-8"));
            httpPost.setEntity(entity);

            // 4. 设置Content-Type为JSON（关键）
            httpPost.setHeader("Content-Type", "application/json");

            // 5. 执行请求
            CloseableHttpResponse response = httpClient.execute(httpPost);
            int code = response.getCode();
            String responseJsonStr = EntityUtils.toString(
                    response.getEntity(),
                    StandardCharsets.UTF_8 // 必须指定 UTF-8，避免中文乱码
            );
//        将String 这个json转成map
            Map<String, Object> map = (Map<String, Object>)JSONObject.parse(responseJsonStr);
            if (code == 200) {
                String url = (String) (map.get("data"));
                if(url != null) {
                    return McpResult.success("发布成功", url);
                }
                return McpResult.fail("获取链接失败,请联系管理员", null);
            }
            return McpResult.fail("发布失败,请稍后重试", null);
        } catch (Exception e) {
            UserLogUtil.sendExceptionLog(userInfoRequest.getUserId(),
                    "投递到公众号任务执行异常", "ybMcp", e, url + "/saveLogInfo");
            return McpResult.fail("投递到公众号异常,请联系管理员", null);
        }
    }

    @Tool(name = "世界市值排名前100", description = "获取世界市值排名前100")
    public List<Company> getWorldMarketCapRank() throws IOException, InterruptedException {
        String url = "https://companiesmarketcap.com/";
        try {
            BrowserContext context = browserUtil.createPersistentBrowserContext(false, "22", "wcmcr");
            Page page = browserUtil.getOrCreatePage(context);
            page.navigate(url);
            page.waitForLoadState();
            Thread.sleep(3000);
            // 等待下载完成并获取Download对象
            Download download = page.waitForDownload(() -> {
                Locator locator = page.locator("(//img[@src='/img/download-icon-dark.svg'])[1]");
                locator.click();
            });

            // 获取CSV临时文件路径
            Path csvTempPath = download.path();
            System.out.println("CSV临时文件路径: " + csvTempPath);

            // 解析CSV内容
            // 替换原来的CSVReader创建代码
            File file = csvTempPath.toFile();
            FileInputStream fileInputStream = new FileInputStream(file);
            List<Company> list = new ArrayList<>();
            CSVReader csvReader = new CSVReaderBuilder(
                    new BufferedReader(
                            new InputStreamReader(fileInputStream, StandardCharsets.UTF_8))).build();
            Iterator<String[]> iterator = csvReader.iterator();
            int cnt = 0;
            while (iterator.hasNext()) {
                String[] next = iterator.next();
                if (cnt == 0) {
                    cnt++;
                    continue;
                }
                Company company = new Company();
                company.setRank(Integer.parseInt(next[0]));
                company.setName(next[1]);
                company.setSymbol(next[2]);
                company.setMarketCap(Long.parseLong(next[3]));
                company.setPrice(Double.parseDouble(next[4]));
                company.setCountry(next[5]);
                list.add(company);
                if (++cnt > 100) {
                    break;
                }
                System.out.println(Arrays.toString(next));
                //去除第一行的表头，从第二行开始
            }
            return list;
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

}
