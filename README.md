# Skill ZIP 下载器

Java 17 命令行程序，只下载、检查并重新打包单个 Skill，不安装、不执行任何技能代码。

## 网页表单

skills.sh 页面链接同时接受 `skills.sh` 和 `www.skills.sh`，两者使用同一个适配器；不会因此放宽其他域名的校验。

同一个 JAR 内包含网页资源，无需安装 Node.js 或另外构建前端：

```bash
java -jar target/skill-downloader-1.0.0.jar --serve --port 8080
```

浏览器打开 `http://127.0.0.1:8080`，选择 GitHub / skills.sh / ClawHub，填写页面链接，点击「下载 ZIP」。可展开高级选项指定技能目录、技能名称、Git ref 或 ClawHub 版本。链接类型和域名不一致时拒绝请求。页面显示当前 skills.sh 模式，不把 GitHub 快捷下载当作正式 API 鉴权成功。

网页默认只监听 **127.0.0.1**，不支持通过启动参数开放到所有网卡。把 JAR 发给别人后，对方可以在自己的 JDK 17 环境中启动使用；**把你的 127.0.0.1 链接发给别人不能共享服务**。公开部署需另行增加用户认证、配额、审计、HTTPS、反向代理限制和出口网络隔离；当前不是多用户公网服务，也不要直接用隧道公开暴露它。下载器的 `GITHUB_TOKEN` 和 skills.sh 凭证由服务器环境配置，页面和 API 都不接收调用方凭证、下载目录或代理地址。

- 全局最多 2 个下载，超额返回 429；任务使用独立临时目录，传输完成、平台错误或客户端传输断开后清理。
- 请求体最多 16 KiB，读取限时 15 秒；ZIP 响应传输限时 120 秒。前端在下载期间禁用提交按钮，显示成功、错误和 SHA-256，可再次保存同一个浏览器 Blob。
- 检查 Host、Origin、Fetch Metadata 和页面随机会话令牌；无跨域 CORS。只提供三个固定网页资源，不提供磁盘文件浏览。
- 下游关闭页面不一定立即取消仍在执行的上游下载，但它仍受原 HTTP 超时和并发限制约束。强制杀死进程或机器掉电可能遗留系统临时目录 `skill-web-*`，可在确认服务停止后清理；不自动删除其他进程的任务。
- 同步下载可能持续数分钟；内存中由浏览器暂存完整 ZIP。该版本不提供后台任务队列、持久化历史或断点续传。

### 表单 API

`GET /api/config` 返回 `csrfToken`、`skillsShMode`、`maxConcurrentDownloads` 和 `localOnly`，不返回平台凭证。随后使用 `X-CSRF-Token` 请求头调用 `POST /api/download`：

```json
{
  "sourceType": "github",
  "sourceUrl": "https://github.com/vercel-labs/skills",
  "skillPath": "skills/find-skills",
  "ref": "main"
}
```

必填 `sourceType`（`github` / `skills.sh` / `clawhub`）和 `sourceUrl`。可选 `skillPath`、`skill`、`ref`；ClawHub 仅使用可选 `version`，不要混用两组参数。请求类型为 `application/json`。

成功响应：`200 application/zip`，带 `Content-Disposition: attachment` 和 `X-Skill-SHA256`，不是 JSON 文件地址。错误响应为 `{ "code": "...", "message": "..." }`：输入不合法 400，页面会话/来源被拒绝 403，找不到资源 404，请求过大 413，需要指定技能 422，并发满或上游限流 429，平台拒绝/下载错误 502，服务端凭证缺失 503。错误不返回服务器路径或平台 Token。

本机集成测试使用模拟下载器，不依赖外网；浏览器可直接点击示例做真实下载，但仍可能遇到平台限流或鉴权限制。

2026-10-27 网页验证：36 项测试通过（含 8 项新增网页/API 测试），已在浏览器实测来源切换、示例填入、类型不匹配提示，以及 GitHub、skills.sh 快捷模式的 `find-skills` 和 ClawHub `steipete/weather` 下载成功；页面显示 ZIP 文件名和 SHA-256。skills.sh 正式 API 仍需有效凭证联调。替换或重新打包 JAR 前请先停止运行中的服务，完成后重启并刷新页面，不支持热替换运行中的 JAR。

## 编译

需要 JDK 17+、Maven 3.8+ 和网络访问。首次构建需要下载依赖。

```bash
mvn clean package
java -jar target/skill-downloader-1.0.0.jar --help
```

如果本机 Maven 的镜像配置失效，可使用随附的官方 Central 配置：

```bash
mvn -gs maven-settings.xml -s maven-settings.xml clean package
```

## 使用

```bash
# skills.sh 标准详情页面
java -jar target/skill-downloader-1.0.0.jar https://skills.sh/vercel-labs/skills/find-skills

# GitHub 仓库内按技能名称选择
java -jar target/skill-downloader-1.0.0.jar https://github.com/vercel-labs/skills --skill find-skills

# 明确技能目录；根目录用 --path .
java -jar target/skill-downloader-1.0.0.jar https://github.com/vercel-labs/skills --ref main --path skills/find-skills --out downloads

# ClawHub，把 owner 和 slug 替换为真实值
java -jar target/skill-downloader-1.0.0.jar https://clawhub.ai/owner/skills/slug
java -jar target/skill-downloader-1.0.0.jar https://clawhub.ai/owner/skills/slug --version 1.0.0
```

默认输出到当前目录的 `downloads/`，文件名包含内容哈希前 12 位。已存在文件不会覆盖，再次下载相同内容会报错。成功输出路径、版本或提交、技能源目录及 SHA-256。失败退出码为 1。

GitHub 同时接受 `/tree/分支/目录` 页面和仓库链接 `#ref`（命令行需引号）。`--ref` 优先于 URL 中的 ref，可传完整 `refs/heads/...`、`refs/tags/...` 或提交 SHA，避免分支和标签同名歧义。分支本身含 `/` 时，请使用仓库 URL 加 `--ref` 和 `--path`，不要依赖 tree URL 的歧义解析。缺省分支会查询 GitHub 元数据，再固定到具体提交下载。私有仓库可以设置 `GITHUB_TOKEN`，令牌仅发给 `api.github.com`，不会转发给 codeload。归档使用 GitHub 官方 zipball API，由其重定向至下载端点，不强制所有内容都来自 codeload。

skills.sh 提供 `shortcut` 和 `api` 两种显式模式，默认 shortcut 保持旧调用兼容，并输出模式提示。快捷模式仅处理 `/owner/repo/skill` GitHub 来源，不调用详情 API。两种模式定位目录时均支持目录名或 `SKILL.md` 的简单 YAML `name` 字段；复杂 YAML、同名技能请用 `--path` 明确选择。

ClawHub 接受新旧页面路径及 `clawhub.com` 输入别名，实际 API 一律请求官方文档采用的 `clawhub.ai`。使用页面作者作为 `ownerHandle`，避免同名技能冲突。默认先查询最新版本再按该版本请求下载；无版本元数据时由下载接口解析来源。ZIP 响应直接校验并标准化打包；`public-github` 响应使用 `repo`、`commit`、`path` 获取固定提交。描述不携带扫描结论，程序会明确提示，并不宣称技能安全。不会绕过 API 返回的拒绝或审核阻止。

## Java 调用

```java
var downloader = new dev.skills.SkillDownloader();
var result = downloader.download(
    java.net.URI.create("https://skills.sh/vercel-labs/skills/find-skills"),
    java.nio.file.Path.of("downloads"),
    new dev.skills.SkillDownloader.Options(null, null, null, null));
System.out.println(result.zipPath());
```

`Options` 依次为 `ref`、`path`、`skill`、`version`。仅 ClawHub 使用 `version`，且不能搭配其他来源选择参数。

## skills.sh 正式 API 配置

| 环境变量 | 默认值 | 用途 |
|---|---|---|
| `SKILLS_SH_MODE` | `shortcut` | 设为 `api` 启用详情 API；不会在 API 出错后静默降级 |
| `SKILLS_SH_API_BASE` | `https://skills.sh` | 官方站点或运维批准的 HTTPS 代理基地址，可含路径前缀 |
| `SKILLS_SH_TOKEN_FILE` | 无 | 原始 Bearer Token 文件，每次请求重新读取；优先于环境变量 |
| `SKILLS_SH_TOKEN` | 无 | 临时开发用 Token；进程启动后不能自行更新，不适合长期运行 |
| `GITHUB_TOKEN` | 无 | 可选 GitHub 授权凭证 |

```bash
export SKILLS_SH_MODE=api
export SKILLS_SH_TOKEN_FILE=/absolute/path/to/rotating-token
java -jar target/skill-downloader-1.0.0.jar https://skills.sh/vercel-labs/skills/find-skills
```

正式模式调用 `/api/v1/skills/{id}`，检查 `id`、`source`、`slug` 与输入一致。GitHub 来源继续复用 GitHub 下载器；下载的是解析到的 GitHub 提交，不声称和平台缓存快照一致。非 GitHub 的 `/domain/skill` 来源要求详情包含完整 `files`，按 `path` 和 UTF-8 文本 `contents` 安全打包；文件为空、未知编码或缺失 `SKILL.md` 时明确报错。平台 `hash` 仅作为返回的快照标识，不作为本地 ZIP 的校验依据。

**程序不会自行签发或获取 Vercel OIDC Token。** Token 文件必须由外部凭证刷新服务更新；401 时默认文件提供者仅重新读取一次，仅当凭证变化时重试。环境变量提供者不提供刷新。Java 调用方可注入 `TokenProvider`，在 `refresh()` 内对接自己的凭证服务：

```java
var config = new dev.skills.DownloadConfig(
    dev.skills.DownloadConfig.SkillsShMode.API,
    java.net.URI.create("https://skills.sh"),
    dev.skills.TokenProvider.rotatingFile(java.nio.file.Path.of("/secure/token")));
var downloader = new dev.skills.SkillDownloader(config);
```

受控代理需实现同样的 `api/v1/skills/{id}` 路径与响应契约，在代理内部使用 Vercel OIDC 调用上游。Java 端的 Token 此时是**代理服务自身的访问凭证**，不是必须将 OIDC 分发到 Java。设置 `SKILLS_SH_API_BASE=https://your-approved-gateway.example/prefix/` 后，程序只向该基地址的 `api/v1/` 子路径发送凭证，拒绝跨来源或越出 API 路径的认证重定向。默认仍检查公网 DNS；内网代理需另行设计出口策略。本项目不包含已部署的代理，也没有加入 Nacos 依赖；已有 Spring/Nacos 工程可将配置映射到 `DownloadConfig`，不应把请求方输入作为可信代理配置。

## 代码结构

- `SkillDownloader`：兼容原 CLI 和 Java 入口，编排解析、下载、校验和交付。
- `SkillWebServer` 和 `src/main/resources/web/`：本机网页、JSON 下载接口、受限并发与任务清理。
- `GitHubSourceAdapter`、`SkillsShSourceAdapter`、`ClawHubSourceAdapter`：站点解析，输出 `ResolvedSource`。
- `ResolvedSource`：GitHub 仓库、托管下载地址、内联文本文件三种内容类型。
- `HttpFileDownloader`：受限 HTTPS 获取、凭证范围、有限重试和超时。
- `SkillZipService`：归档路径检查、CRC 检查、单 Skill 提取和内联打包。
- `DownloadException`：HTTP / API 类错误提供 `code()`、`status()`；文件和 ZIP 错误保留 `IOException`。

## 安全和边界

- 仅允许固定 HTTPS 下载域名及显式配置的 skills.sh API 基地址，每次重定向重新检查；最多 5 次重定向。新平台 CDN 域名会被拒绝，必须经过审查再修改白名单。
- 校验 DNS 地址，拒绝常见内网地址；这不是防 DNS 重绑定的服务器级 SSRF 隔离。不要直接将此 CLI 暴露为不可信用户可调用的公网服务；服务端部署还需出口网络控制。
- 单次 ZIP 下载最多 100 MiB，API JSON 最多 16 MiB，解压总量最多 300 MiB，最多 20,000 个 ZIP 条目；请求头超时 45 秒，每次正文下载期限 120 秒。
- 校验路径、重复条目、CRC 和实际解压大小；不将归档条目解压到磁盘。输出重新创建为普通 ZIP 条目，不恢复符号链接、可执行位或其他 Unix 元数据。
- 最终 ZIP 根目录包含 `SKILL.md` 和技能目录内资源。跨目录依赖不会自动补齐，许可证及外部依赖应自行审查。
- 输出 ZIP 是规范化后的文件，不保证与上游字节一致；SHA-256 是本地输出摘要，不是发布者签名或平台内容哈希验证。
- 403/404 不重试。skills.sh 401 仅允许刷新后重试一次，不换来源绕过。429、502、503、504 和等待响应头超时最多重试两次，退避加随机延迟；`Retry-After` 支持秒数及 HTTP 日期，要求等待超过 30 秒时停止并提示稍后重试，不提前重试。正文中断直接报错并清理临时文件。不接受任意 ZIP URL 或下载 API URL，只接受上述页面 URL。

## 验证

```bash
mvn test
```

本地测试不访问网络；公开平台端到端测试需要另行运行上面的实际下载命令。

2026-10-27 重构验证：28 项本地测试通过，覆盖原归档检查、正式 API 元数据解析、非 GitHub 内联打包、ClawHub ZIP / GitHub 描述分支、Token 文件轮换、401 刷新、429 有限重试、禁止凭证跨域重定向和 403 终止。模拟测试不等于线上鉴权成功。没有可用 OIDC 凭证，因此 skills.sh 正式 API 的成功响应与受控代理仍需使用你方凭证联调；不会把快捷模式成功当作正式 API 验证成功。

本次线上回归：ClawHub `steipete/weather` 下载成功；GitHub 和 skills.sh 快捷路径的 GitHub 请求返回 HTTP 403，程序按预期停止，没有绕过。旧版本曾成功下载同一公开 GitHub 技能，但不将旧结果冒充本次验证结果。可在 GitHub 访问恢复或配置有效 `GITHUB_TOKEN` 后重跑。

## 接口依据

核对日期：2026-10-27。平台规则可能更新。

- https://docs.github.com/en/rest/repos/contents#download-a-repository-archive-zip
- https://github.com/vercel-labs/skills
- https://skills.sh/docs/api
- https://github.com/openclaw/clawhub/blob/main/docs/api.md
- https://github.com/openclaw/clawhub/blob/main/convex/lib/githubHandoff.ts
