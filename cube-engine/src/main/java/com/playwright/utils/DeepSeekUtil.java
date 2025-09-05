package com.playwright.utils;

import com.alibaba.fastjson.JSONObject;
import com.microsoft.playwright.ElementHandle;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.LoadState;
import com.microsoft.playwright.options.WaitUntilState;
import com.playwright.entity.UserInfoRequest;
import com.playwright.websocket.WebSocketClientService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;


import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeoutException;

import static com.playwright.utils.ScreenshotUtil.uploadFile;

/**
 * DeepSeek AI平台工具类
 * @author 优立方
 * @version JDK 17
 * &#064;date  2025年06月15日 10:33
 */
@Component
public class DeepSeekUtil {

    @Autowired
    private LogMsgUtil logInfo;
    
    @Autowired
    private WebSocketClientService webSocketClientService;
    
    @Autowired
    private ClipboardLockManager clipboardLockManager;
    
    @Value("${cube.url}")
    private String url;
    
    @Autowired
    private ScreenshotUtil screenshotUtil;

    /**
     * 检查DeepSeek登录状态
     * @param page Playwright页面对象
     * @param navigate 是否需要先导航到DeepSeek页面
     * @return 登录状态，如果已登录则返回用户名，否则返回"false"
     */
    public String checkLoginStatus(Page page, boolean navigate) {
        try {
            if (navigate) {
                page.navigate("https://chat.deepseek.com/");
                page.waitForLoadState();
                page.waitForTimeout(1500); // 增加等待时间确保页面完全加载
            }

            // 检查是否有登录按钮，如果有则表示未登录
            try {
                Locator loginBtn = page.locator("button:has-text('登录'), button:has-text('Login')").first();
                if (loginBtn.count() > 0 && loginBtn.isVisible()) {
                    return "false";
                }
            } catch (Exception e) { //todo
                // 忽略检查错误
            }

            // 首先尝试关闭侧边栏
            try {
                // 等待侧边栏关闭按钮出现并点击
                ElementHandle closeButton = page.waitForSelector(
                        "div[class*='_4f3769f']",
                        new Page.WaitForSelectorOptions().setTimeout(5000));

                if (closeButton != null) {
                    closeButton.click(new ElementHandle.ClickOptions().setTimeout(30000));

                    // 等待一下确保侧边栏关闭动画完成
                    page.waitForTimeout(800);
                }
            } catch (Exception e) {
//                logInfo.sendTaskLog("关闭侧边栏失败或按钮不存在: " + e.getMessage(), userId, "DeepSeek");
            }

            // 特别针对用户昵称"Obvious"的检测
            try {
                // 点击头像显示下拉菜单
                Locator avatarLocator = page.locator("img.fdf01f38").first();
                if (avatarLocator.count() > 0 && avatarLocator.isVisible()) {
                    avatarLocator.click();
                    page.waitForTimeout(1500); // 增加等待时间确保下拉菜单显示


                    // new 直接定位到包含用户名的元素
                    Locator userNameElement = page.locator("div._9d8da05").first();

                    if (userNameElement.count() > 0 && userNameElement.isVisible()) {
                        String name = userNameElement.textContent();
                        if (name != null && !name.trim().isEmpty() &&
                                !name.trim().equals("登录") && !name.trim().equals("Login")) {
                            // 找到用户昵称
                            return name.trim();
                        }
                    }
                    // 即使未找到昵称，也已确认已登录
                    return "已登录用户";
                }
            } catch (Exception e) {
            }

            // 最后尝试使用通用方法检测登录状态
            try {
                // 检查是否有新建聊天按钮或其他已登录状态的标志
                Locator newChatBtn = page.locator("button:has-text('新建聊天'), button:has-text('New Chat')").first();
                if (newChatBtn.count() > 0 && newChatBtn.isVisible()) {
                    return "已登录用户";
                }

                // 检查是否有聊天历史记录
                Locator chatHistory = page.locator(".conversation-list, .chat-history").first();
                if (chatHistory.count() > 0 && chatHistory.isVisible()) {
                    return "已登录用户";
                }
            } catch (Exception e) {
                // 忽略检查错误
            }

            // 默认返回未登录状态
            return "false";
        } catch (Exception e) {
            throw e;
        }
    }

    /**
     * 等待并获取DeepSeek二维码
     * @param page Playwright页面对象
     * @param userId 用户ID
     * @param screenshotUtil 截图工具
     * @return 二维码截图URL
     */
    public String waitAndGetQRCode(Page page, String userId, ScreenshotUtil screenshotUtil) throws IOException {
        try {
            logInfo.sendTaskLog("正在获取DeepSeek登录二维码", userId, "DeepSeek");

            // 导航到DeepSeek登录页面，启用等待直到网络空闲
            page.navigate("https://chat.deepseek.com/");
            page.waitForLoadState(LoadState.NETWORKIDLE); // 网络空闲时即认为加载完成，比默认更快

            // 直接截图当前页面（包含登录按钮）
            String url = screenshotUtil.screenshotAndUpload(page, "checkDeepSeekLogin.png");

            logInfo.sendTaskLog("DeepSeek二维码获取成功", userId, "DeepSeek");
            return url;
        } catch (Exception e) {
            throw e;
        }
    }

    /**
     * 等待DeepSeek AI回答完成并提取内容
     * @param page Playwright页面实例
     * @param userId 用户ID
     * @param aiName AI名称
     * @param roles 角色信息，用于判断是否为深度思考模式
     * @return 获取的回答内容
     */
    public String waitDeepSeekResponse(Page page, String userId, String aiName, String roles) {
        try {
            // 等待页面内容稳定
            String currentContent = "";
            String lastContent = "";
            int stableCount = 0;

            long startTime = System.currentTimeMillis();

            // 添加初始延迟，确保页面完全加载
            page.waitForTimeout(2000);

            // 进入循环，直到内容不再变化或者超时
            while (true) {
                // 检查是否超时
                long elapsedTime = System.currentTimeMillis() - startTime;
                boolean isDeepThinkingMode = roles != null && roles.contains("ds-sdsk");
                long maxTimeout = isDeepThinkingMode ? 1200000 : 600000; // 深度思考模式20分钟，普通模式10分钟

                if (elapsedTime > maxTimeout) {
                    logInfo.sendTaskLog("超时，AI未完成回答！", userId, aiName);
                    break;
                }

                // 获取最新AI回答内容
                currentContent = getLatestAiResponse(page);

                // 如果成功获取到内容
                if (currentContent != null && !currentContent.isEmpty()) {
                    // 检查内容是否稳定
                    if (currentContent.equals(lastContent)) {
                        stableCount++;

                        // 深度思考模式需要更长的稳定时间
                        int requiredStableCount = isDeepThinkingMode ? 2 : 1;

                        // 如果内容连续稳定达到要求次数，认为AI已完成回答
                        if (stableCount >= requiredStableCount) {
                            logInfo.sendTaskLog("DeepSeek回答完成，正在自动提取内容", userId, aiName);
                            break;
                        }
                    } else {
                        // 内容发生变化，重置稳定计数
                        stableCount = 0;
                        lastContent = currentContent;
                    }
                }

                // 等待一段时间再次检查
                page.waitForTimeout(300);
            }

            logInfo.sendTaskLog("DeepSeek内容已自动提取完成", userId, aiName);
            return currentContent;

        } catch (Exception e) {
            logInfo.sendTaskLog("等待AI回答时出错: " + e.getMessage(), userId, aiName);
            throw e;
        }
    }

    /**
     * 检查是否仍在生成内容
     */
    private boolean checkIfGenerating(Page page) {
        try {
            // 检查生成指示器  (发送按键和停止输出公用一个class 无法判断)
            Object generatingStatus = page.evaluate("""
            () => {
                // 检查停止指示器 ._7436101
                const thinkingIndicators = document.querySelectorAll(
                    '.generating-indicator, .loading-indicator, .thinking-indicator, ' +
                    '.ds-typing-container, .ds-loading-dots, .loading-container'
                );
                
                let isGenerating = false;
                for (const indicator of thinkingIndicators) {
                    if (indicator && window.getComputedStyle(indicator).display !== 'none') {
                        isGenerating = true;
                        break;
                    }
                }
                
                // 检查停止生成按钮
                if (!isGenerating) {
                    const stopButtons = document.querySelectorAll(
                        'button:contains("停止生成"), [title="停止生成"], .stop-generating-button'
                    );
                    for (const btn of stopButtons) {
                        if (btn && window.getComputedStyle(btn).display !== 'none' && 
                            window.getComputedStyle(btn).visibility !== 'hidden') {
                            isGenerating = true;
                            break;
                        }
                    }
                }
                
                return isGenerating;
            }
        """);

            return generatingStatus instanceof Boolean ? (Boolean) generatingStatus : false;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 获取AI最新的回答内容
     * @param page Playwright页面对象
     * @return 最新的AI回答内容
     */
    private String getLatestAiResponse(Page page) {
        try {
            Object jsResult = page.evaluate("""
            () => {
                try {
                    // 获取所有包含AI回答的消息
                    const markdownElements = document.querySelectorAll('.ds-markdown');
                    if (markdownElements.length === 0) {
                        return {
                            content: '',
                            source: 'no-markdown-elements',
                            timestamp: Date.now()
                        };
                    }
                    
                    // 获取最新的Markdown内容
                    const latestMarkdown = markdownElements[markdownElements.length - 1];
                    
                    // 克隆内容以避免修改原DOM
                    const contentClone = latestMarkdown.cloneNode(true);
                    
                    // 移除头像图标和其他无关元素
                    const iconsToRemove = contentClone.querySelectorAll(
                        '._7eb2358, ._58dfa60, .ds-icon, svg, ' +
                        '.avatar, .user-avatar, .ai-avatar, ' +
                        '.ds-button, button, [role="button"]'
                    );
                    iconsToRemove.forEach(icon => icon.remove());
                    
                    // 移除空的div容器
                    const emptyDivs = contentClone.querySelectorAll('div:empty');
                    emptyDivs.forEach(div => div.remove());
                    
                    return {
                        content: contentClone.innerHTML,
                        source: 'latest-markdown',
                        timestamp: Date.now()
                    };
                } catch (e) {
                    return {
                        content: '',
                        source: 'error',
                        error: e.toString(),
                        timestamp: Date.now()
                    };
                }
            }
        """);

            if (jsResult instanceof Map) {
                Map<String, Object> result = (Map<String, Object>) jsResult;
                return (String) result.getOrDefault("content", "");
            }
        } catch (Exception e) {
            System.err.println("获取AI回答时出错: " + e.getMessage());
        }

        return "";
    }


    /**
     * 发送消息到DeepSeek并等待回复
     * @param page Playwright页面实例
     * @param userPrompt 用户提示文本
     * @param userId 用户ID
     * @param roles 角色标识
     * @param chatId 会话ID，如果不为空则使用此会话继续对话
     * @return 处理完成后的结果
     */
    public String handleDeepSeekAI(Page page, String userPrompt, String userId, String roles, String chatId) throws InterruptedException {
        try {
            long startProcessTime = System.currentTimeMillis(); // 记录开始处理时间
            
            // 设置页面错误处理
            page.onPageError(error -> {
            });
            
            // 监听请求失败
            page.onRequestFailed(request -> {
            });
            
            boolean navigationSucceeded = false;
            int retries = 0;
            final int MAX_RETRIES = 3; // 增加重试次数
            
            // 如果有会话ID，则直接导航到该会话
            if (chatId != null && !chatId.isEmpty()) {
                // 这个日志保留，与豆包一致
                
                while (!navigationSucceeded && retries < MAX_RETRIES) {
                    try {
                        // 增加导航选项，提高稳定性
                        page.navigate("https://chat.deepseek.com/a/chat/s/" + chatId, 
                            new Page.NavigateOptions()
                            .setTimeout(10000) // 增加超时时间
                            .setWaitUntil(WaitUntilState.LOAD)); // 使用LOAD而不是DOMCONTENTLOADED，确保页面完全加载
                        
                        // 等待页面稳定 - 使用更可靠的方式
                        try {
                            // 首先等待页面加载完成
                            page.waitForLoadState(LoadState.DOMCONTENTLOADED, new Page.WaitForLoadStateOptions().setTimeout(15000));
                            
                            // 使用JavaScript检查页面是否已准备好，而不是依赖选择器
                            boolean pageReady = false;
                            for (int attempt = 0; attempt < 10 && !pageReady; attempt++) {
                                try {
                                    Object result = page.evaluate("() => { return document.readyState === 'complete' || document.readyState === 'interactive'; }");
                                    if (result instanceof Boolean && (Boolean) result) {
                                        pageReady = true;
                                    } else {
                                        Thread.sleep(500); // 等待500毫秒再次检查
                                    }
                                } catch (Exception evalEx) {
                                    // 忽略评估错误，继续尝试
                                    Thread.sleep(500);
                                }
                            }
                            
                            // 如果页面已准备好，尝试等待网络空闲，但不强制要求
                            if (pageReady) {
                                try {
                                    page.waitForLoadState(LoadState.NETWORKIDLE, new Page.WaitForLoadStateOptions().setTimeout(5000));
                                } catch (Exception networkEx) {
                                    // 忽略网络空闲等待错误
                                }
                            }
                        } catch (Exception e) {
                            // 忽略等待错误，继续执行
                        }
                        
                        navigationSucceeded = true;
                    } catch (Exception e) {
                        retries++;
            
                        if (retries >= MAX_RETRIES) {
                            try {
                                page.navigate("https://chat.deepseek.com/");
                                Thread.sleep(1000); // 给页面充足的加载时间
                            } catch (Exception ex) {
                            }
                        }
                        
                        // 短暂等待后重试
                        Thread.sleep(2000); // 增加等待时间
                    }
                }
            } else {
                try {
                    page.navigate("https://chat.deepseek.com/", 
                        new Page.NavigateOptions()
                        .setTimeout(10000)
                        .setWaitUntil(WaitUntilState.LOAD)); // 使用LOAD而不是DOMCONTENTLOADED
                    
                    // 等待页面稳定 - 使用更可靠的方式
                    try {
                        // 首先等待页面加载完成
                        page.waitForLoadState(LoadState.DOMCONTENTLOADED, new Page.WaitForLoadStateOptions().setTimeout(15000));
                        
                        // 使用JavaScript检查页面是否已准备好，而不是依赖选择器
                        boolean pageReady = false;
                        for (int attempt = 0; attempt < 10 && !pageReady; attempt++) {
                            try {
                                Object result = page.evaluate("() => { return document.readyState === 'complete' || document.readyState === 'interactive'; }");
                                if (result instanceof Boolean && (Boolean) result) {
                                    pageReady = true;
                                } else {
                                    Thread.sleep(500); // 等待500毫秒再次检查
                                }
                            } catch (Exception evalEx) {
                                // 忽略评估错误，继续尝试
                                Thread.sleep(500);
                            }
                        }
                        
                        // 如果页面已准备好，尝试等待网络空闲，但不强制要求
                        if (pageReady) {
                            try {
                                page.waitForLoadState(LoadState.NETWORKIDLE, new Page.WaitForLoadStateOptions().setTimeout(5000));
                            } catch (Exception networkEx) {
                                // 忽略网络空闲等待错误
                            }
                        }
                    } catch (Exception e) {
                        // 忽略等待错误，继续执行
                    }
                } catch (Exception e) {
                }
            }
            
            // 等待页面加载完成
            try {
                // 使用更可靠的等待方式，但缩短超时时间
                Thread.sleep(1000); // 给页面充足的渲染时间
                logInfo.sendTaskLog("DeepSeek页面打开完成", userId, "DeepSeek");
            } catch (Exception e) {
            }
            
            // 先处理深度思考和联网搜索按钮的状态
            boolean needDeepThink = roles.contains("ds-sdsk");
            boolean needWebSearch = roles.contains("ds-lwss");
            // 只要有一个没选中就点亮，否则如果都没选则全部关闭
            if (needDeepThink || needWebSearch) {
                if (needDeepThink) {
                    toggleButtonIfNeeded(page, userId, "深度思考", true, logInfo);
                    // 日志已在toggleButtonIfNeeded方法中发送
                } else {
                    toggleButtonIfNeeded(page, userId, "深度思考", false, logInfo);
                }
                if (needWebSearch) {
                    toggleButtonIfNeeded(page, userId, "联网搜索", true, logInfo);
                } else {
                    toggleButtonIfNeeded(page, userId, "联网搜索", false, logInfo);
                }
            } else {
                // 如果都不需要，全部关闭
                toggleButtonIfNeeded(page, userId, "深度思考", false, logInfo);
                toggleButtonIfNeeded(page, userId, "联网搜索", false, logInfo);
            }
            
            // 定位并填充输入框
            try {
                Locator inputBox = page.locator("#chat-input");
                // 优化等待逻辑：循环检测输入框可用，最多等3秒
                int inputWait = 0;
                while ((inputBox.count() == 0 || !inputBox.isVisible()) && inputWait < 30) {
                    Thread.sleep(100);
                    inputBox = page.locator("#chat-input");
                    inputWait++;
                }
                if (inputBox.count() > 0 && inputBox.isVisible()) {
                    inputBox.click();
                    // 等待输入框获得焦点（最多500ms）
                    int focusWait = 0;
                    while (!inputBox.evaluate("el => document.activeElement === el").equals(Boolean.TRUE) && focusWait < 5) {
                        Thread.sleep(100);
                        focusWait++;
                    }
                    inputBox.fill(userPrompt);
                    logInfo.sendTaskLog("用户指令已自动输入完成", userId, "DeepSeek");
                    // 立即尝试点击发送按钮，无需多余等待
                    try {
                        // 使用用户提供的特定选择器
                        String sendButtonSelector = "._7436101";
                        boolean clicked = false;
                        Locator sendButton = page.locator(sendButtonSelector);
                        // 循环检测发送按钮可用，最多等2秒
                        int sendWait = 0;
                        while ((sendButton.count() == 0 || !sendButton.isVisible()) && sendWait < 20) {
                            Thread.sleep(100);
                            sendButton = page.locator(sendButtonSelector);
                            sendWait++;
                        }
                        if (sendButton.count() > 0 && sendButton.isVisible()) {
                            try {
                                sendButton.scrollIntoViewIfNeeded();
                                sendButton.click(new Locator.ClickOptions().setForce(true).setTimeout(5000));
                                clicked = true;
                                logInfo.sendTaskLog("指令已自动发送成功", userId, "DeepSeek");
                            } catch (Exception e) {
                            }
                        } else {
                        }
                        
                        // 如果特定按钮点击失败，尝试其他选择器
                        if (!clicked) {
                            try {
                                // 尝试常见的发送按钮选择器
                                String[] alternativeSelectors = {
                                    "button.send-button", 
                                    "button[aria-label='发送']",
                                    "button[aria-label='Send']",
                                    "button.ds-button--primary",
                                    ".send-message-button"
                                };
                                
                                for (String selector : alternativeSelectors) {
                                    try {
                                        Locator altButton = page.locator(selector).first();
                                        if (altButton.count() > 0 && altButton.isVisible()) {
                                            altButton.click(new Locator.ClickOptions().setForce(true).setTimeout(3000));
                                            clicked = true;
                                            logInfo.sendTaskLog("指令已自动发送成功", userId, "DeepSeek");
                                            break;
                                        }
                                    } catch (Exception e) {
                                        // 继续尝试下一个选择器
                                    }
                                }
                            } catch (Exception e) {
                            }
                        }
                        
                        // 如果所有按钮选择器都失败，尝试使用JavaScript点击
                        if (!clicked) {
                            try {
                                Object result = page.evaluate("""
                                    () => {
                                        try {
                                            // 记录消息发送时间戳，用于后续判断回答是否为当前会话的新回答
                                            window._deepseekMessageSentTime = Date.now();
                                            console.log('设置消息发送时间戳:', window._deepseekMessageSentTime);
                                            
                                            // 尝试多种可能的按钮
                                            const selectors = [
                                                '._7436101', 
                                                'button.send-button', 
                                                'button[aria-label="发送"]',
                                                'button[aria-label="Send"]',
                                                'button.ds-button--primary',
                                                '.send-message-button'
                                            ];
                                            
                                            // 遍历所有选择器
                                            for (const selector of selectors) {
                                                const button = document.querySelector(selector);
                                                if (button && button.offsetParent !== null) {
                                                    button.click();
                                                    return { method: selector, success: true };
                                                }
                                            }
                                            
                                            // 尝试找到任何看起来像发送按钮的元素
                                            const allButtons = document.querySelectorAll('button');
                                            for (const btn of allButtons) {
                                                if (btn.innerText && (btn.innerText.includes('发送') || btn.innerText.includes('Send'))) {
                                                    btn.click();
                                                    return { method: '文本匹配', success: true };
                                                }
                                                
                                                // 检查按钮样式是否暗示它是发送按钮
                                                const style = window.getComputedStyle(btn);
                                                if (style.backgroundColor && 
                                                    (style.backgroundColor.includes('rgb(77, 107, 254)') || 
                                                     style.backgroundColor.includes('#4D6BFE'))) {
                                                    btn.click();
                                                    return { method: '样式匹配', success: true };
                                                }
                                            }
                                            
                                            // 如果仍然找不到，尝试按回车键
                                            const inputElement = document.querySelector('#chat-input');
                                            if (inputElement) {
                                                const event = new KeyboardEvent('keydown', {
                                                    key: 'Enter',
                                                    code: 'Enter',
                                                    keyCode: 13,
                                                    bubbles: true
                                                });
                                                inputElement.dispatchEvent(event);
                                                return { method: 'Enter键', success: true };
                                            }
                                            
                                            return { method: '所有方法', success: false };
                                        } catch (e) {
                                            return { method: '出错', success: false, error: e.toString() };
                                        }
                                    }
                                """);
                                
                                // 最后一招：尝试按下Enter键
                                try {
                                    // 设置消息发送时间戳
                                    page.evaluate("() => { window._deepseekMessageSentTime = Date.now(); console.log('设置消息发送时间戳(Enter):', window._deepseekMessageSentTime); }");
                                    
                                    inputBox.press("Enter");
                                    logInfo.sendTaskLog("指令已自动发送成功", userId, "DeepSeek");
                                } catch (Exception e) {
                                }
                            } catch (Exception e) {
                            }
                        }

                        // 等待一段时间，确保消息已发送
                        Thread.sleep(1000); // 给予充足的时间确保消息发送
                    } catch (Exception e) {
                        return "获取内容失败：发送消息出错";
                    }
                } else {
                    return "获取内容失败：未找到输入框";
                }
            } catch (Exception e) {
                return "获取内容失败：发送消息出错";
            }
            
            // 等待回答完成并获取内容
            logInfo.sendTaskLog("开启自动监听任务，持续监听DeepSeek回答中", userId, "DeepSeek");
            String content = waitDeepSeekResponse(page, userId, "DeepSeek", roles);
            
            // 返回内容
            return content;
            
        } catch (Exception e) {
            throw e;
        }
    }

    /**
     * 处理DeepSeek内容并保存到稿库
     * 只保存AI回答的内容，不以问答形式展现
     * @param page Playwright页面实例
     * @param userInfoRequest 用户信息请求
     * @param roleType 角色类型
     * @param userId 用户ID
     * @param content 已获取的内容
     * @return 处理后的内容
     */
    public String saveDeepSeekContent(Page page, UserInfoRequest userInfoRequest, String roleType, String userId, String content) throws Exception{
        try {
            long startTime = System.currentTimeMillis(); // 记录开始时间
            // 1. 从URL提取会话ID和分享链接
            String shareUrl = "";
            String chatId = "";
            try {
                String currentUrl = page.url();
                java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("/chat/s/([^/]+)");
                java.util.regex.Matcher matcher = pattern.matcher(currentUrl);
                if (matcher.find()) {
                    chatId = matcher.group(1);
                    shareUrl = "https://chat.deepseek.com/a/chat/s/" + chatId;
                    userInfoRequest.setYbDsChatId(chatId);
                    JSONObject chatData = new JSONObject();
                    chatData.put("type", "RETURN_YBDS_CHATID");
                    chatData.put("chatId", chatId);
                    chatData.put("userId", userId);
                    webSocketClientService.sendMessage(chatData.toJSONString());
                }
            } catch (Exception e) {
                // 忽略错误
            }
            // 2. 只保留AI内容，不加对话包装
            String cleanedContent = cleanDeepSeekContent(content, userId);
            String displayContent = cleanedContent;
            if (cleanedContent == null || cleanedContent.trim().isEmpty()) {
                displayContent = content;
            }
            // 3. 设置AI名称
            String aiName = "DeepSeek";
            if (roleType != null) {
                boolean hasDeepThinking = roleType.contains("ds-sdsk");
                boolean hasWebSearch = roleType.contains("ds-lwss");
                if (hasDeepThinking && hasWebSearch) {
                    aiName = "DeepSeek-思考联网";
                } else if (hasDeepThinking) {
                    aiName = "DeepSeek-深度思考";
                } else if (hasWebSearch) {
                    aiName = "DeepSeek-联网搜索";
                }
            }
            // 4. 发送内容到前端
            logInfo.sendResData(displayContent, userId, "DeepSeek", "RETURN_DEEPSEEK_RES", shareUrl, null);
            // 5. 保存内容到稿库
            userInfoRequest.setDraftContent(displayContent);
            userInfoRequest.setAiName(aiName);
            userInfoRequest.setShareUrl(shareUrl);
            userInfoRequest.setShareImgUrl(null);
            Object response = RestUtils.post(url + "/saveDraftContent", userInfoRequest);
            logInfo.sendTaskLog("执行完成", userId, "DeepSeek");
            return displayContent;
        } catch (Exception e) {
            logInfo.sendTaskLog("DeepSeek内容保存过程发生异常", userId, "DeepSeek");
            throw e;
        }
    }


    /**
     * 通用方法：根据目标激活状态切换按钮（深度思考/联网搜索）
     * @param page Playwright页面
     * @param userId 用户ID
     * @param buttonText 按钮文本（如"深度思考"、"联网搜索"）
     * @param shouldActive 期望激活(true)还是关闭(false)
     * @param logInfo 日志工具
     */
    private void toggleButtonIfNeeded(Page page, String userId, String buttonText, boolean shouldActive, LogMsgUtil logInfo) {
        try {
            // 使用更简单的选择器
            String buttonSelector = String.format("button:has-text('%s'), div[role='button']:has-text('%s')", buttonText, buttonText);

            // 增加超时时间并等待按钮可交互
            Locator button = page.locator(buttonSelector).first();
            button.waitFor(new Locator.WaitForOptions().setTimeout(10000)); // 增加到10秒

            if (!button.isVisible()) {
                logInfo.sendTaskLog(buttonText + "按钮不可见", userId, "DeepSeek");
                return;
            }

            // 获取按钮的完整类名
            String currentClasses = (String) button.evaluate("el => el.className");

            // 检查当前状态：是否包含 _76f196b 类
            boolean isCurrentlyActive = currentClasses.contains("_76f196b");

            // 只在状态不符时点击
            if (isCurrentlyActive != shouldActive) {
                // 使用Playwright的自动等待机制点击:cite[4]
                button.click(new Locator.ClickOptions().setTimeout(5000));

                // 等待状态变化
                boolean stateChanged = false;
                for (int i = 0; i < 15; i++) { // 增加重试次数和超时
                    page.waitForTimeout(200);

                    String newClasses = (String) button.evaluate("el => el.className");
                    boolean isNowActive = newClasses.contains("_76f196b");

                    if (isNowActive == shouldActive) {
                        stateChanged = true;
                        break;
                    }
                }

                if (stateChanged) {
                    logInfo.sendTaskLog((shouldActive ? "已启动" : "已关闭") + buttonText + "模式", userId, "DeepSeek");
                } else {
                    logInfo.sendTaskLog(buttonText + "模式切换失败", userId, "DeepSeek");
                }
            } else {
                logInfo.sendTaskLog(buttonText + "模式已经是" + (shouldActive ? "开启" : "关闭") + "状态", userId, "DeepSeek");
            }
        } catch (Exception e) {
            logInfo.sendTaskLog("切换" + buttonText + "模式时出错: " + e.getMessage(), userId, "DeepSeek");
        }
    }


    /**
     * 清理DeepSeek内容中的图标和其他不需要的元素
     * @param content 原始内容
     * @param userId 用户ID，用于记录日志
     * @return 清理后的内容
     */
    private String cleanDeepSeekContent(String content, String userId) {
        if (content == null || content.isEmpty()) {
            return content;
        }
        
        try {
            // 清理DeepSeek头像图标和其他不需要的元素
            String cleaned = content;
            
            // 1. 清理DeepSeek头像图标容器（多种模式匹配）
            cleaned = cleaned.replaceAll("<div class=\"[^\"]*_7eb2358[^\"]*\"[^>]*>.*?</div>", "");
            cleaned = cleaned.replaceAll("<div class=\"[^\"]*_58dfa60[^\"]*\"[^>]*>.*?</div>", "");
            
            // 2. 清理SVG图标及其容器
            cleaned = cleaned.replaceAll("<div[^>]*>\\s*<svg[^>]*>.*?</svg>\\s*</div>", "");
            cleaned = cleaned.replaceAll("<svg[^>]*>.*?</svg>", "");
            
            // 3. 清理其他可能的头像或图标容器
            cleaned = cleaned.replaceAll("<div class=\"[^\"]*avatar[^\"]*\"[^>]*>.*?</div>", "");
            cleaned = cleaned.replaceAll("<div class=\"[^\"]*icon[^\"]*\"[^>]*>.*?</div>", "");
            
            // 4. 清理空的div标签
            cleaned = cleaned.replaceAll("<div[^>]*>\\s*</div>", "");
            
            // 5. 清理连续的空白字符
            cleaned = cleaned.replaceAll("\\s{2,}", " ");
            
            // 如果内容被完全清空或只剩下少量HTML标签，返回原始内容
            String textOnly = cleaned.replaceAll("<[^>]+>", "").trim();
            if (textOnly.isEmpty() || textOnly.length() < 10) {
                return content;
            }
            
            logInfo.sendTaskLog("已清理HTML内容中的头像图标和交互元素，保留原始格式", userId, "DeepSeek");
            return cleaned;
        } catch (Exception e) {
            // 出现异常时记录日志并返回原始内容
            return content;
        }
    }
} 