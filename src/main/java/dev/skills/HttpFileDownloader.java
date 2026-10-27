package dev.skills;

import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.Set;
import java.util.Objects;
import java.util.concurrent.*;

final class HttpFileDownloader implements RemoteClient {
    private static final Set<String> HOSTS = Set.of("github.com", "api.github.com", "codeload.github.com", "clawhub.ai");
    private final DownloadConfig config;
    @FunctionalInterface interface Exchange {
        HttpResponse<InputStream> send(HttpRequest request) throws IOException, InterruptedException;
    }
    @FunctionalInterface interface Wait { void sleep(long millis) throws InterruptedException; }
    @FunctionalInterface interface UriCheck { void validate(URI uri) throws IOException; }
    private final Exchange exchange;
    private final Wait wait;
    private final UriCheck uriCheck;

    HttpFileDownloader(DownloadConfig config) {
        this.config = config;
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20))
                .followRedirects(HttpClient.Redirect.NEVER).build();
        exchange = request -> client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        wait = Thread::sleep;
        uriCheck = uri -> validateUri(uri, config.skillsShApiBase());
    }

    HttpFileDownloader(DownloadConfig config, Exchange exchange, Wait wait, UriCheck uriCheck) {
        this.config = config;
        this.exchange = exchange;
        this.wait = wait;
        this.uriCheck = uriCheck;
    }

    public void fetch(URI initial, Path destination, long limit) throws IOException, InterruptedException {
        boolean platformApi = isPlatformApi(initial);
        String token = platformApi && config.skillsShTokens() != null ? config.skillsShTokens().token() : null;
        if (platformApi && (token == null || token.isBlank())) {
            throw new DownloadException(DownloadException.Code.AUTH_REQUIRED, "skills.sh API 模式需要 TokenProvider 或 SKILLS_SH_TOKEN_FILE / SKILLS_SH_TOKEN；代理模式使用代理凭证");
        }
        boolean refreshed = false;
        int retries = 0;
        int redirects = 0;
        URI current = initial;
        while (true) {
            uriCheck.validate(current);
            HttpRequest.Builder builder = HttpRequest.newBuilder(current).GET().timeout(Duration.ofSeconds(45))
                    .header("User-Agent", "skill-zip-downloader/1.1");
            if (platformApi) {
                if (!isPlatformApi(current)) throw new DownloadException(DownloadException.Code.FORBIDDEN, "拒绝将 API 凭证重定向至其他来源或路径");
                validateToken(token);
                builder.header("Authorization", "Bearer " + token);
            } else if (current.getHost().equals("api.github.com")) {
                String githubToken = System.getenv("GITHUB_TOKEN");
                if (githubToken != null && !githubToken.isBlank()) {
                    validateToken(githubToken);
                    builder.header("Authorization", "Bearer " + githubToken);
                }
            }
            HttpResponse<InputStream> response;
            try {
                response = exchange.send(builder.build());
            } catch (HttpTimeoutException timeout) {
                if (retries++ >= 2) throw timeout;
                pause(200L * retries);
                continue;
            }
            long delay = -1;
            try (InputStream body = response.body()) {
                int status = response.statusCode();
                if (Set.of(301, 302, 303, 307, 308).contains(status)) {
                    if (++redirects > 5) throw new DownloadException(DownloadException.Code.INVALID_RESPONSE, "重定向次数过多");
                    current = current.resolve(response.headers().firstValue("Location").orElseThrow(() -> new IOException("重定向缺少 Location")));
                    continue;
                }
                if (status == 401 && platformApi && !refreshed) {
                    refreshed = true;
                    String replacement = config.skillsShTokens().refresh();
                    if (replacement != null && !replacement.isBlank() && !replacement.equals(token)) {
                        token = replacement;
                        continue;
                    }
                }
                if ((status == 429 || status == 502 || status == 503 || status == 504) && retries < 2) {
                    delay = retryDelayMillis(response.headers().firstValue("Retry-After").orElse(null), retries, Instant.now());
                    if (delay > 30000) throw new DownloadException(status == 429 ? DownloadException.Code.RATE_LIMITED : DownloadException.Code.REMOTE_ERROR,
                            status, "平台要求较长退避，请至少 " + delay + " 毫秒后重试");
                    retries++;
                } else if (status != 200) {
                    throw DownloadException.http(status, current.getHost());
                } else {
                    if (response.headers().firstValueAsLong("Content-Length").orElse(0) > limit) throw new IOException("下载超过大小限制");
                    ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();
                    ScheduledFuture<?> deadline = timer.schedule(() -> {
                        try { body.close(); } catch (IOException ignored) { }
                    }, 120, TimeUnit.SECONDS);
                    try (OutputStream output = Files.newOutputStream(destination)) {
                        SkillZipService.copyLimited(body, output, limit);
                        if (deadline.isDone()) throw new IOException("下载正文超时");
                    } finally {
                        deadline.cancel(false);
                        timer.shutdownNow();
                    }
                    return;
                }
            }
            pause(delay);
        }
    }

    private void pause(long delay) throws InterruptedException {
        wait.sleep(delay + ThreadLocalRandom.current().nextLong(100, 300));
    }

    private static void validateToken(String token) throws IOException {
        if (token.length() > 32768 || token.chars().anyMatch(character -> character <= 32 || character >= 127)) {
            throw new DownloadException(DownloadException.Code.AUTH_REQUIRED, "认证凭证格式无效；请提供不含空白的原始 Token");
        }
    }

    static long retryDelayMillis(String header, int attempt, Instant now) {
        if (header != null) {
            try { return Math.multiplyExact(Math.max(0, Long.parseLong(header.strip())), 1000); }
            catch (NumberFormatException invalid) {
                try { return Math.max(0, Duration.between(now, ZonedDateTime.parse(header, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()).toMillis()); }
                catch (RuntimeException ignored) { }
            } catch (ArithmeticException overflow) { return Long.MAX_VALUE; }
        }
        return 500L * (1L << Math.min(attempt, 5));
    }

    private boolean isPlatformApi(URI uri) {
        URI base = config.skillsShApiBase();
        return base.getScheme().equals(uri.getScheme()) && base.getHost().equals(uri.getHost())
                && base.getPort() == uri.getPort() && uri.getPath().startsWith(base.getPath() + "api/v1/");
    }

    static void validateUri(URI uri, URI trustedBase) throws IOException {
        boolean trusted = trustedBase != null && trustedBase.getHost().equals(uri.getHost())
                && uri.getPath().startsWith(trustedBase.getPath() + "api/v1/");
        if (!"https".equals(uri.getScheme()) || (!HOSTS.contains(Objects.toString(uri.getHost(), "")) && !trusted)
                || uri.getUserInfo() != null || uri.getFragment() != null || (uri.getPort() != -1 && uri.getPort() != 443)) {
            throw new DownloadException(DownloadException.Code.INVALID_INPUT, "拒绝未许可的下载地址");
        }
        for (InetAddress address : InetAddress.getAllByName(uri.getHost())) {
            if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                    || address.isSiteLocalAddress() || address.isMulticastAddress()
                    || (address.getAddress().length == 16 && (address.getAddress()[0] & 0xfe) == 0xfc)) {
                throw new DownloadException(DownloadException.Code.FORBIDDEN, "拒绝访问本地或私有网络地址");
            }
        }
    }
}
