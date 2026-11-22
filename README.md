# Halo BlogsClub Push

[![Halo](https://img.shields.io/badge/Halo-%3E%3D2.22-4f46e5)](https://halo.run)
[![License](https://img.shields.io/badge/license-MIT-green)](./LICENSE)
[![Version](https://img.shields.io/badge/version-1.0.2-blue)](https://github.com/YueXia-DeLiSha/halo-blogsclub-push/releases)

把 Halo 博客文章按 **BC-PUSH-V2** 协议自动推送到 [BlogsClub](https://www.blogsclub.org)，支持控制台手动推送、内置去重、失败自动重试。

> English documentation: [README.en.md](./README.en.md)

---

## 功能特性

| 特性 | 说明 |
| --- | --- |
| 自动推送 | 插件启动 30 秒后首跑，之后每 5 分钟扫描一次已发布文章 |
| 手动推送 | 控制台「工具 → BlogsClub 推送」页面的「立即推送」按钮 |
| 去重 | 已推送文章记录在 Halo 内置 ConfigMap，重启、升级都不会重复推送 |
| 首次同步 | 可配置同步最近 N 篇历史文章，只推一次 |
| 签名安全 | PushKey 只在本地参与 HMAC-SHA256 计算，绝不随请求上送 |
| 失败重试 | 推送失败不会标记为已推送，下一轮自动重试 |
| 零外部依赖 | 不需要额外数据库、Redis 或本地日志文件 |

## 兼容性

- Halo **>= 2.22**（已在 2.27.3 上验证）
- 数据库：PostgreSQL / MySQL / H2 均可（插件只使用 Halo 扩展存储）
- 运行环境：JDK 21（插件内置，由 Halo 提供）

## 安装

1. 从 [Releases](https://github.com/YueXia-DeLiSha/halo-blogsclub-push/releases) 下载 `plugin-blogsclub-push-x.y.z.jar`；
2. 进入 `控制台 → 插件 → 安装 → 本地上传`，把 jar 拖进去；
3. 安装完成后在插件列表点击「启用」。

## 配置

`控制台 → 插件 → BlogsClub 推送 → 设置`

| 配置项 | 类型 | 说明 |
| --- | --- | --- |
| `status` | 开关 | 是否开启推送。关闭后不扫描、不推送、不打印日志 |
| `pushId` | 文本 | BlogsClub 分配的推送 ID，会出现在 `Authorization` 头中 |
| `pushKey` | 密码 | 推送 KEY，仅用于本地签名，不会随请求上送 |
| `posts` | 数字 | **首次同步**推送最近多少篇已发布文章（0 = 只推之后的新文章） |
| `siteUrl` | 文本 | 文章链接域名，例如 `https://blog.example.com`。留空则使用 Halo 的「外部访问地址」；**必须与 BlogsClub 登记的博客域名一致** |

## 工作原理

```
每 5 分钟 / 手动触发
        │
        ▼
读取设置 ── 关闭或缺少 PushID/PushKey ──► 跳过
        │
        ▼
拉取已发布文章（按发布时间倒序）
        │
        ▼
对比去重记录（ConfigMap blogsclub-push-records）
        │
        ├─ 首次运行：标注全部为已读，只推送最近 N 篇
        └─ 之后：只推送未记录的新文章，单轮上限 10 篇
        │
        ▼
逐篇签名并 POST https://www.blogsclub.org/openPush（间隔 1 秒）
        │
        ├─ 成功：写入去重记录
        └─ 失败：不记录，下一轮重试
```

### 签名算法（BC-PUSH-V2）

```
bodyHash  = sha256(请求体原文)
canonical = "BC-PUSH-V2\n{PushID}\n{timestamp}\n{nonce}\nPOST\n{bodyHash}"
signature = HMAC-SHA256(canonical, PushKey)      // 64 位小写 hex

Authorization: Bearer {PushID}.{timestamp}.{nonce}.{signature}
```

请求体使用 `application/x-www-form-urlencoded`：`title` / `link` / `description` / `date`。

## 手动推送

进入 `控制台 → 工具 → BlogsClub 推送`，点击「立即推送」。

接口：`POST /apis/api.console.halo.run/v1alpha1/trigger`

> 注意：`CustomEndpoint` 的路由挂在 group/version 根下，**路径中不含插件名**。

返回示例：

```json
{
  "ok": true,
  "disabled": false,
  "published": 1,
  "pending": 0,
  "pushed": 0,
  "failed": 0,
  "titles": [],
  "message": "没有需要推送的新文章"
}
```

## 日志

```bash
sudo docker logs -f halo 2>&1 | grep --line-buffered blogsclub-push
```

典型输出：

```
[blogsclub-push] 插件已启动，每 5 分钟扫描一次
[blogsclub-push] 首次同步：现有已发布文章 3 篇，本次推送最近 2 篇
[blogsclub-push] 推送《Hello Halo》成功：{"code":1,"msg":"推送完成：新入库 1 篇，今日剩余额度 49 篇。",...}
[blogsclub-push] 本轮成功推送 2 篇
```

Halo 的文件日志同样包含这些内容：`~/.halo2/logs/halo.log`。

## 常见问题

**1. 页面按钮报「接口未找到（HTTP 404）」**

升级插件后请重启一次 Halo，确保新路由已注册：

```bash
cd ~/halo && sudo docker compose restart halo
```

**2. 远程下载提示「插件已存在」**

Halo 的远程下载只用于新装。升级请走「本地上传」。

**3. 推送成功但文章没出现在 BlogsClub**

接口返回 `code: 1` 只代表已入库，文章还需经过 BlogsClub 的审核队列。

**4. 提示域名不一致 / 被拒绝**

`siteUrl` 必须与 BlogsClub 后台登记的博客域名完全一致（含 `https://`）。

**5. 什么时候会重复推送？**

不会。去重记录存在 Halo 的 `blogsclub-push-records` ConfigMap 中，文章以内部 `metadata.name` 记录。未发布 → 发布不会重复，重新发布也不会。

## 开发构建

```bash
# 前端（Node 18+）
cd ui
npm install --legacy-peer-deps
npm run build          # 产物在 ui/build/dist

# 后端（JDK 21）
./gradlew build -x test
```

产物：`build/libs/plugin-blogsclub-push-<version>.jar`

构建要点：

- `build.gradle` 中的 `processUiResources` 会把 `ui/build/dist` 复制进 jar 的 `console/` 目录，Halo 从那里加载控制台界面；
- `@halo-dev/ui-plugin-bundler-kit` 需要 Vite 6+，npm 安装时必须加 `--legacy-peer-deps`；
- 如果你用 Gradle 9.x，建议加 `org.gradle.jvmargs=-Xmx600m` 以便在小内存机器上构建。

## 目录结构

```
halo-blogsclub-push/
├── src/main/java/run/halo/blogsclub/push/
│   ├── BlogsClubPushPlugin.java      # 生命周期与定时任务
│   ├── BlogsClubPushService.java     # 扫描、签名、发送、去重
│   ├── BlogsClubPushEndpoint.java    # POST /trigger、GET /status
│   ├── PushRecords.java              # ConfigMap 去重记录
│   ├── PushSettings.java             # 配置项
│   └── SyncResult.java               # 同步结果
├── src/main/resources/
│   ├── plugin.yaml                   # 插件清单
│   ├── extensions/settings.yaml      # 设置表单
│   └── logo.png
└── ui/                               # 控制台界面（Vue 3 + Vite）
    ├── src/index.ts                  # 注册菜单与路由
    └── src/views/PushView.vue        # 手动推送页面
```

## 更新日志

### 1.0.2

- 修正手动推送接口路径为 `/apis/api.console.halo.run/v1alpha1/trigger`
- 前端不再做多路径探测

### 1.0.1

- 新增控制台页面与「立即推送」按钮（`CustomEndpoint`）
- `syncOnce()` 返回结构化 `SyncResult`
- 作者信息更正

### 1.0.0

- 首个版本：定时推送、去重、首次历史同步

## 作者

**挽着红月缓缓走** · [github.com/yuexia-delisha](https://github.com/yuexia-delisha)

## 许可

[MIT](./LICENSE)
