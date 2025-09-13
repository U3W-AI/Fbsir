package com.playwright.websocket;

/**
 * @author 优立方
 * @version JDK 17
 * @date 2025年01月16日 17:14
 */

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.playwright.controller.*;
import com.playwright.entity.UserInfoRequest;
import com.playwright.entity.mcp.McpResult;
import com.playwright.mcp.CubeMcp;
import com.playwright.utils.BrowserConcurrencyManager;
import com.playwright.utils.BrowserTaskWrapper;
import com.playwright.utils.SpringContextUtils;
import lombok.RequiredArgsConstructor;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

@Service
public class WebSocketClientService {

    // WebSocket服务器地址
    private final String serverUri;

    // WebSocket客户端实例
    private WebSocketClient webSocketClient;
    // 定时任务调度器
    private ScheduledExecutorService scheduler;
    // 是否正在重连标志
    private boolean reconnecting = false;
    // 重连任务
    private ScheduledFuture<?> reconnectTask;
    private ScheduledFuture<?> heartbeatTask;

    /**
     * 构造函数，初始化WebSocket连接
     */
    public WebSocketClientService(@Value("${cube.wssurl}") String serverUri) {
        this.serverUri = serverUri;
        if (serverUri == null || serverUri.trim().isEmpty()) {
            return;
        }
        initializeScheduler();
        connectToServer();
    }

    /**
     * 初始化定时任务调度器
     */
    private void initializeScheduler() {
        if (scheduler == null || scheduler.isShutdown() || scheduler.isTerminated()) {
            scheduler = Executors.newSingleThreadScheduledExecutor();
        }
    }

    /**
     * 连接到WebSocket服务器
     */
    private void connectToServer() {
        try {
            // 创建WebSocket服务器URI
            URI uri = new URI(serverUri);
            // 创建WebSocket客户端
            webSocketClient = new WebSocketClient(uri) {
                /**
                 * 当WebSocket连接成功时调用
                 */
                @Override
                public void onOpen(ServerHandshake handshake) {
                    reconnecting = false;
                    stopReconnectionTask(); // 停止重连任务
                    startHeartbeatTask();
                }


                /**
                 * 当接收到消息时调用
                 */
                @Override
                public void onMessage(String message) {
                    BrowserController browserController = SpringContextUtils.getBean(BrowserController.class);
                    AIGCController aigcController = SpringContextUtils.getBean(AIGCController.class);
                    UserInfoRequest userInfoRequest = JSONObject.parseObject(message, UserInfoRequest.class);
                    BrowserConcurrencyManager concurrencyManager = SpringContextUtils.getBean(BrowserConcurrencyManager.class);
                    BrowserTaskWrapper taskWrapper = SpringContextUtils.getBean(BrowserTaskWrapper.class);
                    CubeMcp cubeMcp = SpringContextUtils.getBean(CubeMcp.class);
                    // 打印当前并发状态
                    taskWrapper.printStatus();
                    String aiName = userInfoRequest.getAiName();
                    // 处理包含"使用F8S"的消息
                    if(message.contains("使用F8S")){
                        // 处理包含"yb-hunyuan"或"yb-deepseek"的消息
                        if(message.contains("yb-hunyuan-pt") || message.contains("yb-deepseek-pt")){
                            concurrencyManager.submitBrowserTask(() -> {
                                try {
//                                    新增检查登录状态
                                    String status = browserController.checkYBLogin(userInfoRequest.getUserId());
                                    if(status.equals("未登录") || status.equals("false")) {
                                        sendMessage(userInfoRequest,McpResult.fail("请先前往官网登录元宝",null), aiName);
                                        return;
                                    }
                                    McpResult mcpResult = aigcController.startYB(userInfoRequest);
                                    sendMessage(userInfoRequest,mcpResult, aiName);
                                } catch (Exception e) {
                                    e.printStackTrace();
                                }
                            }, "元宝智能体", userInfoRequest.getUserId());
                        }
                        // 处理包含"zj-db"的消息
                        if(message.contains("zj-db")){
                            concurrencyManager.submitBrowserTaskWithDeduplication(() -> {
                                try {
                                    String status = browserController.checkDBLogin(userInfoRequest.getUserId());
                                    if(status.equals("未登录") || status.equals("false")) {
                                        sendMessage(userInfoRequest,McpResult.fail("请先前往官网登录豆包",null), aiName);
                                        return;
                                    }
                                    McpResult mcpResult = aigcController.startDB(userInfoRequest);
                                    sendMessage(userInfoRequest,mcpResult,aiName);
                                } catch (Exception e) {
                                    e.printStackTrace();
                                }
                            }, "豆包智能体", userInfoRequest.getUserId(), 5, userInfoRequest.getUserPrompt());
                        }

                        // 处理包含"baidu-agent"的消息
                        if(userInfoRequest.getRoles() != null && userInfoRequest.getRoles().contains("baidu-agent")){
                            concurrencyManager.submitBrowserTask(() -> {
                                try {
                                    String status = browserController.checkBaiduLogin(userInfoRequest.getUserId());
                                    if(status.equals("未登录") || status.equals("false")) {
                                        sendMessage(userInfoRequest,McpResult.fail("请先前往官网登录百度AI",null), aiName);
                                        return;
                                    }
                                    McpResult mcpResult = aigcController.startBaidu(userInfoRequest);
                                    sendMessage(userInfoRequest,mcpResult,aiName);
                                } catch (Exception e) {
                                    e.printStackTrace();
                                }
                            }, "百度AI", userInfoRequest.getUserId());
                        }
                        // 处理包含"deepseek"的消息
                        if(message.contains("deepseek,")){
                            concurrencyManager.submitBrowserTaskWithDeduplication(() -> {
                                try {
                                    String status = browserController.checkDSLogin(userInfoRequest.getUserId());
                                    if(status.equals("未登录") || status.equals("false")) {
                                        sendMessage(userInfoRequest,McpResult.fail("请先前往官网登录deepseek",null), aiName);
                                        return;
                                    }
                                    McpResult mcpResult = aigcController.startDS(userInfoRequest);
                                    sendMessage(userInfoRequest,mcpResult,aiName);
                                } catch (Exception e) {
                                    e.printStackTrace();
                                }
                            }, "DeepSeek智能体", userInfoRequest.getUserId(), 5, userInfoRequest.getUserPrompt());
                        }
                    }

                    // 处理检查DeepSeek登录状态的消息
                    if (message.contains("PLAY_CHECK_DEEPSEEK_LOGIN")) {
                        concurrencyManager.submitBrowserTask(() -> {
                            try {
                                // 先尝试获取登录状态
                                String checkLogin = browserController.checkDSLogin(userInfoRequest.getUserId());

                                // 构建并发送状态消息 - 使用与其他AI智能体一致的格式
                                userInfoRequest.setStatus(checkLogin);
                                userInfoRequest.setType("RETURN_DEEPSEEK_STATUS");
                                sendMessage(JSON.toJSONString(userInfoRequest));
                            } catch (Exception e) {
                                e.printStackTrace();
                                // 发送错误状态 - 使用与其他AI智能体一致的格式
                                userInfoRequest.setStatus("false");
                                userInfoRequest.setType("RETURN_DEEPSEEK_STATUS");
                                sendMessage(JSON.toJSONString(userInfoRequest));
                            }
                        }, "DeepSeek登录检查", userInfoRequest.getUserId());
                    }

                    // 处理获取DeepSeek二维码的消息
                    if(message.contains("PLAY_GET_DEEPSEEK_QRCODE")){
                        concurrencyManager.submitBrowserTask(() -> {
                            try {
                                browserController.getDSQrCode(userInfoRequest.getUserId());
                            } catch (Exception e) {
                                e.printStackTrace();
                            }
                        }, "获取DeepSeek二维码", userInfoRequest.getUserId());
                    }

                    // 处理包含"START_YB"的消息
                    if(message.contains("START_YB")){
                        concurrencyManager.submitBrowserTask(() -> {
                            try {
                                aigcController.startYB(userInfoRequest);
                            } catch (Exception e) {
                                e.printStackTrace();
                            }
                        }, "启动元包", userInfoRequest.getUserId());
                    }
                    
                    // 处理包含"AI排版"的消息
                    if(message.contains("AI排版")){
                        concurrencyManager.submitBrowserTask(() -> {
                            try {
//                                aigcController.startYBOffice(userInfoRequest);
                                cubeMcp.publishToOffice(userInfoRequest);
                            } catch (Exception e) {
                                e.printStackTrace();
                            }
                        }, "AI排版", userInfoRequest.getUserId());
                    }

                    // 处理检查百度AI登录状态的消息
                    if (message.contains("PLAY_CHECK_BAIDU_LOGIN")) {
                        concurrencyManager.submitBrowserTask(() -> {
                            try {
                                String checkLogin = browserController.checkBaiduLogin(userInfoRequest.getUserId());
                                userInfoRequest.setStatus(checkLogin);
                                userInfoRequest.setType("RETURN_BAIDU_STATUS");
                                sendMessage(JSON.toJSONString(userInfoRequest));
                            } catch (Exception e) {
                                e.printStackTrace();
                                // 发送错误状态
                                userInfoRequest.setStatus("false");
                                userInfoRequest.setType("RETURN_BAIDU_STATUS");
                                sendMessage(JSON.toJSONString(userInfoRequest));
                            }
                        }, "百度AI登录检查", userInfoRequest.getUserId());
                    }

                    // 处理获取百度AI二维码的消息
                    if(message.contains("PLAY_GET_BAIDU_QRCODE")){
                        concurrencyManager.submitBrowserTask(() -> {
                            try {
                                browserController.getBaiduQrCode(userInfoRequest.getUserId());
                            } catch (Exception e) {
                                e.printStackTrace();
                            }
                        }, "获取百度AI二维码", userInfoRequest.getUserId());
                    }
                    
                    // 处理获取yb二维码的消息
                    if(message.contains("PLAY_GET_YB_QRCODE")){
                        concurrencyManager.submitBrowserTask(() -> {
                            try {
                                browserController.getYBQrCode(userInfoRequest.getUserId());
                            } catch (Exception e) {
                                e.printStackTrace();
                            }
                        }, "获取元宝二维码", userInfoRequest.getUserId());
                    }

                    if(message.contains("AI评分")){
                        new Thread(() -> {
                            try {
                                aigcController.startDBScore(userInfoRequest);
                            } catch (Exception e) {
                                e.printStackTrace();
                            }
                        }).start();
                    }
                    // 处理检查yb登录状态的消息
                    if (message.contains("CHECK_YB_LOGIN")) {
                        concurrencyManager.submitBrowserTask(() -> {
                            try {
                                String checkLogin = browserController.checkYBLogin(userInfoRequest.getUserId());
                                userInfoRequest.setStatus(checkLogin);
                                userInfoRequest.setType("RETURN_YB_STATUS");
                                sendMessage(JSON.toJSONString(userInfoRequest));
                            } catch (Exception e) {
                                e.printStackTrace();
                            }
                        }, "元宝登录检查", userInfoRequest.getUserId());
                    }

                    // 处理检查数据库登录状态的消息
                    if (message.contains("CHECK_DB_LOGIN")) {
                        concurrencyManager.submitBrowserTask(() -> {
                            try {
                                String checkLogin = browserController.checkDBLogin(userInfoRequest.getUserId());
                                userInfoRequest.setStatus(checkLogin);
                                userInfoRequest.setType("RETURN_DB_STATUS");
                                sendMessage(JSON.toJSONString(userInfoRequest));
                            } catch (Exception e) {
                                e.printStackTrace();
                            }
                        }, "豆包登录检查", userInfoRequest.getUserId());
                    }


                    // 处理获取数据库二维码的消息
                    if(message.contains("PLAY_GET_DB_QRCODE")){
                        concurrencyManager.submitBrowserTask(() -> {
                            try {
                                browserController.getDBQrCode(userInfoRequest.getUserId());
                            } catch (Exception e) {
                                e.printStackTrace();
                            }
                        }, "获取豆包二维码", userInfoRequest.getUserId());
                    }

                }

                /**
                 * 当WebSocket连接关闭时调用
                 */
                @Override
                public void onClose(int code, String reason, boolean remote) {
                    startReconnectionTask();
                    stopHeartbeatTask();
                }

                /**
                 * 当WebSocket发生错误时调用
                 */
                @Override
                public void onError(Exception ex) {
                    startReconnectionTask();
                    stopHeartbeatTask();
                }
            };

            // 连接到WebSocket服务器
            webSocketClient.connect();

        } catch (URISyntaxException e) {
        }
    }

    /**
     * 启动心跳任务
     */
    private void startHeartbeatTask() {
        if (scheduler == null || scheduler.isShutdown() || scheduler.isTerminated()) {
            initializeScheduler();
        }

        stopHeartbeatTask(); // 避免重复创建

        heartbeatTask = scheduler.scheduleAtFixedRate(() -> {
            if (webSocketClient != null && webSocketClient.isOpen()) {
                JSONObject pingMessage = new JSONObject();
                pingMessage.put("type", "heartbeat");
                webSocketClient.send(pingMessage.toJSONString());
            }
        }, 0, 30, TimeUnit.SECONDS); // 每 30 秒发送一次
    }

    /**
     * 关闭心跳任务
     */
    private void stopHeartbeatTask() {
        if (heartbeatTask != null && !heartbeatTask.isCancelled()) {
            heartbeatTask.cancel(false);
            heartbeatTask = null;
        }
    }

    /**
     * 启动重连任务
     */
    private void startReconnectionTask() {
        initializeScheduler();

        if (reconnecting) {
            return; // 避免重复启动重连任务
        }

        reconnecting = true;

        // 停止之前的重连任务（如果有的话），确保不会创建多个任务
        stopReconnectionTask();

        // 启动新的重连任务
        reconnectTask = scheduler.scheduleWithFixedDelay(() -> {
            if (webSocketClient == null || !webSocketClient.isOpen()) {
                connectToServer();
            } else {
                stopReconnectionTask(); // 连接成功后，停止任务
            }
        }, 0, 5, TimeUnit.SECONDS);
    }

    /**
     * 停止重连任务
     */
    private void stopReconnectionTask() {
        if (reconnectTask != null && !reconnectTask.isCancelled()) {
            reconnectTask.cancel(false);
            reconnectTask = null;
        }
    }

    /**
     * 发送消息到WebSocket服务器
     */
    public void sendMessage(String message) {
        if (webSocketClient != null && webSocketClient.isOpen()) {
            webSocketClient.send(message);
        }
    }

    /**
     * 用于区分消息的方法
     */
    public void sendMessage(UserInfoRequest userInfoRequest, McpResult mcpResult, String aiName) {
        Map<String, String> content = new HashMap<>();
        content.put("type",  userInfoRequest.getType());
        content.put("userId", userInfoRequest.getUserId());
        content.put("aiName", aiName);
        content.put("taskId", userInfoRequest.getTaskId());
        if("openAI".equals(userInfoRequest.getType()))  {
            String result = mcpResult.getResult();
            if(result == null || result.isEmpty()) {
                result = aiName + "执行错误,请稍后重试";
            }
            content.put("message", result);
        } else{
//            TODO 其他情况
        }
        if (webSocketClient != null && webSocketClient.isOpen()) {
            webSocketClient.send(JSONObject.toJSONString(content));
        }
    }
}
