package com.playwright.controller;

import cn.hutool.core.thread.ThreadException;
import com.microsoft.playwright.*;
import com.microsoft.playwright.options.AriaRole;
import com.microsoft.playwright.options.LoadState;
import com.playwright.entity.UserInfoRequest;
import com.playwright.entity.mcp.McpResult;
import com.playwright.mcp.CubeMcp;
import com.playwright.utils.*;
import com.playwright.websocket.WebSocketClientService;
import com.vladsch.flexmark.util.sequence.builder.tree.SegmentTree;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * AI生成内容控制器
 * 处理与各大AI平台（腾讯元宝、豆包等）的交互操作
 *
 * @author 优立方
 * @version JDK 17
 * @date 2025年01月21日 08:53
 */

@RestController
@RequestMapping("/api/browser")
@Tag(name = "AI生成内容控制器", description = "处理与各大AI平台（腾讯元宝、豆包等）的交互操作")
public class AIGCController {

    // 依赖注入 注入webSocketClientService 进行消息发送
    private final WebSocketClientService webSocketClientService;

    // 构造器注入WebSocket服务
    public AIGCController(WebSocketClientService webSocketClientService) {
        this.webSocketClientService = webSocketClientService;
    }

    // 从配置文件中注入URL 调用远程API存储数据
    @Value("${cube.url}")
    private String url;

    // 腾讯元宝相关操作工具类
    @Autowired
    private TencentUtil tencentUtil;

    // 豆包相关操作工具类
    @Autowired
    private DouBaoUtil douBaoUtil;


    // 日志记录工具类
    @Autowired
    private LogMsgUtil logInfo;

    // 浏览器操作工具类
    @Autowired
    private BrowserUtil browserUtil;

    @Autowired
    private ClipboardLockManager clipboardLockManager;


    // 百度AI相关操作工具类
    @Autowired
    private BaiduUtil baiduUtil;

    @Autowired
    private DeepSeekUtil deepSeekUtil;

    @Value("${cube.uploadurl}")
    private String uploadUrl;

    @Autowired
    private CubeMcp cubeMcp;

    /**
     * 处理腾讯元宝平台的请求
     *
     * @param userInfoRequest 包含用户信息和会话参数
     * @return 生成的内容（当前版本暂未实现内容返回）
     */
    @Operation(summary = "启动腾讯元宝内容生成", description = "根据角色执行不同类型的腾讯元宝任务（T1和DS）")
    @ApiResponse(responseCode = "200", description = "处理成功", content = @Content(mediaType = "application/json"))
    @PostMapping("/startYB")
    public McpResult startYB(@RequestBody UserInfoRequest userInfoRequest) throws Exception {
        String userId = userInfoRequest.getUserId();
        String roles = userInfoRequest.getRoles();
        String userPrompt = userInfoRequest.getUserPrompt();
        String t1ChatId = userInfoRequest.getToneChatId();
        String dschatId = userInfoRequest.getYbDsChatId();
        AtomicReference<McpResult> mcpResult = new AtomicReference<>(new McpResult());
        logInfo.sendTaskLog("元宝智能体任务开始，角色配置: " + roles, userId, "元宝智能体");
        try {
            CountDownLatch configCountDownLatch = new CountDownLatch(2);
            CountDownLatch mainCountDownLatch = new CountDownLatch(2);
            if (roles.contains("yb-hunyuan-pt")) {
                new Thread(() -> {
                    //======================腾讯元宝T1=======================//
                    try {
                        Page hyPage = tencentUtil.getPage("T1", userId);
                        long start = System.currentTimeMillis();
                        //腾讯元宝T1  根据角色组合处理不同模式（普通/深度思考/联网）
                        logInfo.sendTaskLog("腾讯元宝T1准备就绪，正在打开页面", userId, "腾讯元宝T1");
                        if (roles.contains("yb-hunyuan-pt") && !roles.contains("yb-hunyuan-sdsk") && !roles.contains("yb-hunyuan-lwss")) {
                            tencentUtil.handleYBAI(hyPage, userPrompt, "yb-hunyuan-pt", userId, "腾讯元宝T1", t1ChatId);
                        } else if (roles.contains("yb-hunyuan-sdsk") && !roles.contains("yb-hunyuan-lwss")) {
                            //深度思考
                            tencentUtil.handleYBAI(hyPage, userPrompt, "yb-hunyuan-sdsk", userId, "腾讯元宝T1", t1ChatId);
                        } else if (roles.contains("yb-hunyuan-lwss") && !roles.contains("yb-hunyuan-sdsk")) {
                            //联网
                            tencentUtil.handleYBAI(hyPage, userPrompt, "yb-hunyuan-lwss-1", userId, "腾讯元宝T1", t1ChatId);
                        } else if (roles.contains("yb-hunyuan-lwss") && roles.contains("yb-hunyuan-sdsk")) {
                            //深度思考 + 联网
                            tencentUtil.handleYBAI(hyPage, userPrompt, "yb-hunyuan-lwss-2", userId, "腾讯元宝T1", t1ChatId);
                        }
                        //保存入库 腾讯元宝T1 - T1和DS独立处理，各自发送响应
                        configCountDownLatch.countDown();
                        configCountDownLatch.await();
                        hyPage = tencentUtil.getPage("T1", userId);
                        if (roles.contains("yb-hunyuan-pt") && !roles.contains("yb-hunyuan-sdsk") && !roles.contains("yb-hunyuan-lwss")) {
                            mcpResult.set(tencentUtil.saveDraftData(hyPage, userInfoRequest, "yb-hunyuan-pt", userId));
                        } else if (roles.contains("yb-hunyuan-sdsk") && !roles.contains("yb-hunyuan-lwss")) {
                            //深度思考
                            mcpResult.set(tencentUtil.saveDraftData(hyPage, userInfoRequest, "yb-hunyuan-sdsk", userId));
                        } else if (roles.contains("yb-hunyuan-lwss")) {
                            //深度思考 + 联网
                            mcpResult.set(tencentUtil.saveDraftData(hyPage, userInfoRequest, "yb-hunyuan-lwss", userId));
                        }
                        UserLogUtil.sendNormalLog(userId, "启动腾讯元宝T1生成", "startYB", start, mcpResult.get().getResult(), url + "/saveLogInfo");
                    } catch (Exception e) {
                        logInfo.sendTaskLog("腾讯元宝T1执行异常", userId, "腾讯元宝T1");
                        UserLogUtil.sendExceptionLog(userId, "腾讯元宝T1执行异常", "startYB", e, url + "/saveLogInfo");
                    } finally {
                        if (configCountDownLatch.getCount() == 2) {
                            configCountDownLatch.countDown();
                        }
                        mainCountDownLatch.countDown();
                    }
                }).start();
            } else {
                configCountDownLatch.countDown();
                mainCountDownLatch.countDown();
            }

            //======================腾讯元宝DS=======================//
            if (roles.contains("yb-deepseek-pt")) {
                try {
                    Page dsPage = tencentUtil.getPage("DS", userId);
                    Long start = System.currentTimeMillis();
                    logInfo.sendTaskLog("腾讯元宝DS准备就绪，正在打开页面", userId, "腾讯元宝DS");
                    Thread.sleep(3000);
                    //腾讯元宝DS  根据角色组合处理不同模式（普通/深度思考/联网）
                    if (roles.contains("yb-deepseek-pt") && !roles.contains("yb-deepseek-sdsk") && !roles.contains("yb-deepseek-lwss")) {
                        tencentUtil.handleYBAI(dsPage, userPrompt, "yb-deepseek-pt", userId, "腾讯元宝DS", dschatId);
                    } else if (roles.contains("yb-deepseek-sdsk") && !roles.contains("yb-deepseek-lwss")) {
                        //深度思考
                        tencentUtil.handleYBAI(dsPage, userPrompt, "yb-deepseek-sdsk", userId, "腾讯元宝DS", dschatId);
                    } else if (roles.contains("yb-deepseek-lwss") && !roles.contains("yb-deepseek-sdsk")) {
                        //深度思考 + 联网
                        tencentUtil.handleYBAI(dsPage, userPrompt, "yb-deepseek-lwss-1", userId, "腾讯元宝DS", dschatId);
                    } else if (roles.contains("yb-deepseek-lwss") && roles.contains("yb-deepseek-sdsk")) {
                        //深度思考 + 联网
                        tencentUtil.handleYBAI(dsPage, userPrompt, "yb-deepseek-lwss-2", userId, "腾讯元宝DS", dschatId);
                    }
                    //保存入库 腾讯元宝DS - DS独立处理，发送自己的响应
                    configCountDownLatch.countDown();
                    configCountDownLatch.await();
                    dsPage = tencentUtil.getPage("DS", userId);
                    if (roles.contains("yb-deepseek-pt") && !roles.contains("yb-deepseek-sdsk") && !roles.contains("yb-deepseek-lwss")) {
                        mcpResult.set(tencentUtil.saveDraftData(dsPage, userInfoRequest, "yb-deepseek-pt", userId));
                    } else if (roles.contains("yb-deepseek-sdsk") && !roles.contains("yb-deepseek-lwss")) {
                        mcpResult.set(tencentUtil.saveDraftData(dsPage, userInfoRequest, "yb-deepseek-sdsk", userId));
                    } else if (roles.contains("yb-deepseek-lwss")) {
                        //深度思考 + 联网
                        mcpResult.set(tencentUtil.saveDraftData(dsPage, userInfoRequest, "yb-deepseek-lwss", userId));
                    }
                    UserLogUtil.sendNormalLog(userId, "启动腾讯元宝DS生成", "startYB", start, mcpResult.get().getResult(), url + "/saveLogInfo");
                } catch (Exception e) {
                    logInfo.sendTaskLog("腾讯元宝DS执行异常", userId, "腾讯元宝DS");
                    UserLogUtil.sendExceptionLog(userId, "腾讯元宝DS执行异常", "startYB", e, url + "/saveLogInfo");
                } finally {
                    if (configCountDownLatch.getCount() == 2) {
                        configCountDownLatch.countDown();
                    }
                    mainCountDownLatch.countDown();
                }
            } else {
                configCountDownLatch.countDown();
                mainCountDownLatch.countDown();
            }
            mainCountDownLatch.await();
            // 等待所有线程执行完毕
            System.out.println("DS跟T1执行完成");
            return mcpResult.get();
        } catch (Exception e) {
            throw e;
        }
    }

    @Operation(summary = "豆包智能评分", description = "调用豆包平台对内容进行评分并返回评分结果")
    @ApiResponse(responseCode = "200", description = "处理成功", content = @Content(mediaType = "application/json"))
    @PostMapping("/startDBScore")
    public McpResult startDBScore(@io.swagger.v3.oas.annotations.parameters.RequestBody(description = "用户信息请求体", required = true,
            content = @Content(schema = @Schema(implementation = UserInfoRequest.class))) @RequestBody UserInfoRequest userInfoRequest) throws IOException, InterruptedException {
        try (BrowserContext context = browserUtil.createPersistentBrowserContext(false, userInfoRequest.getUserId(), "db")) {

            // 初始化变量
            String userId = userInfoRequest.getUserId();
            String dbchatId = userInfoRequest.getDbChatId();
            logInfo.sendTaskLog("智能评分准备就绪，正在打开页面", userId, "智能评分");
            String roles = userInfoRequest.getRoles();
            String userPrompt = userInfoRequest.getUserPrompt();

            // 初始化页面并导航到指定会话
            Page page = browserUtil.getOrCreatePage(context);
            if (dbchatId != null) {
                page.navigate("https://www.doubao.com/chat/" + dbchatId);
            } else {
                page.navigate("https://www.doubao.com/chat/");
            }

            page.waitForLoadState(LoadState.LOAD);
            Thread.sleep(500);
            logInfo.sendTaskLog("智能评分页面打开完成", userId, "智能评分");
            // 定位深度思考按钮
            Locator deepThoughtButton = page.locator("button.semi-button:has-text('深度思考')");
            // 检查按钮是否包含以 active- 开头的类名
            Boolean isActive = (Boolean) deepThoughtButton.evaluate("element => {\n" +
                    "    const classList = Array.from(element.classList);\n" +
                    "    return classList.some(cls => cls.startsWith('active-'));\n" +
                    "}");

            // 确保 isActive 不为 null
            if (isActive != null && !isActive && roles.contains("db-sdsk")) {
                deepThoughtButton.click();
                // 点击后等待一段时间，确保按钮状态更新
                Thread.sleep(1000);

                // 再次检查按钮状态
                isActive = (Boolean) deepThoughtButton.evaluate("element => {\n" +
                        "    const classList = Array.from(element.classList);\n" +
                        "    return classList.some(cls => cls.startsWith('active-'));\n" +
                        "}");
                if (isActive != null && !isActive) {
                    deepThoughtButton.click();
                    Thread.sleep(1000);
                }
                logInfo.sendTaskLog("已启动深度思考模式", userId, "智能评分");
            }
            Thread.sleep(1000);
            page.locator("[data-testid='chat_input_input']").click();
            Thread.sleep(1000);
            page.locator("[data-testid='chat_input_input']").fill(userPrompt);
            logInfo.sendTaskLog("用户指令已自动输入完成", userId, "智能评分");
            Thread.sleep(1000);
            page.locator("[data-testid='chat_input_input']").press("Enter");
            logInfo.sendTaskLog("指令已自动发送成功", userId, "智能评分");

            // 创建定时截图线程
            AtomicInteger i = new AtomicInteger(0);
            ScheduledExecutorService screenshotExecutor = Executors.newSingleThreadScheduledExecutor();
            // 🔥 优化：启动定时任务，增加页面状态检查和错误处理
            ScheduledFuture<?> screenshotFuture = screenshotExecutor.scheduleAtFixedRate(() -> {
                try {
                    // 检查页面是否已关闭
                    if (page.isClosed()) {
                        return;
                    }

                    int currentCount = i.getAndIncrement();
                    logInfo.sendImgData(page, userId + "智能评分执行过程截图" + currentCount, userId);
                } catch (com.microsoft.playwright.impl.TargetClosedError e) {
                } catch (PlaywrightException e) {
                } catch (Exception e) {
                    // 只记录严重错误到日志系统
                    if (e.getMessage() != null && !e.getMessage().toLowerCase().contains("timeout")) {
                        UserLogUtil.sendExceptionLog(userId, "智能评分截图", "startDBScore", e, url + "/saveLogInfo");
                    }
                }
            }, 1000, 6000, TimeUnit.MILLISECONDS); // 🔥 优化：延迟1秒开始，每6秒执行一次

            logInfo.sendTaskLog("开启自动监听任务，持续监听智能评分回答中", userId, "智能评分");
            // 等待复制按钮出现并点击
//            String copiedText =  douBaoUtil.waitAndClickDBCopyButton(page,userId,roles);
            //等待html片段获取完成
            String copiedText = douBaoUtil.waitDBHtmlDom(page, userId, "智能评分");
            //关闭截图
            screenshotFuture.cancel(false);
            screenshotExecutor.shutdown();

            boolean isRight;

            Locator chatHis = page.locator("//*[@id=\"root\"]/div[1]/div/div[3]/div/main/div/div/div[2]/div/div[1]/div/div/div[2]/div[2]/div/div/div/div/div/div/div[1]/div/div/div[2]/div[1]/div/div");
            if (chatHis.count() > 0) {
                isRight = true;
            } else {
                isRight = false;
            }

            AtomicReference<String> shareUrlRef = new AtomicReference<>();

            clipboardLockManager.runWithClipboardLock(() -> {
                try {
                    if (isRight && page.locator("//*[@id=\"root\"]/div[1]/div/div[3]/aside/div[2]/div/div[1]/div/div[1]/div[3]/div/div/div/div[4]").count() > 0) {
                        page.locator("//*[@id=\"root\"]/div[1]/div/div[3]/aside/div[2]/div/div[1]/div/div[1]/div[3]/div/div/div/div[4]").click();
                        Thread.sleep(1000);
                        page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("公开分享")).click();
                        Thread.sleep(500);
                    } else {
                        page.locator("button[data-testid='message_action_share']").last().click();
                        Thread.sleep(2000);
                        page.locator("button[data-testid='thread_share_copy_btn']").first().click();
                    }

                    // 建议适当延迟等待内容更新
                    Thread.sleep(2000);
                    String shareUrl = (String) page.evaluate("navigator.clipboard.readText()");
                    shareUrlRef.set(shareUrl);
                } catch (Exception e) {
                    UserLogUtil.sendExceptionLog(userId, "智能评分复制", "startDBScore", e, url + "/saveLogInfo");
                }
            });

            Thread.sleep(1000);
            String shareUrl = shareUrlRef.get();
            String sharImgUrl = "";
            if (isRight && page.locator("//*[@id=\"root\"]/div[1]/div/div[3]/aside/div[2]/div/div[1]/div/div[1]/div[3]/div/div/div/div[3]").count() > 0) {
                page.locator("//*[@id=\"root\"]/div[1]/div/div[3]/aside/div[2]/div/div[1]/div/div[1]/div[3]/div/div/div/div[3]").click();
                sharImgUrl = ScreenshotUtil.downloadAndUploadFile(page, uploadUrl, () -> {
                    page.getByTestId("popover_select_option_item").nth(1).click();
                });
            } else {
                page.locator("button[data-testid='message_action_share']").last().click();
                Thread.sleep(2000);
                Locator shareLocator = page.locator("(//span[contains(@class,'semi-button-content')][contains(text(),'分享图片')])[1]");
                shareLocator.click();
                Thread.sleep(5000);
                sharImgUrl = ScreenshotUtil.downloadAndUploadFile(page, uploadUrl, () -> {
                    page.locator("button:has-text(\"下载图片\")").click();
                });
            }

            logInfo.sendTaskLog("执行完成", userId, "智能评分");
            logInfo.sendResData(copiedText, userId, "智能评分", "RETURN_WKPF_RES", shareUrl, sharImgUrl);

            //保存数据库
            userInfoRequest.setDraftContent(copiedText);
            userInfoRequest.setAiName("智能评分");
            userInfoRequest.setShareUrl(shareUrl);
            userInfoRequest.setShareImgUrl(sharImgUrl);
            RestUtils.post(url + "/saveDraftContent", userInfoRequest);
            return McpResult.success(copiedText, shareUrl);
        } catch (Exception e) {
            throw e;
        }
    }

    /**
     * 处理豆包的常规请求
     *
     * @param userInfoRequest 包含会话ID和用户指令
     * @return AI生成的文本内容
     */
    @Operation(summary = "启动豆包AI生成", description = "调用豆包AI平台生成内容并抓取结果")
    @ApiResponse(responseCode = "200", description = "处理成功", content = @Content(mediaType = "application/json"))
    @PostMapping("/startDB")
    public McpResult startDB(@RequestBody UserInfoRequest userInfoRequest) throws IOException, InterruptedException {
        try (BrowserContext context = browserUtil.createPersistentBrowserContext(false, userInfoRequest.getUserId(), "db")) {

            // 初始化变量
            String userId = userInfoRequest.getUserId();
            String dbchatId = userInfoRequest.getDbChatId();
            logInfo.sendTaskLog("豆包准备就绪，正在打开页面", userId, "豆包");
            String roles = userInfoRequest.getRoles();
            String userPrompt = userInfoRequest.getUserPrompt();

            // 初始化页面并导航到指定会话
            Page page = browserUtil.getOrCreatePage(context);
            if (dbchatId != null) {
                page.navigate("https://www.doubao.com/chat/" + dbchatId);
            } else {
                page.navigate("https://www.doubao.com/chat/");
            }

            page.waitForLoadState(LoadState.LOAD);
            Thread.sleep(500);
            logInfo.sendTaskLog("豆包页面打开完成", userId, "豆包");
            // 定位深度思考按钮
            Locator deepThoughtButton = page.locator("button.semi-button:has-text('深度思考')");
            // 检查按钮是否包含以 active- 开头的类名
            Boolean isActive = (Boolean) deepThoughtButton.evaluate("element => {\n" +
                    "    const classList = Array.from(element.classList);\n" +
                    "    return classList.some(cls => cls.startsWith('active-'));\n" +
                    "}");

            // 确保 isActive 不为 null
            if (isActive != null && !isActive && roles.contains("db-sdsk")) {
                deepThoughtButton.click();
                // 点击后等待一段时间，确保按钮状态更新
                Thread.sleep(1000);

                // 再次检查按钮状态
                isActive = (Boolean) deepThoughtButton.evaluate("element => {\n" +
                        "    const classList = Array.from(element.classList);\n" +
                        "    return classList.some(cls => cls.startsWith('active-'));\n" +
                        "}");
                if (isActive != null && !isActive) {
                    deepThoughtButton.click();
                    Thread.sleep(1000);
                }
                logInfo.sendTaskLog("已启动深度思考模式", userId, "豆包");
            }
            Thread.sleep(1000);
            page.locator("[data-testid='chat_input_input']").click();
            Thread.sleep(1000);
            page.locator("[data-testid='chat_input_input']").fill(userPrompt);
            logInfo.sendTaskLog("用户指令已自动输入完成", userId, "豆包");
            Thread.sleep(1000);
            page.locator("[data-testid='chat_input_input']").press("Enter");
            logInfo.sendTaskLog("指令已自动发送成功", userId, "豆包");

            // 创建定时截图线程
            AtomicInteger i = new AtomicInteger(0);
            ScheduledExecutorService screenshotExecutor = Executors.newSingleThreadScheduledExecutor();
            // 🔥 优化：启动定时任务，增加页面状态检查和错误处理
            ScheduledFuture<?> screenshotFuture = screenshotExecutor.scheduleAtFixedRate(() -> {
                try {
                    // 检查页面是否已关闭
                    if (page.isClosed()) {
                        return;
                    }

                    int currentCount = i.getAndIncrement();
                    logInfo.sendImgData(page, userId + "豆包执行过程截图" + currentCount, userId);
                } catch (com.microsoft.playwright.impl.TargetClosedError e) {
                } catch (PlaywrightException e) {
                } catch (Exception e) {
                    // 只记录严重错误到日志系统
                    if (e.getMessage() != null && !e.getMessage().toLowerCase().contains("timeout")) {
                        UserLogUtil.sendExceptionLog(userId, "豆包截图", "startDB", e, url + "/saveLogInfo");
                    }
                }
            }, 1000, 6000, TimeUnit.MILLISECONDS); // 🔥 优化：延迟1秒开始，每6秒执行一次

            logInfo.sendTaskLog("开启自动监听任务，持续监听豆包回答中", userId, "豆包");
            // 等待复制按钮出现并点击
//            String copiedText =  douBaoUtil.waitAndClickDBCopyButton(page,userId,roles);
            //等待html片段获取完成
            String copiedText = douBaoUtil.waitDBHtmlDom(page, userId, "豆包");
            //关闭截图
            screenshotFuture.cancel(false);
            screenshotExecutor.shutdown();

            boolean isRight;

            Locator chatHis = page.locator("//*[@id=\"root\"]/div[1]/div/div[3]/div/main/div/div/div[2]/div/div[1]/div/div/div[2]/div[2]/div/div/div/div/div/div/div[1]/div/div/div[2]/div[1]/div/div");
            if (chatHis.count() > 0) {
                isRight = true;
            } else {
                isRight = false;
            }

            AtomicReference<String> shareUrlRef = new AtomicReference<>();

            clipboardLockManager.runWithClipboardLock(() -> {
                try {
                    if (isRight && page.locator("//*[@id=\"root\"]/div[1]/div/div[3]/aside/div[2]/div/div[1]/div/div[1]/div[3]/div/div/div/div[4]").count() > 0) {
                        page.locator("//*[@id=\"root\"]/div[1]/div/div[3]/aside/div[2]/div/div[1]/div/div[1]/div[3]/div/div/div/div[4]").click();
                        Thread.sleep(1000);
                        page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("公开分享")).click();
                        Thread.sleep(500);
                    } else {
                        page.locator("button[data-testid='message_action_share']").last().click();
                        Thread.sleep(2000);
                        page.locator("button[data-testid='thread_share_copy_btn']").first().click();
                    }

                    // 建议适当延迟等待内容更新
                    Thread.sleep(2000);
                    String shareUrl = (String) page.evaluate("navigator.clipboard.readText()");
                    shareUrlRef.set(shareUrl);
                } catch (Exception e) {
                    UserLogUtil.sendExceptionLog(userId, "豆包复制", "startDB", e, url + "/saveLogInfo");
                }
            });

            Thread.sleep(1000);
            String shareUrl = shareUrlRef.get();
            String sharImgUrl = "";
            if (isRight && page.locator("//*[@id=\"root\"]/div[1]/div/div[3]/aside/div[2]/div/div[1]/div/div[1]/div[3]/div/div/div/div[3]").count() > 0) {
                page.locator("//*[@id=\"root\"]/div[1]/div/div[3]/aside/div[2]/div/div[1]/div/div[1]/div[3]/div/div/div/div[3]").click();
                sharImgUrl = ScreenshotUtil.downloadAndUploadFile(page, uploadUrl, () -> {
                    page.getByTestId("popover_select_option_item").nth(1).click();
                });
            } else {
                page.locator("button[data-testid='message_action_share']").last().click();
                Thread.sleep(2000);
                Locator shareLocator = page.locator("(//span[contains(@class,'semi-button-content')][contains(text(),'分享图片')])[1]");
                shareLocator.click();
                Thread.sleep(5000);
                sharImgUrl = ScreenshotUtil.downloadAndUploadFile(page, uploadUrl, () -> {
                    page.locator("button:has-text(\"下载图片\")").click();
                });
            }

            logInfo.sendTaskLog("执行完成", userId, "豆包");
            logInfo.sendChatData(page, "/chat/([^/?#]+)", userId, "RETURN_DB_CHATID", 1);
            logInfo.sendResData(copiedText, userId, "豆包", "RETURN_DB_RES", shareUrl, sharImgUrl);

            //保存数据库
            userInfoRequest.setDraftContent(copiedText);
            userInfoRequest.setAiName("豆包");
            userInfoRequest.setShareUrl(shareUrl);
            userInfoRequest.setShareImgUrl(sharImgUrl);
            RestUtils.post(url + "/saveDraftContent", userInfoRequest);
            return McpResult.success(copiedText, shareUrl);
        } catch (Exception e) {
            throw e;
        }
    }

    @Operation(summary = "投递公众号排版", description = "调用豆包对内容进行评分")
    @ApiResponse(responseCode = "200", description = "处理成功", content = @Content(mediaType = "application/json"))
    @PostMapping("/startDBOffice")
    public McpResult startYBOffice(@RequestBody UserInfoRequest userInfoRequest) throws InterruptedException {
        try {
            // 初始化变量
            String userId = userInfoRequest.getUserId();
            logInfo.sendTaskLog("智能排版准备就绪，正在打开页面", userId, "智能排版");
            String roles = userInfoRequest.getRoles();
            String userPrompt = userInfoRequest.getUserPrompt();
            //TODO 如有灵活需求，可在此做修改
//            roles = "znpb-ds,yb-deepseek-pt,yb-deepseek-sdsk,yb-deepseek-lwss,";
            McpResult mcpResult = new McpResult();
            try {
                if (roles.contains("znpb-t1")) {
                    //======================腾讯元宝T1=======================//
                    try {
                        Page hyPage = tencentUtil.getPage("T1", userId);
                        long start = System.currentTimeMillis();
                        //腾讯元宝T1  根据角色组合处理不同模式（普通/深度思考/联网）
                        logInfo.sendTaskLog("智能排版准备就绪，正在打开页面", userId, "智能排版");
                        if (roles.contains("yb-hunyuan-pt") && !roles.contains("yb-hunyuan-sdsk") && !roles.contains("yb-hunyuan-lwss")) {
                            tencentUtil.handleYBAI(hyPage, userPrompt, "yb-hunyuan-pt", userId, "智能排版", "");
                        } else if (roles.contains("yb-hunyuan-sdsk") && !roles.contains("yb-hunyuan-lwss")) {
                            //深度思考
                            tencentUtil.handleYBAI(hyPage, userPrompt, "yb-hunyuan-sdsk", userId, "智能排版", "");
                        } else if (roles.contains("yb-hunyuan-lwss") && !roles.contains("yb-hunyuan-sdsk")) {
                            //联网
                            tencentUtil.handleYBAI(hyPage, userPrompt, "yb-hunyuan-lwss-1", userId, "智能排版", "");
                        } else if (roles.contains("yb-hunyuan-lwss") && roles.contains("yb-hunyuan-sdsk")) {
                            //深度思考 + 联网
                            tencentUtil.handleYBAI(hyPage, userPrompt, "yb-hunyuan-lwss-2", userId, "智能排版", "");
                        }
                        //保存入库 腾讯元宝T1 - T1和DS独立处理，各自发送响应
                        if (roles.contains("yb-hunyuan-pt") && !roles.contains("yb-hunyuan-sdsk") && !roles.contains("yb-hunyuan-lwss")) {
                            mcpResult = tencentUtil.saveDraftData(hyPage, userInfoRequest, roles, userId);
                        } else if (roles.contains("yb-hunyuan-sdsk") && !roles.contains("yb-hunyuan-lwss")) {
                            //深度思考
                            mcpResult = tencentUtil.saveDraftData(hyPage, userInfoRequest, roles, userId);
                        } else if (roles.contains("yb-hunyuan-lwss")) {
                            //深度思考 + 联网
                            mcpResult = tencentUtil.saveDraftData(hyPage, userInfoRequest, roles, userId);
                        }
                        UserLogUtil.sendNormalLog(userId, "启动智能排版生成", "startYBOffice", start, mcpResult.getResult(), url + "/saveLogInfo");
                    } catch (Exception e) {
                        logInfo.sendTaskLog("智能排版执行异常", userId, "智能排版");
                        UserLogUtil.sendExceptionLog(userId, "智能排版执行异常", "startYBOffice", e, url + "/saveLogInfo");
                    }
                } else {

                    //======================腾讯元宝DS=======================//
                    try {
                        Page dsPage = tencentUtil.getPage("DS", userId);
                        Long start = System.currentTimeMillis();
                        logInfo.sendTaskLog("智能排版准备就绪，正在打开页面", userId, "智能排版");
                        Thread.sleep(3000);
                        //腾讯元宝DS  根据角色组合处理不同模式（普通/深度思考/联网）
                        if (roles.contains("yb-deepseek-pt") && !roles.contains("yb-deepseek-sdsk") && !roles.contains("yb-deepseek-lwss")) {
                            tencentUtil.handleYBAI(dsPage, userPrompt, "yb-deepseek-pt", userId, "智能排版", "");
                        } else if (roles.contains("yb-deepseek-sdsk") && !roles.contains("yb-deepseek-lwss")) {
                            //深度思考
                            tencentUtil.handleYBAI(dsPage, userPrompt, "yb-deepseek-sdsk", userId, "智能排版", "");
                        } else if (roles.contains("yb-deepseek-lwss") && !roles.contains("yb-deepseek-sdsk")) {
                            //深度思考 + 联网
                            tencentUtil.handleYBAI(dsPage, userPrompt, "yb-deepseek-lwss-1", userId, "智能排版", "");
                        } else if (roles.contains("yb-deepseek-lwss") && roles.contains("yb-deepseek-sdsk")) {
                            //深度思考 + 联网
                            tencentUtil.handleYBAI(dsPage, userPrompt, "yb-deepseek-lwss-2", userId, "智能排版", "");
                        }
                        //保存入库 腾讯元宝DS - DS独立处理，发送自己的响应
                        if (roles.contains("yb-deepseek-pt") && !roles.contains("yb-deepseek-sdsk") && !roles.contains("yb-deepseek-lwss")) {
                            mcpResult = tencentUtil.saveDraftData(dsPage, userInfoRequest, roles, userId);
                        } else if (roles.contains("yb-deepseek-sdsk") && !roles.contains("yb-deepseek-lwss")) {
                            mcpResult = tencentUtil.saveDraftData(dsPage, userInfoRequest, roles, userId);
                        } else if (roles.contains("yb-deepseek-lwss")) {
                            //深度思考 + 联网
                            mcpResult = tencentUtil.saveDraftData(dsPage, userInfoRequest, roles, userId);
                        }
                        UserLogUtil.sendNormalLog(userId, "启动智能排版生成", "startYBOffice", start, mcpResult.getResult(), url + "/saveLogInfo");
                    } catch (Exception e) {
                        logInfo.sendTaskLog("智能排版执行异常", userId, "智能排版");
                        UserLogUtil.sendExceptionLog(userId, "智能排版执行异常", "startYBOffice", e, url + "/saveLogInfo");
                    }
                    return mcpResult;
                }
            } catch (Exception e) {
                throw e;
            }
            return McpResult.fail("未获取到内容", "");
        } catch (Exception e) {
            throw e;
        }
    }

    /**
     * 处理百度AI的常规请求
     *
     * @param userInfoRequest 包含会话ID和用户指令
     * @return AI生成的文本内容
     */
    @Operation(summary = "启动百度AI生成", description = "调用百度AI平台生成内容并抓取结果")
    @ApiResponse(responseCode = "200", description = "处理成功", content = @Content(mediaType = "application/json"))
    @PostMapping("/startBaidu")
    public McpResult startBaidu(@RequestBody UserInfoRequest userInfoRequest) throws InterruptedException {
        try (BrowserContext context = browserUtil.createPersistentBrowserContext(false,
                userInfoRequest.getUserId(), "baidu")) {

            // 初始化变量
            String userId = userInfoRequest.getUserId();
            String roles = userInfoRequest.getRoles();
            String userPrompt = userInfoRequest.getUserPrompt();
            String chatId = userInfoRequest.getBaiduChatId();
            String isNewChat = userInfoRequest.getIsNewChat();

            logInfo.sendTaskLog("百度AI准备就绪，正在打开页面", userId, "百度AI");

            // 如果指定了新会话，则忽略已有的会话ID
            if ("true".equalsIgnoreCase(isNewChat)) {
                logInfo.sendTaskLog("用户请求新会话，将忽略已有会话ID", userId, "百度AI");
                chatId = null;
            } else if (chatId != null && !chatId.isEmpty()) {
                logInfo.sendTaskLog("检测到会话ID: " + chatId + "，将继续使用此会话", userId, "百度AI");
            } else {
                logInfo.sendTaskLog("未检测到会话ID，将创建新会话", userId, "百度AI");
            }

            // 创建页面
            Page page = browserUtil.getOrCreatePage(context);
            page.setDefaultTimeout(60000); // 60秒超时

            // 创建定时截图线程
            AtomicInteger i = new AtomicInteger(0);
            ScheduledExecutorService screenshotExecutor = Executors.newSingleThreadScheduledExecutor();
            ScheduledFuture<?> screenshotFuture = screenshotExecutor.scheduleAtFixedRate(() -> {
                try {
                    if (page.isClosed()) {
                        return;
                    }
                    int currentCount = i.getAndIncrement();
                    logInfo.sendImgData(page, userId + "百度AI执行过程截图" + currentCount, userId);
                } catch (Exception e) {
                    UserLogUtil.sendExceptionLog(userId, "百度AI截图", "startBaidu", e, url + "/saveLogInfo");
                }
            }, 2000, 8000, TimeUnit.MILLISECONDS); // 延迟2秒开始，每8秒执行一次

            logInfo.sendTaskLog("开启自动监听任务，持续监听百度AI回答中", userId, "百度AI");

            // 处理百度AI交互
            String copiedText = "";
            int maxRetries = 3;

            // 重试循环
            for (int retry = 0; retry < maxRetries; retry++) {
                try {
                    if (retry > 0) {
                        logInfo.sendTaskLog("第" + (retry + 1) + "次尝试", userId, "百度AI");
                        Thread.sleep(3000);
                    }

                    copiedText = baiduUtil.handleBaiduAI(page, userPrompt, userId, roles, chatId);

                    if (!copiedText.startsWith("获取内容失败") && !copiedText.isEmpty()) {
                        break; // 成功获取内容，跳出重试循环
                    }

                    Thread.sleep(3000); // 等待3秒后重试
                } catch (Exception e) {
                    if (retry == maxRetries - 1) {
                        copiedText = "获取内容失败：多次尝试后仍然失败";
                        logInfo.sendTaskLog("百度AI处理失败", userId, "百度AI");
                        UserLogUtil.sendExceptionLog(userId, "百度AI处理", "startBaidu", e, url + "/saveLogInfo");

                    }
                    Thread.sleep(2000);
                }
            }

            // 安全地关闭截图任务
            try {
                screenshotFuture.cancel(true);
                screenshotExecutor.shutdownNow();
                if (!screenshotExecutor.awaitTermination(3, TimeUnit.SECONDS)) {
                    logInfo.sendTaskLog("截图任务关闭超时", userId, "百度AI");
                }
            } catch (Exception e) {
                logInfo.sendTaskLog("关闭截图任务异常", userId, "百度AI");
                UserLogUtil.sendExceptionLog(userId, "百度AI截图关闭", "startBaidu", e, url + "/saveLogInfo");
            }

            // 如果获取内容失败，尝试备用方法
//            if (copiedText.startsWith("获取内容失败") || copiedText.isEmpty()) {
//                try {
//                    logInfo.sendTaskLog("尝试使用备用方法提取内容", userId, "百度AI");
//
//                    Object extractedContent = page.evaluate("""
//                                () => {
//                                    const contentElements = document.querySelectorAll('div, p, span');
//                                    let longestText = '';
//
//                                    for (let element of contentElements) {
//                                        const text = element.innerHTML;
//                                        if (text && text.length > longestText.length && text.length > 100) {
//                                            longestText = text;
//                                        }
//                                    }
//
//                                    return longestText || '无法提取内容';
//                                }
//                            """);
//
//                    if (extractedContent != null && !extractedContent.toString().isEmpty() &&
//                            !extractedContent.toString().equals("无法提取内容")) {
//                        copiedText = extractedContent.toString();
//                        logInfo.sendTaskLog("使用备用方法成功提取内容", userId, "百度AI");
//                    }
//                } catch (Exception e) {
//                    logInfo.sendTaskLog("备用提取方法失败", userId, "百度AI");
//                    UserLogUtil.sendExceptionLog(userId, "百度AI备用提取", "startBaidu", e, url + "/saveLogInfo");
//                }
//            }
            // 保存结果
            McpResult mcpResult = new McpResult();
            try {
                mcpResult = baiduUtil.saveBaiduContent(page, userInfoRequest, roles, userId, copiedText);
                logInfo.sendTaskLog("执行完成", userId, "百度AI");
            } catch (Exception e) {
                logInfo.sendTaskLog("保存百度AI内容到稿库失败", userId, "百度AI");
                UserLogUtil.sendExceptionLog(userId, "保存百度AI内容到稿库", "startBaidu", e, url + "/saveLogInfo");

                // 即使保存失败，也要发送结果数据
                try {
                    String errorContent = copiedText != null && !copiedText.isEmpty() ? copiedText : "获取内容失败：" + e.getMessage();
                    logInfo.sendResData(errorContent, userId, "百度AI", "RETURN_BAIDU_RES", "", "");
                } catch (Exception sendError) {
                }
                return McpResult.fail("获取内容失败", "");
            }
            return mcpResult;
        } catch (Exception e) {
            logInfo.sendTaskLog("百度AI执行异常", userInfoRequest.getUserId(), "百度AI");
            throw e;
        }
    }

    /**
     * 处理DeepSeek的常规请求
     *
     * @param userInfoRequest 包含会话ID和用户指令
     * @return AI生成的文本内容
     */
    @Operation(summary = "启动DeepSeek AI生成", description = "调用DeepSeek AI平台生成内容并抓取结果")
    @ApiResponse(responseCode = "200", description = "处理成功", content = @Content(mediaType = "application/json"))
    @PostMapping("/startDS")
    public McpResult startDS(@RequestBody UserInfoRequest userInfoRequest) throws InterruptedException, IOException {

        String userId = userInfoRequest.getUserId();
        String chatId = userInfoRequest.getDeepseekChatId();
        String userPrompt = userInfoRequest.getUserPrompt();
        String isNewChat = userInfoRequest.getIsNewChat();
        String roles = userInfoRequest.getRoles();


        try (BrowserContext context = browserUtil.createPersistentBrowserContext(false, userId, "deepseek")) {
            if ("true".equalsIgnoreCase(isNewChat)) {
                chatId = null;
            } else if (chatId != null && !chatId.isEmpty()) {
                logInfo.sendTaskLog("检测到会话ID: " + chatId + "，将继续使用此会话", userId, "DeepSeek");
            }

            // 初始化页面并发送消息
            Page page = browserUtil.getOrCreatePage(context);

            // 🔥 优化：设置更合理的超时时间，提高响应速度
            page.setDefaultTimeout(90000); // 90秒（增加到90秒以减少超时错误）

            // 创建定时截图线程
            AtomicInteger i = new AtomicInteger(0);
            ScheduledExecutorService screenshotExecutor = Executors.newSingleThreadScheduledExecutor();

            // 启动定时任务，每6秒执行一次截图，添加错误处理和状态检查
            ScheduledFuture<?> screenshotFuture = screenshotExecutor.scheduleAtFixedRate(() -> {
                try {
                    // 检查页面是否已关闭，避免对已关闭页面进行操作
                    if (page.isClosed()) {
                        return;
                    }

                    // 🔥 优化：移除页面加载检查，减少不必要的延迟
                    int currentCount = i.getAndIncrement();
                    try {
                        // 使用更安全的截图方式
                        logInfo.sendImgData(page, userId + "DeepSeek执行过程截图" + currentCount, userId);
                    } catch (Exception e) {
                        UserLogUtil.sendExceptionLog(userId, "DeepSeek执行过程截图", "startDeepSeek", e, url + "/saveLogInfo");
                    }
                } catch (Exception e) {
                    UserLogUtil.sendExceptionLog(userId, "DeepSeek执行过程截图", "startDeepSeek", e, url + "/saveLogInfo");
                }
            }, 1000, 4000, TimeUnit.MILLISECONDS); // 🔥 优化：延迟1秒开始，每4秒执行一次（提高截图频率）

            logInfo.sendTaskLog("开启自动监听任务，持续监听DeepSeek回答中", userId, "DeepSeek");

            // 发送消息并获取回答
            String copiedText = "";
            int maxRetries = 3;

            // 重试循环
            for (int retry = 0; retry < maxRetries; retry++) {
                try {
                    if (retry > 0) {
                        // 刷新页面重新开始
                        page.reload();
                        page.waitForLoadState(LoadState.LOAD);
                        Thread.sleep(2000);
                    }

                    // 🔥 新增：检测DeepSeek服务器不可用弹窗
                    try {
                        // 检查是否有服务器不可用的弹窗或错误信息
                        String serverUnavailableCheck = (String) page.evaluate("""
                                    () => {
                                        // 检查常见的服务器不可用提示
                                        const errorMessages = [
                                            '服务器暂时不可用',
                                            '服务暂时不可用', 
                                            'Service temporarily unavailable',
                                            'Server temporarily unavailable',
                                            '系统繁忙',
                                            '服务异常',
                                            '网络异常'
                                        ];
                                        
                                        // 检查页面中是否包含这些错误信息
                                        const bodyText = document.body.innerText || document.body.textContent || '';
                                        for (const message of errorMessages) {
                                            if (bodyText.includes(message)) {
                                                return message;
                                            }
                                        }
                                        
                                        // 检查弹窗或模态框
                                        const modals = document.querySelectorAll('.modal, .dialog, .popup, .alert, [role="dialog"], [role="alert"]');
                                        for (const modal of modals) {
                                            const modalText = modal.innerText || modal.textContent || '';
                                            for (const message of errorMessages) {
                                                if (modalText.includes(message)) {
                                                    return message;
                                                }
                                            }
                                        }
                                        
                                        return null;
                                    }
                                """);

                        if (serverUnavailableCheck != null && !serverUnavailableCheck.equals("null")) {

                            // 安全地关闭截图任务
                            try {
                                screenshotFuture.cancel(true);
                                screenshotExecutor.shutdownNow();
                            } catch (Exception e) {
                            }

                            // 直接返回错误信息给前端
                            String errorMessage = "DeepSeek服务器暂时不可用，请稍后再试";
                            logInfo.sendTaskLog(errorMessage, userId, "DeepSeek");
                            logInfo.sendResData(errorMessage, userId, "DeepSeek", "RETURN_DEEPSEEK_RES", "", "");

                            // 保存错误信息到数据库
                            userInfoRequest.setDraftContent(errorMessage);
                            userInfoRequest.setAiName("DeepSeek");
                            userInfoRequest.setShareUrl("");
                            userInfoRequest.setShareImgUrl("");
                            RestUtils.post(url + "/saveDraftContent", userInfoRequest);

                            return McpResult.fail(errorMessage, "");
                        }
                    } catch (Exception e) {
                        return McpResult.fail("无法访问DeepSeek服务器", "");
                    }

                    copiedText = deepSeekUtil.handleDeepSeekAI(page, userPrompt, userId, roles, chatId);

                    if (!copiedText.startsWith("获取内容失败") && !copiedText.isEmpty()) {
                        break; // 成功获取内容，跳出重试循环
                    }

                    Thread.sleep(3000); // 等待3秒后重试
                } catch (Exception e) {
                    if (retry == maxRetries - 1) {
                        copiedText = "获取内容失败：多次尝试后仍然失败";
                        // 不发送技术错误到前端，只记录日志
                    }
                    Thread.sleep(2000); // 出错后等待2秒
                }
            }

            // 安全地关闭截图任务
            try {
                screenshotFuture.cancel(true); // 使用true尝试中断正在执行的任务
                screenshotExecutor.shutdownNow(); // 立即关闭执行器

                // 等待执行器完全关闭，但最多等待3秒
                if (!screenshotExecutor.awaitTermination(3, TimeUnit.SECONDS)) {
                    // 截图任务未能完全关闭
                }
            } catch (Exception e) {
                // 关闭截图任务时出错，不发送到前端
            }

            // 如果获取内容失败，尝试从页面中提取任何可能的内容
            if (copiedText.startsWith("获取内容失败") || copiedText.isEmpty()) {
                try {

                    // 使用JavaScript提取页面上的任何文本内容
                    Object extractedContent = page.evaluate("""
                                () => {
                                    // 尝试查找任何可能包含回复的元素
                                    const contentElements = document.querySelectorAll('.ds-markdown, .flow-markdown-body, .message-content, .ds-markdown-paragraph');
                                    if (contentElements.length > 0) {
                                        // 获取最后一个元素的文本
                                        const lastElement = contentElements[contentElements.length - 1];
                                        return lastElement.innerHTML || lastElement.innerText || '';
                                    }

                                    // 如果找不到特定元素，尝试获取页面上的任何文本
                                    const bodyText = document.body.innerText;
                                    if (bodyText && bodyText.length > 50) {
                                        return bodyText;
                                    }

                                    return '无法提取内容';
                                }
                            """);

                    if (extractedContent != null && !extractedContent.toString().isEmpty() &&
                            !extractedContent.toString().equals("无法提取内容")) {
                        copiedText = extractedContent.toString();
                    }
                } catch (Exception e) {
                    return McpResult.fail("无法提取返回内容", "");
                }
            }

            // 🔥 优化：获取分享链接，增加超时保护
            String shareUrl = "";
            try {
                // 设置较短的超时时间用于分享操作
                page.locator("button:has-text('分享')").click(new Locator.ClickOptions().setTimeout(30000));
                Thread.sleep(1500); // 稍微增加等待时间
                shareUrl = (String) page.evaluate("navigator.clipboard.readText()");
                if (shareUrl != null && !shareUrl.trim().isEmpty()) {
                } else {
                    shareUrl = page.url();
                }
            } catch (Exception e) {
                // 使用当前页面URL作为备选
                try {
                    shareUrl = page.url();
                } catch (Exception ex) {
                    shareUrl = "";
                }
            }

            String shareImgUrl = "";
            try {
                // 使用新的分条截图方法
                MessageScreenshot screenshotter = new MessageScreenshot();
                shareImgUrl = screenshotter.captureMessagesAsLongScreenshot(page, uploadUrl, userId);
            } catch (Exception e) {
                logInfo.sendTaskLog("DeepSeek导出图片失败: " + e.getMessage(), userId, "DeepSeek");
                shareImgUrl = "";
            }


            logInfo.sendTaskLog("执行完成", userId, "DeepSeek");
            logInfo.sendChatData(page, "/chat/s/([^/?#]+)", userId, "RETURN_DEEPSEEK_CHATID", 1);

            logInfo.sendResData(copiedText, userId, "DeepSeek", "RETURN_DEEPSEEK_RES", shareUrl, shareImgUrl);

            // 保存数据库
            userInfoRequest.setDraftContent(shareImgUrl);
            userInfoRequest.setAiName("DeepSeek");
            userInfoRequest.setShareUrl(shareUrl);
            userInfoRequest.setShareImgUrl(shareImgUrl);
            RestUtils.post(url + "/saveDraftContent", userInfoRequest);


            return McpResult.success(copiedText, shareImgUrl);

        } catch (Exception e) {

            // 发送用户友好的错误信息，不暴露技术细节
            String userFriendlyError = "DeepSeek处理出现问题，请稍后重试";
            logInfo.sendTaskLog(userFriendlyError, userId, "DeepSeek");
            logInfo.sendResData(userFriendlyError, userId, "DeepSeek", "RETURN_DEEPSEEK_RES", "", "");

            return McpResult.fail(userFriendlyError, "");
        }
    }


    @Operation(summary = "启动豆包AI生成图片", description = "调用豆包AI平台生成内容并抓取结果")
    @ApiResponse(responseCode = "200", description = "处理成功", content = @Content(mediaType = "application/json"))
    @PostMapping("/startDBImg")
    public McpResult startDBImg(@RequestBody UserInfoRequest userInfoRequest) throws Exception {
        try (BrowserContext context = browserUtil.createPersistentBrowserContext(false, userInfoRequest.getUserId(), "db")) {

            // 初始化变量
            String userId = userInfoRequest.getUserId();
            String roles = userInfoRequest.getRoles();
            String userPrompt = userInfoRequest.getUserPrompt();

            // 初始化页面并导航到指定会话
            Page page = browserUtil.getOrCreatePage(context);
            page.navigate("https://www.doubao.com/chat/");

            page.waitForLoadState(LoadState.LOAD);
            Thread.sleep(500);
            logInfo.sendTaskLog("豆包页面打开完成", userId, "豆包");
            // 定位深度思考按钮
            Locator deepThoughtButton = page.locator("button.semi-button:has-text('深度思考')");
            // 检查按钮是否包含以 active- 开头的类名
            Boolean isActive = (Boolean) deepThoughtButton.evaluate("element => {\n" +
                    "    const classList = Array.from(element.classList);\n" +
                    "    return classList.some(cls => cls.startsWith('active-'));\n" +
                    "}");

            // 确保 isActive 不为 null
            if (isActive != null && !isActive && roles.contains("db-sdsk")) {
                deepThoughtButton.click();
                // 点击后等待一段时间，确保按钮状态更新
                Thread.sleep(1000);

                // 再次检查按钮状态
                isActive = (Boolean) deepThoughtButton.evaluate("element => {\n" +
                        "    const classList = Array.from(element.classList);\n" +
                        "    return classList.some(cls => cls.startsWith('active-'));\n" +
                        "}");
                if (isActive != null && !isActive) {
                    deepThoughtButton.click();
                    Thread.sleep(1000);
                }
                logInfo.sendTaskLog("已启动深度思考模式", userId, "豆包");
            }
            Thread.sleep(1000);
            page.locator("[data-testid='chat_input_input']").click();
            Thread.sleep(1000);
            page.locator("[data-testid='chat_input_input']").fill(userPrompt);
            logInfo.sendTaskLog("用户指令已自动输入完成", userId, "豆包");
            Thread.sleep(1000);
            page.locator("[data-testid='chat_input_input']").press("Enter");
            logInfo.sendTaskLog("指令已自动发送成功", userId, "豆包");
//            等待豆包图片生成完毕,默认等待30s
            Thread.sleep(30 * 1000);
//            下载图片
            try {
                Download download = page.waitForDownload(() -> {
                    try {
                        Locator first = page.locator("(//span[contains(@class,'h-24 text-sm leading-24 font-medium text-s-color-text-tertiary')][contains(text(),'下载')])[1]");
                        if (first.isVisible()) {
                            first.click();
                            Thread.sleep(1000);
                            Locator locator = page.locator("(//span[contains(@class,'semi-button-content')][contains(text(),'下载')])[1]");
                            if (locator.isVisible()) {
                                locator.click();
                            }
                        }
                    } catch (Exception e) {
                    }
                });
                Thread.sleep(8000);
                InputStream inputStream = download.createReadStream();
                McpResult mcpResult = cubeMcp.uploadMaterialByStream("image", inputStream, userInfoRequest.getUnionId(), userInfoRequest.getImageDescription());
                if(mcpResult == null) {
                    return McpResult.fail("图片生成失败", "");
                }
                return mcpResult;
            } catch (Exception e) {
            }
        } catch (Exception e) {
            throw e;
        }
        return McpResult.fail("图片生成失败", "");
    }
}
