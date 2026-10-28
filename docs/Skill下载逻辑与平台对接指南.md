# Skill 下载逻辑与平台对接指南

整理日期：2026-10-27。适用项目：`skill-downloader`，Java 17。

本文依据当前项目源码整理，区分“已经实现的行为”和“扩展建议”，方便将下载能力接入其他业务系统，或者新增技能来源平台。接口示例描述当前程序的调用方式，不代表远端平台的永久接口承诺。

## 1. 一句话说明

**输入来源类型和技能链接，解析内容来源，下载或获取文件，选择单个技能目录，校验并生成 ZIP，返回给用户。**

统一的是“HTTP 获取、技能提取、校验和 ZIP 打包能力”，不是强制所有文件都从 GitHub 获取。

- 确认是 GitHub 来源：复用 GitHub 下载器。
- 平台直接托管 ZIP：下载平台 ZIP，再校验和整理。
- 平台提供内联文本文件：校验路径后本地打包。
- 资源不存在、无法确定技能、缺少凭证、平台拒绝：明确报错，不猜测来源，不绕过访问限制。
- 下载过程中不安装技能、不运行脚本、不执行 `SKILL.md` 中的指令。

## 2. 总体流程

```text
网页表单 / 业务接口 / Java 方法
    ↓
校验来源类型、链接域名、参数组合
    ↓
解析标准来源 Source
    ↓
对应 SourceAdapter.resolve(...)
    ↓
ResolvedSource
    ├─ GitHub(repository, revision, skillPath, name)
    │     → 下载固定提交的仓库 ZIP
    │     → 去掉仓库顶层包装目录
    │     → 定位并提取单个 Skill
    │
    ├─ HostedZip(downloadUrl, revision, name)
    │     → 获取响应
    │     ├─ ZIP → 校验并选择 Skill
    │     └─ 当前 ClawHub 的 public-github JSON
    │           → 转成 GitHub 类型 → 复用 GitHub 路径
    │
    └─ InlineFiles(files, revision, name)
          → 校验文本文件路径和大小
          → 临时打包 → 再校验根目录 SKILL.md
    ↓
生成规范化的单 Skill ZIP
    ↓
计算输出 ZIP 的 SHA-256
    ↓
CLI / Java 返回本地结果，或网页接口返回二进制附件
    ↓
清理临时文件
```

**注意：当前 HostedZip 的非 ZIP 响应处理仍然与 ClawHub 耦合，不是通用的任意 JSON 下载协议。新增平台时必须处理这一点，见第 10 节。**

## 3. 业务系统如何调用

### 3.1 请求字段

当前网页接口：`POST /api/download`，请求类型为 `application/json`。

```json
{
  "sourceType": "github",
  "sourceUrl": "https://github.com/vercel-labs/skills",
  "skillPath": "skills/find-skills",
  "ref": "main"
}
```

| 字段 | 必填 | 说明 |
|---|---|---|
| `sourceType` | 是 | 当前仅接受 `github`、`skills.sh`、`clawhub` |
| `sourceUrl` | 是 | 仓库或技能详情页链接，不是任意 ZIP 下载地址 |
| `skillPath` | 否 | 仓库内技能目录；根目录用 `.` |
| `skill` | 否 | 未明确目录时，用目录名或简单 frontmatter 名称匹配技能 |
| `ref` | 否 | Git 分支、标签或完整提交 SHA |
| `version` | 否 | 仅适用于 ClawHub |

约束：

- `sourceType` 必须与解析出的链接来源一致。
- ClawHub 不接受 `ref`、`skillPath`、`skill`；其他来源不接受 `version`。
- skills.sh 非 GitHub 内联来源也不接受 `ref`、`skillPath`、`skill`。
- 不允许调用方传入服务器输出路径、代理地址或平台 Token。
- 网页接口拒绝未知字段、重复 JSON 字段、超长字段及控制字符。

当前浏览器页面先调用 `GET /api/config` 获取页面会话 `csrfToken`，随后在下载请求头中传 `X-CSRF-Token`。这是本机网页的请求保护，不是多用户身份认证，也不是远端平台 Token。

### 3.2 成功响应

```http
HTTP/1.1 200 OK
Content-Type: application/zip
Content-Disposition: attachment; filename="find-skills-89ad407befe3.zip"
X-Skill-SHA256: <输出 ZIP 的完整 SHA-256>
Cache-Control: no-store

<ZIP 二进制内容>
```

当前接口直接返回附件，不返回下载 URL，不长期保存历史文件。

如果其他业务系统需要“提交任务后获得文件地址”，必须额外实现任务状态、持久化、对象存储、下载授权及过期清理；不能返回当前任务的临时磁盘路径。

### 3.3 Java 直接调用

同一 Java 应用集成时，可以直接调用核心，不必绕一层本机 HTTP。

```java
var downloader = new dev.skills.SkillDownloader();
var result = downloader.download(
    java.net.URI.create("https://github.com/vercel-labs/skills"),
    java.nio.file.Path.of("downloads"),
    new dev.skills.SkillDownloader.Options(
        "main",
        "skills/find-skills",
        null,
        null
    )
);
System.out.println(result.zipPath());
System.out.println(result.sha256());
```

`Options` 顺序：`ref`、`path`、`skill`、`version`。

`Result` 字段：`zipPath`、`source`、`revision`、`skillPath`、`sha256`。

Java 核心根据 URL 判断平台，没有 `sourceType` 参数；业务层若接收独立类型字段，应像网页接口一样校验类型一致性。

## 4. 来源链接解析

| 来源 | 当前支持的页面形式 |
|---|---|
| GitHub | `https://github.com/{owner}/{repo}` |
| GitHub 版本 | `https://github.com/{owner}/{repo}#ref` |
| GitHub 目录 | `https://github.com/{owner}/{repo}/tree/{ref}/{path}` |
| skills.sh GitHub 来源 | `https://skills.sh/{owner}/{repo}/{skill}` |
| skills.sh 非 GitHub 来源 | `https://skills.sh/{domain}/{skill}`，仅正式 API 模式 |
| skills.sh 域名别名 | `www.skills.sh` 精确归一化为 `skills.sh` |
| ClawHub | `https://clawhub.ai/{owner}/skills/{slug}` |
| ClawHub 旧路径 | `https://clawhub.ai/{owner}/{slug}` |
| ClawHub 输入别名 | 接受 `clawhub.com`，实际 API 仍请求 `clawhub.ai` |

当前要求 HTTPS，禁止 URL 用户名/密码和查询参数；仅 GitHub 仓库链接接受 `#ref`。`tree` 链接不能同时使用锚点。

重要细节：

1. 必须通过 URI 解析器取真实 host，不能用字符串 `contains("github.com")`。
2. 别名使用明确白名单，不要普遍删除所有域名的 `www.`，也不要接受 `skills.sh.evil.example`。
3. `tree` URL 的分支本身含 `/` 时有歧义，应使用仓库 URL 加单独 `ref`、`skillPath`。
4. `#ref` 是本程序的输入约定，不是把 URL fragment 发送给 GitHub 服务器。
5. 页面输入域名白名单与真正的 HTTP 下载域名白名单是两层校验。

## 5. GitHub 下载逻辑

```text
解析 owner/repo
    ↓
确定 ref：Options.ref > URL ref > 默认分支
    ↓
未提供 ref：GET https://api.github.com/repos/{owner}/{repo}
             读取 default_branch
    ↓
ref 不是 40 位十六进制提交 SHA：
GET https://api.github.com/repos/{owner}/{repo}/commits/{编码后的 ref}
读取 sha，固定到具体提交
    ↓
GET https://api.github.com/repos/{owner}/{repo}/zipball/{sha}
    ↓
按重定向下载仓库 ZIP（允许 codeload.github.com）
    ↓
提取单个技能并重新打包
```

**当前不是直接拼接 codeload HEAD 地址下载。** 它先解析固定提交，再使用 zipball API。传入完整提交 SHA 时可省略默认分支和提交查询，但归档请求仍经过 GitHub API。

`GITHUB_TOKEN` 可选，仅发给 `api.github.com`，不会转发给 codeload。公开资源也可能因限流或访问策略返回错误；收到 403 不自动切换地址绕过。

### 技能目录如何确定

优先级：

1. 有 `skillPath`：要求这个目录直接包含 `SKILL.md`。
2. 无 `skillPath`、有技能名：匹配候选目录末段，或 `SKILL.md` 简单 YAML frontmatter 的 `name`。
3. 两者都无：仓库必须恰好只有一个候选技能目录。
4. 找不到或出现多个匹配：停止，提示指定目录，不随便取第一个。

只保留目标目录内的文件。目录外共享资源、许可证文件和其他依赖不会自动补齐，接入方应额外审查。

## 6. skills.sh 下载逻辑

### 6.1 快捷模式 shortcut

当前默认模式，不需要 skills.sh API 凭证：

```text
https://skills.sh/{owner}/{repo}/{skill}
    ↓
从 URL 取得 owner/repo 和 skill
    ↓
GitHubSourceAdapter.resolve(...)
    ↓
GitHub 下载仓库 → 匹配技能目录 → 输出 ZIP
```

这里没有访问 skills.sh 详情 API。失败可能实际发生在 GitHub；不能把快捷模式成功描述为“正式 API 鉴权成功”。

`/{domain}/{skill}` 非 GitHub 来源在此模式下返回 `SOURCE_UNAVAILABLE`。

### 6.2 正式模式 api

```text
提取 id，例如 vercel-labs/skills/find-skills
    ↓
GET {SKILLS_SH_API_BASE}/api/v1/skills/{id}
Authorization: Bearer <由服务端 TokenProvider 提供>
    ↓
检查详情 id、source、slug 与请求一致
    ├─ owner/repo 型来源
    │     → 复用 GitHub 解析及下载
    └─ domain 型来源
          → files 必须为非空数组
          → 逐项读取 path、contents
          → UTF-8 文本打包
```

当前实现不是从详情中随意寻找任意 `installUrl` 再下载，而是校验输入来源和详情一致后，按已支持的来源形状分流。

对于 GitHub 来源，即使详情提供文件快照，当前仍优先从 GitHub 获取解析到的提交；**不保证与 skills.sh 缓存快照相同**。

非 GitHub 来源的文件要求：

- `path` 是安全相对路径，不得重复。
- `contents` 必须是字符串；空文件内容可以为空字符串。
- 如果存在 `encoding`，当前仅接受 `utf-8`。
- 不支持把未知二进制或 Base64 直接当文本保存。
- `files` 为 null、空数组或没有根目录 `SKILL.md` 时失败。
- 平台 `hash` 仅作为快照标识返回，不等于生成 ZIP 的 SHA-256，也没有据此完成平台内容验签。

### 6.3 凭证和代理

| 配置 | 当前作用 |
|---|---|
| `SKILLS_SH_MODE` | `shortcut` 或 `api` |
| `SKILLS_SH_API_BASE` | 默认 `https://skills.sh`，可以是受控 HTTPS 代理基地址 |
| `SKILLS_SH_TOKEN_FILE` | 每次请求重新读取的 Token 文件，优先于环境变量 |
| `SKILLS_SH_TOKEN` | 进程环境中的 Token，适合短期调试 |

当前代码不会签发 OIDC Token，也不会自动调用 Vercel 获取新凭证。

- 文件方案：外部刷新服务更新文件；401 时重新读取，只有值变了才重试一次。
- Java 方案：注入 `TokenProvider`，由业务实现 `refresh()`。
- 代理方案：代理内部管理 OIDC，Java 使用代理自己的访问凭证；代理路径与响应需保持兼容。
- 不在前端传 Token，不把短期凭证当永久 API Key。
- API 失败后不静默降级到 shortcut；模式由服务器明确配置。

Nacos/Spring 接入是业务系统自行实现的配置适配，当前项目没有 Nacos SDK，也没有已经部署的代理。

## 7. ClawHub 下载逻辑

```text
解析 owner + slug
    ↓
GET https://clawhub.ai/api/v1/skills/{slug}?ownerHandle={owner}
    ↓
优先使用用户指定 version，否则读取 latestVersion.version
    ↓
GET https://clawhub.ai/api/v1/download
    ?slug={slug}&ownerHandle={owner}[&version={version}]
    ↓
检查下载结果
    ├─ ZIP → 校验并选择技能目录 → 重新打包
    └─ 非 ZIP → 限制为最多 2 MiB，再解析 JSON
         → sourceRef 必须为 public-github
         → repo、commit、path 必须有效
         → GitHubSourceAdapter 下载固定提交
         → 提取 path → 输出 ZIP
```

这里查询的是单个技能，不需要先遍历 `/api/v1/skills` 列表；分页同步属于另一个功能。

关键约束：

- 请求带 `ownerHandle`，避免同名 slug 选错作者。
- 下载描述中的 `commit` 必须为 40 位十六进制 SHA。
- 使用 `repo + commit + path` 构造受控请求，不直接跟随任意 `archiveUrl`。
- 当前没有验证描述的 `contentHash`，输出摘要只代表本地 ZIP。
- 描述不提供安全结论时，不把下载成功当作扫描通过。
- API 拒绝访问时停止，不额外搜索同名 GitHub 仓库作为绕过。

## 8. 共用下载及打包规则

### 8.1 HTTP 获取

`HttpFileDownloader` 当前负责：

- 仅 HTTPS，精确下载域名白名单；额外允许受信配置的 skills.sh API 子路径。
- 检查解析出的 IP，拒绝常见本地和私有地址。
- 手动检查每次重定向，最多 5 次。
- skills.sh 凭证只用于配置的 API 范围，拒绝带凭证跨来源或越出 API 路径重定向。
- GitHub Token 仅发给 GitHub API。
- 流式写入临时文件，不把整个仓库放进 Java 堆内存。

| 限制 | 当前值 |
|---|---|
| ZIP 下载大小 | 不设业务大小上限，流式写入磁盘；仍保留解压大小和超时限制，注意磁盘容量 |
| 详情 JSON 下载大小 | 16 MiB |
| ClawHub 非 ZIP 描述 | 2 MiB |
| ZIP 实际解压总大小 | 300 MiB |
| ZIP / 内联文件条目数 | 20,000 |
| 建立连接超时 | 20 秒 |
| 请求等待响应超时 | 45 秒 |
| 每次正文读取期限 | 120 秒 |

这些主要是当前代码常量，不是已经全部开放给 Nacos 动态配置的参数。

重试行为：

- 429、502、503、504、等待响应头超时：最多额外重试两次，加随机延迟。
- `Retry-After` 支持秒数和 HTTP 日期；要求等待超过 30 秒时直接返回“稍后再试”，不会提前重试。
- skills.sh 401：最多一次凭证刷新重试。
- 403、404：不重试。
- 正文中断：报错并清理，不做断点续传。

**当前 DNS 检查不等于服务器级 SSRF 隔离**，不能替代网络出口策略或解决所有 DNS 重绑定问题。公开服务需要额外网络控制。

### 8.2 ZIP 校验和重新打包

`SkillZipService` 会检查路径、重复条目、实际解压总量及 CRC，拒绝绝对路径、`..`、反斜杠等危险形式。

它读取归档条目，不把原始 ZIP 解压到任意磁盘路径；输出重新创建为普通文件条目，不恢复 Unix 符号链接或可执行权限。

输出文件名：

```text
{清洗后的技能名称}-{ZIP SHA-256 前 12 位}.zip
```

输出根目录应包含：

```text
SKILL.md
scripts/       源技能存在时保留
references/    源技能存在时保留
assets/        源技能存在时保留
```

输出是规范化后的 ZIP，不保证与上游压缩包字节一致。SHA-256 是完整性标识，不是来源签名或安全认证。指定本地输出目录里已存在同名结果时，CLI 不静默覆盖。

## 9. 错误处理和清理

### 9.1 网页接口响应

```json
{
  "code": "SKILL_SELECTION_REQUIRED",
  "message": "无法唯一确定 Skill，请使用 --path 指定目录。候选: [...]"
}
```

| 场景 | HTTP 状态 |
|---|---|
| 输入错误、类型不匹配、未知字段 | 400 |
| Host / Origin / 页面会话不合法 | 403 |
| 上游资源不存在 | 404 |
| 请求体超过 16 KiB | 413 |
| 需要选择技能或目录不存在 | 422 |
| 并发任务满 / 上游限流 | 429 |
| 上游拒绝、下载或归档校验失败 | 502 |
| 服务端凭证缺失、任务被中断 | 503 |

不是所有错误都已统一为 `DownloadException`；ZIP 和文件处理仍可能抛 `IOException`，网页层再转换成业务错误。

### 9.2 文件生命周期

- Java/CLI：工作临时目录在 `finally` 中删除；成功结果保留在调用方的输出目录。
- 网页：额外创建独立任务目录，附件传输完成后删除；失败也清理。
- 浏览器端：读取 Blob 后发起保存，可再次保存；不等于后端长期保留文件。
- 同时最多两个网页下载任务；页面重复点击有前端保护，后端另有独立并发限制。
- 客户端关闭页面不保证马上取消上游下载；仍依赖超时和资源限制。
- 强杀进程或掉电可能遗留临时目录，不能宣称任何情况下都自动清理成功。

### 9.3 诊断建议

“之前成功、现在 403”不能直接归因为限流。接入方建议增加经过脱敏的诊断字段：平台、处理阶段、HTTP 状态、请求 ID、限流额度、重置时间、耗时。不要记录 Authorization、Token 或含敏感签名的完整 URL。

当前 HTTP 错误主要保留状态和来源域名，尚未完整持久化 GitHub 错误正文与限流头；不能依靠当前日志追溯所有历史 403 原因。

## 10. 新增来源平台的具体步骤

假设接入 `ExampleHub`，以下是扩展步骤，**不是当前已实现的第四个平台**。

### 第一步 核实平台契约

先确认：页面 URL 结构、稳定技能 ID、作者/命名空间、详情接口、鉴权、版本规则、内容下载方式、重定向域名、限流、文件编码、许可和审核状态。

不要凭“安装命令看起来像 GitHub”就推定所有技能都有仓库；不要猜测详情 JSON 字段。

### 第二步 扩展输入校验

需要修改以下位置，当前不是只加一个类就能自动注册：

1. `SkillDownloader.parseSource()`：识别新页面结构及精确域名别名。
2. `SkillWebServer.parseRequest()`：增加新 `sourceType`，校验类型和参数组合。
3. 网页 `index.html`：增加来源选项。
4. 网页 `app.js`：增加主机白名单、输入提示、模式说明和可选示例。
5. 根据平台需求更新 `Options` 参数约束；当前 `version` 被限定为 ClawHub，不能直接用于新平台。

### 第三步 实现解析器

在 `dev.skills` 包内实现现有包级 `SourceAdapter`，调用受控 `RemoteClient` 获取详情，转换为已有内容类型：

```text
GitHub 仓库 + 固定版本 + 技能目录
    → ResolvedSource.GitHub

可信的 ZIP 下载地址
    → ResolvedSource.HostedZip

明确编码的完整文本文件列表
    → ResolvedSource.InlineFiles

未知内容协议 / 其他 Git 托管 / 二进制内联文件
    → 先设计新类型和处理器，不硬塞进现有字段
```

`SourceAdapter` 当前接收包级 `SkillDownloader.Source`，字段偏向现有三个来源；对于第四个平台，应按实际需求增加稳定的 identity 模型，不能随意复用 `repo` 字段存放不相关信息。若要跨模块发布适配器 SPI，还需调整接口及模型可见性。

### 第四步 处理 HostedZip 的特殊耦合

**必须处理的扩展点：当前主流程会将 HostedZip 的所有非 ZIP 响应交给 `ClawHubSourceAdapter.handoff()`。**

新增平台不能直接复用这个假设。建议二选一：

- 最小改动：主流程只在当前平台为 ClawHub 时解析 `public-github`；其他平台非 ZIP 立即报 `INVALID_RESPONSE`。
- 可扩展方案：增加平台专属的下载描述解析能力或明确的描述类型，由所属适配器处理 JSON，再输出 GitHub / ZIP / InlineFiles。

对于普通 ZIP 内容，不应因格式错误就自动发起另一平台的下载请求。

### 第五步 注册流程和网络权限

1. 在 `SkillDownloader.download()` 的平台分发 `switch` 注册新适配器。
2. 审核并添加实际 API/CDN 域名，按每次重定向重新校验。
3. 新平台凭证配置应独立，不能复用 skills.sh Token。
4. 不允许调用方通过表单临时增加可信域名或传任意代理。
5. 如果返回有查询签名的下载地址，区分“用户输入页面 URL”与“适配器得到的受控内容 URL”；不要直接取消所有入口的 URL 校验。
6. 新增来源规则不得改变 403/审核失败的终止行为。

### 第六步 接入统一后处理

下载临时文件 → 校验归档 → 定位单 Skill → 重新打包 → 计算哈希 → 返回结果 → 清理。

不要为每个平台重复写一套不带大小限制的 `Files.copy()` 下载，也不要仅凭文件后缀判断 ZIP。

## 11. 接入其他业务系统而非新增来源平台

如果“其他平台”指你自己的管理后台、Spring Boot 服务或业务门户，不必新增 `SourceAdapter`，直接复用现有核心即可。

推荐分层：

```text
业务 Controller
    → 业务用户鉴权 / 配额 / 参数校验
    → SkillDownloader.download(...)
    → 业务文件返回或对象存储
    → 审计、失败提示、过期清理
```

- UI 只提交类型和链接及有限选项。
- 服务端管理平台凭证和固定代理配置。
- 不将当前 `127.0.0.1` HTTP 服务直接当成已完成的公网后端。
- 业务系统若有自己的登录、CSRF 和反向代理，应使用该系统的安全机制；不要机械照搬本机页面 Token 作为用户认证。
- 当前接口同步返回 ZIP；大规模业务建议另行增加异步任务和受授权的短期下载地址。
- 当前 `Result` 是下载结果，不是完整技能元信息；需要标题、说明、作者、许可等字段时应另建元数据对象，明确其来源与缓存策略。

## 12. 测试与验收清单

每个新增来源至少验证：

- 合法页面、域名别名、恶意后缀域名、类型不匹配。
- 作者和技能 ID 对齐、同名冲突、版本固定。
- GitHub 固定提交、单技能目录、多技能歧义。
- 平台 ZIP、平台描述 JSON、HTML 错误页和未知格式。
- 内联文件正常、空快照、重复路径、路径穿越、未知编码。
- 401 刷新最多一次、403 不绕过、404、429、有限重试和长退避。
- 跨域重定向不泄露认证信息。
- 大小、条目数限制、CRC 校验、下载中断。
- 成功和失败清理、并发超限、输出文件不静默覆盖。
- 浏览器真实附件下载以及错误提示。

当前项目已有 36 项测试；此前完成过三个来源的网页示例下载，skills.sh 是快捷模式。正式 skills.sh API 成功鉴权和真实代理仍需要有效凭证联调。模拟测试通过不等于所有平台线上场景都验证过。

## 13. 代码定位

以下为项目内相对位置：

| 文件 | 职责 |
|---|---|
| `src/main/java/dev/skills/SkillWebServer.java` | 表单 API、参数校验、并发、附件返回、任务清理 |
| `src/main/java/dev/skills/SkillDownloader.java` | CLI / Java 入口、来源解析和下载编排 |
| `src/main/java/dev/skills/SourceAdapter.java` | 平台解析接口 |
| `src/main/java/dev/skills/ResolvedSource.java` | 三种内容来源模型 |
| `src/main/java/dev/skills/GitHubSourceAdapter.java` | GitHub 版本解析和仓库归档下载 |
| `src/main/java/dev/skills/SkillsShSourceAdapter.java` | skills.sh 快捷/正式模式 |
| `src/main/java/dev/skills/ClawHubSourceAdapter.java` | 详情、版本和 GitHub 描述解析 |
| `src/main/java/dev/skills/RemoteClient.java` | 共用 HTTP/JSON 获取抽象 |
| `src/main/java/dev/skills/HttpFileDownloader.java` | 网络约束、凭证、重定向、重试和超时 |
| `src/main/java/dev/skills/SkillZipService.java` | 技能选择、归档校验和打包 |
| `src/main/java/dev/skills/DownloadConfig.java` | skills.sh 模式、API 地址和凭证配置 |
| `src/main/java/dev/skills/TokenProvider.java` | 凭证读取与刷新钩子 |
| `src/main/java/dev/skills/DownloadException.java` | 下载业务错误分类 |
| `src/main/resources/web/` | 表单页面、样式和前端校验 |
| `src/test/java/dev/skills/` | 下载、适配器、HTTP 和网页接口测试 |

## 14. 最终实施原则

> 平台适配器负责把“平台链接”变成明确的内容来源；共用下载层负责安全获取字节；ZIP 层负责输出单个技能；业务系统负责用户权限、配额和交付生命周期。

不要将“能创建一个 .zip 文件”当作成功。成功应意味着：来源明确、目标技能明确、归档通过校验、根目录存在 `SKILL.md`，且用户获得了可追溯的输出结果。
