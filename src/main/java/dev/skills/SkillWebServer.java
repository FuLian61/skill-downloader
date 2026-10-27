package dev.skills;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.*;

public final class SkillWebServer implements AutoCloseable {
    private static final ObjectMapper JSON = new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final Set<String> FIELDS = Set.of("sourceType", "sourceUrl", "ref", "skillPath", "skill", "version");
    private final HttpServer server;
    private final ExecutorService workers;
    private final ScheduledExecutorService deadlines = Executors.newSingleThreadScheduledExecutor();
    private final Semaphore slots;
    private final int concurrency;
    private final DownloadOperation download;
    private final DownloadConfig config;
    private final Path workspace;
    private final String csrfToken;
    private final Map<String, byte[]> assets;

    @FunctionalInterface
    interface DownloadOperation {
        SkillDownloader.Result run(URI source, Path output, SkillDownloader.Options options) throws IOException, InterruptedException;
    }

    public static void main(String[] args) throws IOException {
        int port = 8080;
        if (args.length != 0) {
            if (args.length != 2 || !args[0].equals("--port")) throw new IllegalArgumentException("网页模式仅支持 --port <端口>");
            port = Integer.parseInt(args[1]);
        }
        if (port < 1 || port > 65535) throw new IllegalArgumentException("端口必须在 1 到 65535 之间");
        DownloadConfig config = DownloadConfig.fromEnvironment();
        SkillWebServer app = new SkillWebServer(port, config, new SkillDownloader(config)::download,
                Files.createTempDirectory("skill-web-"), 2);
        Runtime.getRuntime().addShutdownHook(new Thread(app::close, "skill-web-shutdown"));
        app.start();
        System.out.println("Skill 下载页面: " + app.baseUri());
        System.out.println("仅监听本机；按 Ctrl+C 停止。skills.sh 模式: " + config.skillsShMode().name().toLowerCase(Locale.ROOT));
    }

    SkillWebServer(int port, DownloadConfig config, DownloadOperation download, Path workspace, int concurrency) throws IOException {
        this.config = config;
        this.download = download;
        this.workspace = workspace;
        this.concurrency = concurrency;
        slots = new Semaphore(concurrency);
        byte[] secret = new byte[32];
        new SecureRandom().nextBytes(secret);
        csrfToken = Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
        assets = Map.of("/", resource("index.html"), "/app.css", resource("app.css"), "/app.js", resource("app.js"));
        Files.createDirectories(workspace);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 32);
        workers = new ThreadPoolExecutor(4, 8, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(32),
                new ThreadPoolExecutor.AbortPolicy());
        server.setExecutor(workers);
        server.createContext("/", this::handle);
    }

    public void start() { server.start(); }

    public URI baseUri() { return URI.create("http://127.0.0.1:" + server.getAddress().getPort()); }

    private static byte[] resource(String name) throws IOException {
        try (InputStream input = SkillWebServer.class.getResourceAsStream("/web/" + name)) {
            if (input == null) throw new IOException("网页资源缺失: " + name);
            return input.readAllBytes();
        }
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            securityHeaders(exchange);
            if (!validHost(exchange)) {
                error(exchange, 403, "INVALID_HOST", "仅允许通过本机地址访问。");
                return;
            }
            String path = exchange.getRequestURI().getPath();
            if (assets.containsKey(path)) {
                if (!method(exchange, "GET")) return;
                String type = path.endsWith(".css") ? "text/css" : path.endsWith(".js") ? "application/javascript" : "text/html";
                send(exchange, 200, type + "; charset=utf-8", assets.get(path));
            } else if (path.equals("/api/config")) {
                if (!method(exchange, "GET")) return;
                if (!sameOrigin(exchange)) {
                    error(exchange, 403, "INVALID_ORIGIN", "请从本机表单页面发起请求。");
                    return;
                }
                send(exchange, 200, "application/json; charset=utf-8", JSON.writeValueAsBytes(Map.of(
                        "csrfToken", csrfToken,
                        "skillsShMode", config.skillsShMode().name().toLowerCase(Locale.ROOT),
                        "maxConcurrentDownloads", concurrency,
                        "localOnly", true)));
            } else if (path.equals("/api/download")) {
                if (!method(exchange, "POST")) return;
                handleDownload(exchange);
            } else {
                error(exchange, 404, "NOT_FOUND", "接口不存在。");
            }
        }
    }

    private void handleDownload(HttpExchange exchange) throws IOException {
        String token = exchange.getRequestHeaders().getFirst("X-CSRF-Token");
        if (!sameOrigin(exchange) || token == null || !MessageDigest.isEqual(csrfToken.getBytes(StandardCharsets.UTF_8), token.getBytes(StandardCharsets.UTF_8))) {
            error(exchange, 403, "INVALID_SESSION", "页面会话无效，请刷新页面后重试。");
            return;
        }
        String contentType = Objects.toString(exchange.getRequestHeaders().getFirst("Content-Type"), "");
        if (!contentType.split(";", 2)[0].strip().equalsIgnoreCase("application/json")) {
            error(exchange, 415, "INVALID_CONTENT_TYPE", "接口只接受 application/json。");
            return;
        }
        if (!slots.tryAcquire()) {
            exchange.getResponseHeaders().set("Retry-After", "5");
            error(exchange, 429, "BUSY", "当前下载任务已满，请稍后重试。");
            return;
        }
        Path job = null;
        try {
            Request request;
            ScheduledFuture<?> bodyTimeout = deadlines.schedule(exchange::close, 15, TimeUnit.SECONDS);
            try {
                byte[] body = exchange.getRequestBody().readNBytes(16385);
                if (body.length > 16384) {
                    error(exchange, 413, "REQUEST_TOO_LARGE", "请求不能超过 16 KiB。");
                    return;
                }
                request = parseRequest(body);
            } catch (IllegalArgumentException invalid) {
                error(exchange, 400, "INVALID_INPUT", invalid.getMessage());
                return;
            } finally {
                bodyTimeout.cancel(false);
            }
            job = Files.createTempDirectory(workspace, "job-");
            SkillDownloader.Result result;
            try {
                result = download.run(request.url(), job, request.options());
            } catch (DownloadException failure) {
                int status = switch (failure.code()) {
                    case INVALID_INPUT -> 400;
                    case NOT_FOUND -> 404;
                    case RATE_LIMITED -> 429;
                    case AUTH_REQUIRED -> 503;
                    default -> 502;
                };
                String message = switch (failure.code()) {
                    case AUTH_REQUIRED -> "服务器未配置有效的平台凭证，或凭证已过期。请联系运行程序的人配置，不要在表单中填写 Token。";
                    case FORBIDDEN -> "上游平台拒绝了请求（403 或访问限制）。不会自动换来源绕过，请检查平台权限或稍后再试。";
                    case RATE_LIMITED -> "上游平台限流，有限次数重试后仍无法完成，请稍后再试。";
                    case NOT_FOUND -> "上游资源不存在，请检查链接和版本。";
                    case SOURCE_UNAVAILABLE -> "该来源没有可用文件快照，或当前 skills.sh 模式不支持此来源。";
                    default -> "平台响应无效或暂不可用，请检查链接和版本后重试。";
                };
                error(exchange, status, failure.code().name(), message);
                return;
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                error(exchange, 503, "INTERRUPTED", "服务正在停止，请稍后重试。");
                return;
            } catch (IOException failure) {
                String message = failure.getMessage();
                if (message != null && message.startsWith("无法唯一确定 Skill")) {
                    error(exchange, 422, "SKILL_SELECTION_REQUIRED", message);
                } else if (message != null && message.startsWith("指定目录没有 SKILL.md")) {
                    error(exchange, 422, "SKILL_PATH_NOT_FOUND", "指定目录没有 SKILL.md，请检查高级选项中的技能目录。");
                } else {
                    error(exchange, 502, "DOWNLOAD_FAILED", "下载或 ZIP 校验失败，请检查链接、网络及技能目录。服务器未返回未经校验的文件。");
                }
                return;
            }
            Path file = result.zipPath().toRealPath();
            if (!file.startsWith(job.toRealPath()) || !Files.isRegularFile(file)) throw new IOException("下载结果超出任务目录");
            String filename = file.getFileName().toString().replaceAll("[^a-zA-Z0-9._-]", "_");
            exchange.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"" + filename + "\"");
            exchange.getResponseHeaders().set("X-Skill-SHA256", result.sha256());
            ScheduledFuture<?> transferTimeout = deadlines.schedule(exchange::close, 120, TimeUnit.SECONDS);
            try {
                exchange.getResponseHeaders().set("Content-Type", "application/zip");
                exchange.sendResponseHeaders(200, Files.size(file));
                try (InputStream input = Files.newInputStream(file); OutputStream output = exchange.getResponseBody()) {
                    input.transferTo(output);
                }
            } finally {
                transferTimeout.cancel(false);
            }
        } catch (IOException failure) {
            System.err.println("网页请求未完成: " + failure.getClass().getSimpleName());
            if (exchange.getResponseCode() == -1) {
                try { error(exchange, 500, "IO_ERROR", "请求读取或临时文件处理失败，请稍后重试。"); }
                catch (IOException disconnected) { }
            }
        } catch (RuntimeException failure) {
            System.err.println("网页任务异常: " + failure.getClass().getSimpleName());
            if (exchange.getResponseCode() == -1) error(exchange, 500, "INTERNAL_ERROR", "服务内部错误，请重试或查看服务日志。");
        } finally {
            if (job != null) deleteJob(job);
            slots.release();
        }
    }

    record Request(URI url, SkillDownloader.Options options) {}

    static Request parseRequest(byte[] body) {
        try {
            JsonNode json = JSON.readTree(body);
            if (json == null || !json.isObject()) throw new IllegalArgumentException("请求必须是 JSON 对象。");
            var fields = json.fieldNames();
            while (fields.hasNext()) if (!FIELDS.contains(fields.next())) throw new IllegalArgumentException("请求包含不支持的字段。");
            String type = field(json, "sourceType", 20);
            String urlText = field(json, "sourceUrl", 4096);
            if (type == null || urlText == null) throw new IllegalArgumentException("请选择来源类型并填写链接。");
            if (!Set.of("github", "skills.sh", "clawhub").contains(type)) throw new IllegalArgumentException("来源类型不支持。");
            URI url = URI.create(urlText);
            SkillDownloader.Source source = SkillDownloader.parseSource(url);
            if (!source.platform().equals(type)) throw new IllegalArgumentException("来源类型与链接域名不一致，请修改类型或链接。");
            String ref = field(json, "ref", 512);
            String path = field(json, "skillPath", 1024);
            String skill = field(json, "skill", 200);
            String version = field(json, "version", 200);
            if (path != null && !path.equals(".")) SkillZipService.safeEntry(path);
            if (type.equals("clawhub") && (ref != null || path != null || skill != null)) throw new IllegalArgumentException("ClawHub 仅支持版本选项。");
            if (!type.equals("clawhub") && version != null) throw new IllegalArgumentException("version 仅适用于 ClawHub。");
            return new Request(url, new SkillDownloader.Options(ref, path, skill, version));
        } catch (com.fasterxml.jackson.core.JsonProcessingException invalid) {
            throw new IllegalArgumentException("JSON 格式无效或包含重复字段。");
        } catch (IOException invalid) {
            throw new IllegalArgumentException(invalid.getMessage());
        }
    }

    private static String field(JsonNode json, String name, int maximum) {
        JsonNode node = json.get(name);
        if (node == null || node.isNull()) return null;
        if (!node.isTextual()) throw new IllegalArgumentException(name + " 必须是字符串。");
        String text = node.textValue().strip();
        if (text.length() > maximum || text.chars().anyMatch(character -> character < 32 || character == 127)) {
            throw new IllegalArgumentException(name + " 过长或含控制字符。");
        }
        return text.isEmpty() ? null : text;
    }

    private boolean validHost(HttpExchange exchange) {
        String host = exchange.getRequestHeaders().getFirst("Host");
        String port = ":" + server.getAddress().getPort();
        return ("127.0.0.1" + port).equals(host) || ("localhost" + port).equals(host);
    }

    private boolean sameOrigin(HttpExchange exchange) {
        String site = exchange.getRequestHeaders().getFirst("Sec-Fetch-Site");
        if (site != null && !site.equals("same-origin") && !site.equals("none")) return false;
        String origin = exchange.getRequestHeaders().getFirst("Origin");
        return origin == null || origin.equals("http://" + exchange.getRequestHeaders().getFirst("Host"));
    }

    private static boolean method(HttpExchange exchange, String allowed) throws IOException {
        if (exchange.getRequestMethod().equals(allowed)) return true;
        exchange.getResponseHeaders().set("Allow", allowed);
        error(exchange, 405, "METHOD_NOT_ALLOWED", "请求方法不支持。");
        return false;
    }

    private static void securityHeaders(HttpExchange exchange) {
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.getResponseHeaders().set("X-Frame-Options", "DENY");
        exchange.getResponseHeaders().set("Referrer-Policy", "no-referrer");
        exchange.getResponseHeaders().set("Content-Security-Policy", "default-src 'self'; script-src 'self'; style-src 'self'; connect-src 'self'; img-src 'self' data:; object-src 'none'; base-uri 'none'; frame-ancestors 'none'; form-action 'self'");
    }

    private static void error(HttpExchange exchange, int status, String code, String message) throws IOException {
        send(exchange, status, "application/json; charset=utf-8", JSON.writeValueAsBytes(Map.of("code", code, "message", message)));
    }

    private static void send(HttpExchange exchange, int status, String type, byte[] bytes) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", type);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) { output.write(bytes); }
    }

    private static void deleteJob(Path job) {
        try (var files = Files.walk(job)) {
            for (Path file : files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(file);
        } catch (IOException failure) {
            System.err.println("任务临时目录清理失败: " + failure.getClass().getSimpleName());
        }
    }

    public void close() {
        server.stop(1);
        workers.shutdownNow();
        deadlines.shutdownNow();
        try {
            workers.awaitTermination(5, TimeUnit.SECONDS);
            Files.deleteIfExists(workspace);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (IOException failure) {
            System.err.println("服务停止时仍有临时文件，目录: " + workspace);
        }
    }
}
