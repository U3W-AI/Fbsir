## cube-engine 部署指南

## 前置要求
请先完成 [公共环境部署](../common_deployment_guide.md) 中的所有步骤，包括 JDK、Maven 的安装和项目仓库克隆。

### 环境要求
- JDK 17
- Git
- Maven
- Windows 10系统及以上
- 建议内存：16GB (8GB会有卡顿现象)

## 第一阶段：基础服务部署

### 1. 获取主机ID并配置
在开始部署前，您需要填写自己的主机ID。主机ID将用于区分不同的服务实例，确保系统正常运行。

- 克隆仓库到本地后，打开 `src/main/resources/application.yaml` 文件
- 修改`datadir`地址，此为数据目录，建议单独文件夹存放。例：`datadir: D:\AGI\user-data-dir`
- 将主机ID填入配置中：
  ```yaml
  cube:
    url: http://127.0.0.1:8081/aigc
    wssurl: ws://127.0.0.1:8081/websocket?clientId=play-你的主机ID  #主机ID建议使用字母+数字组合，例如play-user01，并在数据库sys_host_whitelist中配置主机id
    datadir: //文件夹路径
    uploadurl: http://127.0.0.1:8081/common/upload
  ```
  **注意：** `play-`前缀固定，后续部分使用你的主机ID

### 2. 构建与启动
- 进入项目目录：   
    ```bash
        cd cube-engine
    ```
- 执行打包：
    ```bash
        mvn clean package -DskipTests
    ```
- 启动服务：
  ```bash
      java -jar target/U3W.jar
    ```
- 服务启动后，可通过 http://localhost:8083/swagger-ui/index.html 查看接口文档

### 3.后台项目启动
- 启动项目cube-ui
- 配置主机ID，登录元宝和豆包(确保后续能够正常咨询)

## 第二阶段：MCP服务配置

### MCP服务概述
cube-engine 内置了 MCP (Model Context Protocol) 服务，支持与腾讯元器等AI平台的无缝集成。MCP服务默认在8083端口提供以下端点：
- `/cubeServer/sse` - Server-Sent Events端点
- `/cubeServer/mcp` - MCP消息处理端点

### 配置说明
MCP服务已在 `application.yaml` 中预配置，无需额外修改：
```yaml
spring:
  ai:
    mcp:
      server:
        enabled: true
        name: cube-engine-mcp
        version: 1.0.0
        sse-endpoint: /cubeServer/sse
        sse-message-endpoint: /cubeServer/mcp
        capabilities:
          tool: true
```

## 第三阶段：内网穿透与外部访问

### 内网穿透的必要性
由于cube-engine和cube-admin运行在本地环境，外部网络无法直接访问。需要通过内网穿透工具将本地服务暴露到公网，使腾讯元器等平台能够连接到您的MCP服务。

### 推荐的内网穿透方案
以下工具可以帮助您实现内网穿透，选择适合您技术水平的方案：

**花生壳 (Oray)**
- 优点：操作简单，有免费版本
- 适用场景：快速测试和演示

**OpenFrp**
- 优点：开源免费，配置灵活
- 适用场景：有一定技术基础的用户

**PassNat**
- 优点：稳定性好，支持多种协议
- 适用场景：生产环境部署

**其他选择：** ngrok、frp、natapp等

### 穿透配置要点
- 本地端口：8083
- 目标路径：`/cubeServer/sse`
- 协议：HTTP
- 确保穿透后的公网地址能够正常访问

## 第四阶段：服务验证与集成

### MCP服务验证
完成内网穿透后，通过浏览器访问 `映射IP:映射端口/cubeServer/sse` 来验证服务是否正常响应。如果能看到相关信息，说明MCP服务部署成功。

### 腾讯元器集成
1. 访问 [腾讯元器](https://yuanqi.tencent.com/v2) 平台
2. 在插件广场中接入MCP插件
3. 自建对话智能体
4. 通过工作流管理导入相关配置文件
5. 修改导入的工作流mcp节点，切换mcp调用，以及获取用户信息节点，配置对应的IP+端口号

### 最终验证
完成所有配置后，发布并咨询相关问题，发送以"1"开头的问题（确保意图准确识别）来测试整个系统是否正常工作。系统将返回相应的回复链接，确认部署完成。

## 注意事项
- 主机ID必须唯一，避免与其他实例冲突
- 内网穿透工具的选择应考虑稳定性和安全性
- 定期检查服务状态和日志信息
- 如遇问题，可查看控制台日志或联系技术支持
   
