# SFW Web Console v1.0 — 使用文档

---

## 目录

1. [项目概述](#1-项目概述)
2. [功能特性](#2-功能特性)
3. [系统架构](#3-系统架构)
4. [安装部署](#4-安装部署)
5. [使用指南](#5-使用指南)
6. [SFW 命令参考](#6-sfw-命令参考)
7. [Web 界面说明](#7-web-界面说明)
8. [API 接口文档](#8-api-接口文档)
9. [安全说明](#9-安全说明)
10. [自定义开发](#10-自定义开发)
11. [故障排除](#11-故障排除)

---

## 1. 项目概述

**SFW Web Console** 是一个 SairFramework（SFW）插件，提供基于 Web 浏览器的远程控制台。

**技术栈：**

| 层级 | 技术 |
|------|------|
| 后端 | Java 8 + SFW Plugin API |
| 前端 | HTML5 + CSS3 + JavaScript (原生) |
| 通信 | HTTP/HTTPS + SSE (Server-Sent Events) |
| 安全 | PBKDF2WithHmacSHA256 密码哈希、会话管理、登录限流 |

---

## 2. 功能特性

### 核心功能
- **远程命令执行** — 通过 Web 浏览器向 SFW 发送任意命令，输出实时同步
- **SFW 界面模拟** — 在 Web 端还原 SFW 控制台的文字颜色、边框样式、背景效果
- **实时输出推送** — 基于 SSE（Server-Sent Events）技术，SFW 输出毫秒级同步到浏览器
- **命令历史记录** — 上下方向键可翻阅历史命令（最多 500 条）
- **前端文件可编辑** — 默认从 JAR 加载（零磁盘写入），设置 web.root 后可从外部目录加载并热编辑

### 安全功能
- **HTTP 默认模式** — 默认监听 localhost:8080，仅本机可访问
- **HTTPS 远程访问** — 用户自行提供 JKS 证书后可启用 HTTPS（TLS 1.2），支持远程访问
- **密码认证** — PBKDF2WithHmacSHA256（12 万次迭代 + 32 字节随机盐）
- **首次登录设置密码** — 第一次使用强制设置密码
- **会话管理** — HttpOnly + SameSite=Strict Cookie，可配置超时
- **暴力破解防护** — 同一 IP 连续 5 次失败锁定 5 分钟
- **安全响应头** — X-Content-Type-Options、X-Frame-Options、XSS 防护

### 个性化定制
- **主题定制** — 可自定义背景色、文字色、边框色、强调色
- **字体可调** — 字体族、字体大小自由设置
- **透明度调节** — 背景透明度 0.3~1.0 范围可调
- **前端文件热编辑** — 设置 web.root 后可直接编辑外部目录下的文件

---

## 3. 系统架构

```
┌──────────────────────────────────────────────────────┐
│                     Web Browser                       │
│  ┌────────────┐  ┌───────────────┐  ┌─────────────┐  │
│  │ Login Page │  │ SFW Terminal  │  │   Settings   │  │
│  └────────────┘  └───────────────┘  └─────────────┘  │
│                          │                            │
│              HTTP/HTTPS + SSE (Real-time)             │
└──────────────────────────┼───────────────────────────┘
                           │
┌──────────────────────────┼───────────────────────────┐
│               SFW Plugin (sair.sfwweb)                │
│                          │                            │
│  ┌───────────────────────┴───────────────────────┐   │
│  │              WebServer (HTTP/HTTPS)            │   │
│  │  ┌─────────┐ ┌──────────┐ ┌────────────────┐  │   │
│  │  │REST API │ │SSE Stream│ │Static Files    │  │   │
│  │  │(命令/   │ │(实时输出) │ │(从 web root    │  │   │
│  │  │ 配置/   │ │          │ │  目录加载)     │  │   │
│  │  │ 认证)   │ │          │ │                │  │   │
│  │  └────┬────┘ └────┬─────┘ └────────────────┘  │   │
│  └───────┼───────────┼───────────────────────────┘   │
│          │           │                                │
│  ┌───────┴───────────┴───────────────────────────┐   │
│  │           SfwConsoleCapture                    │   │
│  │        (PrintRunnable 拦截控制台输出)           │   │
│  └───────────────────────┬───────────────────────┘   │
│                          │                            │
│  ┌───────┴───────────┴───────────────────────────┐   │
│  │           CommandHandler                       │   │
│  │         (SairCons.runner 执行命令)              │   │
│  └───────────────────────┬───────────────────────┘   │
└──────────────────────────┼───────────────────────────┘
                           │
┌──────────────────────────┼───────────────────────────┐
│                   SFW Core                            │
│  ┌───────────────────────┴───────────────────────┐   │
│  │  SairCons · Libraries · ConsFrame · 插件系统   │   │
│  └───────────────────────────────────────────────┘   │
└──────────────────────────────────────────────────────┘
```

**数据流：**
1. 用户在 Web 终端输入命令（如 `/list`）
2. 浏览器通过 HTTP POST 发送到 `/api/command/execute`
3. `CommandHandler` 调用 `SairCons.runner()` 执行
4. SFW 执行命令，输出被 `SfwConsoleCapture`（PrintRunnable）拦截
5. 输出通过 SSE 流实时推送到浏览器
6. 浏览器动态追加到控制台显示区

---

## 4. 安装部署

### 4.1 前置要求

- **SFW 运行环境**：Java 8 JDK
- **SFW 主目录**：`C:\Sair\SairFrameWork\`

### 4.2 安装步骤

**步骤一：放置插件**
```
将 SFW_WEB.jar 复制到：
C:\Sair\SairFrameWork\plugins\exection\SFW_WEB.jar
```

**步骤二：启动 SFW**
```
双击 C:\Sair\SairFrameWork\run.bat
```

**步骤三：验证加载**
```
在 SFW 控制台中输入 /list，应看到 SFW_WEB 插件在列表中
```

### 4.3 Web 前端文件说明

插件默认从 JAR classpath 直接加载前端文件，**零磁盘写入，无需解压**。

如需自定义界面，将 `web/` 目录复制到任意位置：
```
SFW_WEB/setwebroot D:\myweb
SFW_WEB/restart
```
重启后将从该目录加载文件，直接编辑即可热更新。

### 4.4 默认访问（HTTP，本机）

```
http://localhost:8080
```
首次访问需要设置登录密码。

### 4.5 启用 HTTPS 远程访问

HTTPS 不会自动生成证书，需要你自行准备 JKS 格式的证书文件。

**生成自签名 JKS 证书示例（使用 JDK 自带的 keytool）：**
```batch
C:\Sair\SairFrameWork\8x64\bin\keytool.exe -genkeypair ^
  -alias sfwweb ^
  -keyalg RSA -keysize 2048 ^
  -validity 365 ^
  -keystore C:\Sair\sfwweb.jks ^
  -storepass yourpassword ^
  -keypass yourpassword ^
  -dname "CN=localhost, OU=SFW, O=SFW, L=Local, ST=State, C=CN"
```

**在 SFW 中配置 HTTPS：**
```
SFW_WEB/setkeystore C:\Sair\sfwweb.jks        # 指定证书文件路径
SFW_WEB/setkeystorepass yourpassword           # 设置证书密码
SFW_WEB/togglehttps on                          # 启用 HTTPS
SFW_WEB/setport 8443                            # 设置 HTTPS 端口
SFW_WEB/restart                                 # 重启生效
```

配置完成后访问 `https://你的IP:8443` 即可远程连接。

---

## 5. 使用指南

### 5.1 Web 终端基本操作

| 操作 | 方式 |
|------|------|
| 输入命令 | 在底部输入框输入 SFW 命令，按 Enter 发送 |
| 历史命令 | ↑ 上一条命令，↓ 下一条命令 |
| 清空屏幕 | `Ctrl + L` 或点击标题栏 🗑️ 按钮 |
| 退出登录 | 点击标题栏 🚪 按钮 |
| 打开设置 | 点击标题栏 ⚙️ 按钮 |

### 5.2 首次登录流程

1. 打开 `http://localhost:8080`
2. 看到 "INITIAL SETUP" 界面
3. 输入密码 → 确认密码 → 点击 "SET PASSWORD"
4. 自动跳转到 SFW 控制台界面

### 5.3 修改密码

1. 点击右上角 ⚙️ 进入设置面板
2. 在"密码管理"区域填入当前密码和新密码
3. 点击"更新密码"

---

## 6. SFW 命令参考

### 6.1 服务器控制

| 命令 | 说明 | 示例 |
|------|------|------|
| `<插件名>/start` | 启动 Web 服务器 | `SFW_WEB/start` |
| `<插件名>/stop` | 停止 Web 服务器 | `SFW_WEB/stop` |
| `<插件名>/restart` | 重启 Web 服务器 | `SFW_WEB/restart` |
| `<插件名>/status` | 查看服务器状态 | `SFW_WEB/status` |
| `<插件名>/setport 8080` | 设置监听端口（需重启） | `SFW_WEB/setport 9090` |

### 6.2 HTTPS / 证书配置

| 命令 | 说明 | 示例 |
|------|------|------|
| `<插件名>/setkeystore <路径>` | 设置 JKS 证书文件路径 | `SFW_WEB/setkeystore C:\Sair\sfwweb.jks` |
| `<插件名>/setkeystorepass <密码>` | 设置证书密码 | `SFW_WEB/setkeystorepass mypass` |
| `<插件名>/togglehttps [on\|off]` | 开关 HTTPS（需重启） | `SFW_WEB/togglehttps on` |
| `<插件名>/setwebroot <目录>` | 设置前端文件根目录 | `SFW_WEB/setwebroot D:\myweb` |

### 6.3 密码管理

| 命令 | 说明 | 示例 |
|------|------|------|
| `<插件名>/setpassword <密码>` | 设置/强制修改密码 | `SFW_WEB/setpassword mypassword` |
| `<插件名>/resetpassword <旧> <新>` | 验证旧密码后重置 | `SFW_WEB/resetpassword old new` |

### 6.4 主题定制

| 命令 | 说明 |
|------|------|
| `<插件名>/settheme <背景> <文字> <边框> <强调>` | 一键四色 |
| `<插件名>/setfontcolor <颜色>` | 文字颜色 |
| `<插件名>/setbgcolor <颜色>` | 背景颜色 |
| `<插件名>/setbordercolor <颜色>` | 边框颜色 |
| `<插件名>/setaccentcolor <颜色>` | 强调色 |
| `<插件名>/setfontsize <大小>` | 字体大小 (10-24) |
| `<插件名>/setfontfamily <字体>` | 字体族 |
| `<插件名>/setopacity <0.3-1.0>` | 背景透明度 |

### 6.5 其他

| 命令 | 说明 |
|------|------|
| `<插件名>/showconfig` | 显示完整配置 |
| `<插件名>/setmaxlines <条数>` | 设置最大输出行数 |
| `<插件名>/clearlog` | 清空 Web 控制台日志 |

---

## 7. Web 界面说明

### 7.1 动态效果
- **粒子背景** — 粒子随主题颜色变化，粒子间动态连线
- **Glitch 文字效果** — 标题赛博朋克故障动画
- **霓虹边框** — 输入框聚焦发光
- **角标装饰** — 控制台四角边框高亮

### 7.2 设置面板

| 区域 | 可配置项 |
|------|----------|
| 🔒 密码管理 | 修改登录密码 |
| 🎨 主题定制 | 4 种颜色、字体大小、背景透明度（实时预览） |
| ⚙️ 服务器配置 | 会话超时时间、最大输出行数 |

---

## 8. API 接口文档

### 认证
- `GET /api/auth/status` — 检查认证状态
- `POST /api/auth/setup` — 首次设置密码
- `POST /api/auth/login` — 登录（返回 Session Cookie）
- `POST /api/auth/logout` — 登出
- `POST /api/auth/changepassword` — 修改密码（需登录）

### 命令
- `POST /api/command/execute` — 执行 SFW 命令
- `GET /api/command/history` — 获取命令历史

### 实时
- `GET /api/output/stream` — SSE 实时输出流

### 配置
- `GET /api/config/get` — 获取配置
- `POST /api/config/update` — 更新服务器配置
- `POST /api/config/theme` — 更新主题

---

## 9. 安全说明

### 网络安全
- **HTTP 模式（默认）** — 仅监听 `127.0.0.1`，不对外暴露
- **HTTPS 模式** — 需用户自行提供 JKS 证书，支持 TLS 1.2
- **安全响应头** — `X-Content-Type-Options`、`X-Frame-Options`、`X-XSS-Protection`

### 认证安全
- **PBKDF2WithHmacSHA256** — 12 万次迭代，32 字节随机盐
- **时序攻击防护** — 恒定时间密码比较
- **会话 Cookie** — HttpOnly + SameSite=Strict
- **登录限流** — 5 次失败锁定 5 分钟

### 建议
1. 使用强密码（12+ 位，含大小写、数字、特殊字符）
2. 远程访问务必启用 HTTPS
3. 不要将 HTTP 模式暴露在公网
4. 定期更新密码和证书

---

## 10. 自定义开发

### 10.1 项目结构

```
SFW_WEB/
├── META-INF/MANIFEST.MF         # ACT: sair.sfwweb.SfwWebActivity
├── sair/sfwweb/
│   ├── SfwWebActivity.java      # 主入口
│   └── core/
│       ├── ConfigManager.java
│       ├── PasswordManager.java
│       ├── SfwConsoleCapture.java
│       ├── CommandHandler.java
│       └── WebServer.java
├── web/                         # 前端源文件
│   ├── index.html
│   ├── css/style.css
│   └── js/app.js
├── _build.bat                   # 构建脚本
└── docs/README.md               # 本文档
```

### 10.2 构建

```batch
双击 _build.bat
```
或手动：
```batch
dir /s /b *.java > sources.txt
C:\Sair\SairFrameWork\8x64\bin\javac.exe -encoding UTF-8 ^
  -cp ".;C:\Sair\SairFrameWork\SFW.jar" -d out @sources.txt
xcopy /E /I /Y web out\web
xcopy /E /I /Y META-INF out\META-INF
cd out
C:\Sair\SairFrameWork\8x64\bin\jar.exe cfm ..\SFW_WEB.jar ^
  META-INF\MANIFEST.MF .
```

---

## 11. 故障排除

| 问题 | 解决方案 |
|------|----------|
| 浏览器无法连接 | 确认 SFW 插件已启动，检查端口是否被占用 |
| 端口被占用 | `SFW_WEB/setport 9090` 换端口 |
| 忘记密码 | `SFW_WEB/setpassword <新密码>` |
| HTTPS 启用失败 | 确认 keystore 路径正确，密码正确，文件为 JKS 格式 |
| 前端修改不生效 | 确认修改的是 web root 目录下的文件，刷新浏览器 |
| 证书过期 | 重新生成 JKS 证书，更新 keystore 配置后重启 |

**重置插件：**
删除 `C:\Sair\SairFrameWork\data\sair.sfwweb.SfwWebActivity\` 目录后重启 SFW。

---

> **SFW Web Console v1.0** — 让 SFW 触手可及，掌控你的终端.

> 项目路径：`C:\Users\Small\Desktop\SFW_WEB`
