# Halo BlogsClub Push

[![Halo](https://img.shields.io/badge/Halo-%3E%3D2.22-4f46e5)](https://halo.run)
[![License](https://img.shields.io/badge/license-MIT-green)](./LICENSE)
[![Version](https://img.shields.io/badge/version-1.0.2-blue)](https://github.com/YueXia-DeLiSha/halo-blogsclub-push/releases)

A Halo plugin that pushes published posts to [BlogsClub](https://www.blogsclub.org) using the **BC-PUSH-V2** signature scheme. Ships with a console UI for manual pushes, built-in deduplication and automatic retries.

> 中文文档（主文档）: [README.md](./README.md)

---

## Features

| Feature | Description |
| --- | --- |
| Auto push | Runs 30s after startup, then scans published posts every 5 minutes |
| Manual push | "Push now" button on the console page `Tools → BlogsClub Push` |
| Deduplication | Pushed posts are recorded in a Halo ConfigMap, so restarts and upgrades never re-push |
| First-run sync | Optionally push the latest N existing posts once |
| Secure signing | `PushKey` only takes part in the local HMAC-SHA256 computation and is never sent |
| Retry on failure | A failed post is not marked as pushed and is retried on the next cycle |
| No external deps | No extra database, Redis or local log file required |

## Requirements

- Halo **>= 2.22** (verified on 2.27.3)
- Database: PostgreSQL / MySQL / H2 (plugin only uses Halo extension storage)
- JDK 21 (provided by Halo at runtime)

## Installation

1. Download `plugin-blogsclub-push-x.y.z.jar` from [Releases](https://github.com/YueXia-DeLiSha/halo-blogsclub-push/releases);
2. Open `Console → Plugins → Install → Local upload` and drop the jar;
3. Enable the plugin in the plugin list.

## Configuration

`Console → Plugins → BlogsClub Push → Settings`

| Key | Type | Description |
| --- | --- | --- |
| `status` | checkbox | Enable pushing. When off, the plugin neither scans nor logs |
| `pushId` | text | Push ID issued by BlogsClub, sent in the `Authorization` header |
| `pushKey` | password | Push key, used for local signing only |
| `posts` | number | How many existing posts to push on the **first** sync (0 = only new posts) |
| `siteUrl` | text | Post link domain, e.g. `https://blog.example.com`. Falls back to Halo's external URL. **Must match the domain registered on BlogsClub** |

## How it works

```
every 5 minutes / manual trigger
        │
        ▼
read settings ── disabled or missing PushID/PushKey ──► skip
        │
        ▼
load published posts (newest first)
        │
        ▼
compare with the dedup record (ConfigMap blogsclub-push-records)
        │
        ├─ first run: mark all as seen, push only the newest N
        └─ later: push unseen posts only, max 10 per cycle
        │
        ▼
sign and POST each post to https://www.blogsclub.org/openPush (1s apart)
        │
        ├─ success: write dedup record
        └─ failure: keep it pending, retry next cycle
```

### Signature (BC-PUSH-V2)

```
bodyHash  = sha256(raw request body)
canonical = "BC-PUSH-V2\n{PushID}\n{timestamp}\n{nonce}\nPOST\n{bodyHash}"
signature = HMAC-SHA256(canonical, PushKey)      // 64 lowercase hex chars

Authorization: Bearer {PushID}.{timestamp}.{nonce}.{signature}
```

Request body is `application/x-www-form-urlencoded`: `title` / `link` / `description` / `date`.

## Manual push

Open `Console → Tools → BlogsClub Push` and click **Push now**.

Endpoint: `POST /apis/api.console.halo.run/v1alpha1/trigger`

> Note: `CustomEndpoint` routes are mounted under the group/version root — the **plugin name is not part of the path**.

Sample response:

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

## Logs

```bash
sudo docker logs -f halo 2>&1 | grep --line-buffered blogsclub-push
```

Typical output:

```
[blogsclub-push] 插件已启动，每 5 分钟扫描一次
[blogsclub-push] 首次同步：现有已发布文章 3 篇，本次推送最近 2 篇
[blogsclub-push] 推送《Hello Halo》成功：{"code":1,"msg":"推送完成：新入库 1 篇，今日剩余额度 49 篇。",...}
[blogsclub-push] 本轮成功推送 2 篇
```

The same lines are written to Halo's file log at `~/.halo2/logs/halo.log`.

## Troubleshooting

**"Endpoint not found (HTTP 404)"**

Restart Halo after upgrading so the new routes are registered:

```bash
cd ~/halo && sudo docker compose restart halo
```

**Remote download says "plugin already exists"**

Halo's remote download only installs new plugins. Use **Local upload** to upgrade.

**Push succeeded but the post is missing on BlogsClub**

`code: 1` means it entered the review queue; it still needs BlogsClub's approval.

**Domain mismatch / rejected**

`siteUrl` must exactly match the blog domain registered on BlogsClub (including `https://`).

**Will a post be pushed twice?**

No. Dedup records live in the `blogsclub-push-records` ConfigMap, keyed by the post's internal `metadata.name`.

## Building

```bash
# Frontend (Node 18+)
cd ui
npm install --legacy-peer-deps
npm run build          # output goes to ui/build/dist

# Backend (JDK 21)
./gradlew build -x test
```

Artifact: `build/libs/plugin-blogsclub-push-<version>.jar`

Notes:

- `processUiResources` in `build.gradle` copies `ui/build/dist` into the jar's `console/` directory;
- `@halo-dev/ui-plugin-bundler-kit` requires Vite 6+, so `--legacy-peer-deps` is required;
- On small machines add `org.gradle.jvmargs=-Xmx600m` to `gradle.properties`.

## Project layout

```
halo-blogsclub-push/
├── src/main/java/run/halo/blogsclub/push/
│   ├── BlogsClubPushPlugin.java      # lifecycle & scheduler
│   ├── BlogsClubPushService.java     # scan, sign, send, dedup
│   ├── BlogsClubPushEndpoint.java    # POST /trigger, GET /status
│   ├── PushRecords.java              # ConfigMap-backed dedup record
│   ├── PushSettings.java             # settings POJO
│   └── SyncResult.java               # sync result
├── src/main/resources/
│   ├── plugin.yaml                   # plugin manifest
│   ├── extensions/settings.yaml      # settings form
│   └── logo.png
└── ui/                               # console UI (Vue 3 + Vite)
    ├── src/index.ts                  # menu & route registration
    └── src/views/PushView.vue        # manual push page
```

## Changelog

### 1.0.2

- Fixed the manual push endpoint to `/apis/api.console.halo.run/v1alpha1/trigger`
- Removed multi-path probing from the UI

### 1.0.1

- Added the console page with a "Push now" button (`CustomEndpoint`)
- `syncOnce()` now returns a structured `SyncResult`
- Corrected author information

### 1.0.0

- Initial release: scheduled push, dedup, first-run history sync

## Author

**挽着红月缓缓走 (YueXia-DeLiSha)** · [github.com/yuexia-delisha](https://github.com/yuexia-delisha)

## License

[MIT](./LICENSE)
