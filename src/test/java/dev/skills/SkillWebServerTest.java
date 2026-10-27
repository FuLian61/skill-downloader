package dev.skills;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.net.URI;
import java.net.Socket;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.*;
import static org.junit.jupiter.api.Assertions.*;

class SkillWebServerTest {
    @TempDir Path temporary;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final ObjectMapper json = new ObjectMapper();
    private static final String REQUEST = "{\"sourceType\":\"github\",\"sourceUrl\":\"https://github.com/org/repo\"}";

    private SkillWebServer server(SkillWebServer.DownloadOperation operation, int concurrency) throws IOException {
        var config = new DownloadConfig(DownloadConfig.SkillsShMode.SHORTCUT, URI.create("https://skills.sh"), () -> "never-expose-this");
        var server = new SkillWebServer(0, config, operation, temporary.resolve("jobs"), concurrency);
        server.start();
        return server;
    }

    private HttpResponse<byte[]> get(SkillWebServer server, String path) throws Exception {
        return client.send(HttpRequest.newBuilder(server.baseUri().resolve(path)).timeout(Duration.ofSeconds(5)).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    private String token(SkillWebServer server) throws Exception {
        return json.readTree(get(server, "/api/config").body()).path("csrfToken").asText();
    }

    private HttpRequest request(SkillWebServer server, String token, String body) {
        return HttpRequest.newBuilder(server.baseUri().resolve("/api/download"))
                .timeout(Duration.ofSeconds(10)).header("Content-Type", "application/json")
                .header("X-CSRF-Token", token).POST(HttpRequest.BodyPublishers.ofString(body)).build();
    }

    private SkillDownloader.Result artifact(URI uri, Path output, SkillDownloader.Options options) throws IOException {
        Path zip = output.resolve("demo.zip");
        try (ZipOutputStream stream = new ZipOutputStream(Files.newOutputStream(zip))) {
            stream.putNextEntry(new ZipEntry("SKILL.md"));
            stream.write("hello".getBytes(StandardCharsets.UTF_8));
            stream.closeEntry();
        }
        return new SkillDownloader.Result(zip, uri.toString(), "sha", "demo", "a".repeat(64));
    }

    @Test void servesBundledUiAndOnlyNonSecretConfig() throws Exception {
        try (var server = server(this::artifact, 2)) {
            var page = get(server, "/");
            assertEquals(200, page.statusCode());
            assertTrue(new String(page.body(), StandardCharsets.UTF_8).contains("新建下载"));
            assertEquals("DENY", page.headers().firstValue("X-Frame-Options").orElseThrow());
            assertTrue(page.headers().firstValue("Content-Security-Policy").orElseThrow().contains("frame-ancestors 'none'"));
            assertEquals(200, get(server, "/app.js").statusCode());
            assertEquals(200, get(server, "/app.css").statusCode());
            var config = new String(get(server, "/api/config").body(), StandardCharsets.UTF_8);
            assertTrue(config.contains("shortcut"));
            assertFalse(config.contains("never-expose-this"));
            assertEquals(404, get(server, "/secret.env").statusCode());
            assertEquals(405, get(server, "/api/download").statusCode());
        }
    }

    @Test void downloadsBinaryAttachmentAndCleansItsDirectory() throws Exception {
        try (var server = server(this::artifact, 2)) {
            var response = client.send(request(server, token(server), REQUEST), HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(200, response.statusCode());
            assertEquals("application/zip", response.headers().firstValue("Content-Type").orElseThrow());
            assertTrue(response.headers().firstValue("Content-Disposition").orElseThrow().contains("demo.zip"));
            assertEquals("a".repeat(64), response.headers().firstValue("X-Skill-SHA256").orElseThrow());
            try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(response.body()))) {
                assertEquals("SKILL.md", zip.getNextEntry().getName());
            }
        }
        assertFalse(Files.exists(temporary.resolve("jobs")));
    }

    @Test void validatesTypeUrlOptionsAndJsonBeforeCallingDownloader() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        try (var server = server((uri, output, options) -> { calls.incrementAndGet(); return artifact(uri, output, options); }, 2)) {
            String csrf = token(server);
            for (String invalid : List.of(
                    "{\"sourceType\":\"github\",\"sourceUrl\":\"https://skills.sh/org/repo/demo\"}",
                    "{\"sourceType\":\"github\",\"sourceUrl\":\"https:opaque\"}",
                    "{\"sourceType\":\"github\",\"sourceUrl\":\"https://127.0.0.1/private\"}",
                    "{\"sourceType\":\"github\",\"sourceUrl\":\"https://github.com/org/repo\",\"skillPath\":\"../escape\"}",
                    "{\"sourceType\":\"github\",\"sourceUrl\":\"https://github.com/org/repo\",\"version\":\"1.0\"}",
                    "{\"sourceType\":\"clawhub\",\"sourceUrl\":\"https://clawhub.ai/owner/demo\",\"ref\":\"main\"}",
                    "{\"sourceType\":\"github\",\"sourceUrl\":\"https://github.com/org/repo\",\"outputDirectory\":\"/tmp\"}",
                    "{\"sourceType\":\"github\",\"sourceType\":\"clawhub\"}", "[]", "{} {}")) {
                assertEquals(400, client.send(request(server, csrf, invalid), HttpResponse.BodyHandlers.ofString()).statusCode(), invalid);
            }
            assertEquals(0, calls.get());
        }
    }

    @Test void rejectsCrossOriginMissingCsrfAndOversizedBodies() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        try (var server = server((uri, output, options) -> { calls.incrementAndGet(); return artifact(uri, output, options); }, 2)) {
            String csrf = token(server);
            assertEquals(403, client.send(request(server, "wrong", REQUEST), HttpResponse.BodyHandlers.ofString()).statusCode());
            HttpRequest crossOrigin = HttpRequest.newBuilder(server.baseUri().resolve("/api/download"))
                    .header("Content-Type", "application/json").header("X-CSRF-Token", csrf)
                    .header("Origin", "https://evil.example").POST(HttpRequest.BodyPublishers.ofString(REQUEST)).build();
            assertEquals(403, client.send(crossOrigin, HttpResponse.BodyHandlers.ofString()).statusCode());
            assertEquals(413, client.send(request(server, csrf, " ".repeat(17000)), HttpResponse.BodyHandlers.ofString()).statusCode());
            HttpRequest wrongType = HttpRequest.newBuilder(server.baseUri().resolve("/api/download"))
                    .header("X-CSRF-Token", csrf).POST(HttpRequest.BodyPublishers.ofString(REQUEST)).build();
            assertEquals(415, client.send(wrongType, HttpResponse.BodyHandlers.ofString()).statusCode());
            assertEquals(0, calls.get());
        }
    }

    @Test void blocksUnexpectedHostHeader() throws Exception {
        try (var server = server(this::artifact, 2); Socket socket = new Socket("127.0.0.1", server.baseUri().getPort())) {
            socket.setSoTimeout(3000);
            socket.getOutputStream().write("GET / HTTP/1.1\r\nHost: attacker.example\r\nConnection: close\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
            String status = new BufferedReader(new InputStreamReader(socket.getInputStream())).readLine();
            assertTrue(status.contains("403"));
        }
    }

    @Test void reportsPlatformFailuresWithoutServerSecretsAndCleansUp() throws Exception {
        try (var server = server((uri, output, options) -> {
            Files.writeString(output.resolve("partial"), "partial bytes");
            throw new DownloadException(DownloadException.Code.AUTH_REQUIRED, "never-expose-this");
        }, 2)) {
            var response = client.send(request(server, token(server), REQUEST), HttpResponse.BodyHandlers.ofString());
            assertEquals(503, response.statusCode());
            assertTrue(response.body().contains("AUTH_REQUIRED"));
            assertFalse(response.body().contains("never-expose-this"));
        }
        assertFalse(Files.exists(temporary.resolve("jobs")));
    }

    @Test void limitsConcurrentJobsAndReleasesSlotAfterCompletion() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (var server = server((uri, output, options) -> {
            entered.countDown();
            if (!release.await(5, TimeUnit.SECONDS)) throw new IOException("fixture timeout");
            return artifact(uri, output, options);
        }, 1)) {
            String csrf = token(server);
            var first = client.sendAsync(request(server, csrf, REQUEST), HttpResponse.BodyHandlers.ofByteArray());
            try {
                assertTrue(entered.await(3, TimeUnit.SECONDS));
                var rejected = client.send(request(server, csrf, REQUEST), HttpResponse.BodyHandlers.ofString());
                assertEquals(429, rejected.statusCode());
                assertTrue(rejected.body().contains("BUSY"));
                assertEquals("5", rejected.headers().firstValue("Retry-After").orElseThrow());
            } finally {
                release.countDown();
            }
            assertEquals(200, first.get(5, TimeUnit.SECONDS).statusCode());
        }
    }

    @Test void parsesAllThreeSourcesAndTrimsOptionalFields() {
        var alias = SkillWebServer.parseRequest("{\"sourceType\":\"skills.sh\",\"sourceUrl\":\"https://www.skills.sh/mattpocock/skills/improve-codebase-architecture\"}".getBytes(StandardCharsets.UTF_8));
        assertEquals("www.skills.sh", alias.url().getHost());
        for (String[] source : List.of(
                new String[]{"github", "https://github.com/org/repo#main"},
                new String[]{"skills.sh", "https://skills.sh/org/repo/demo"},
                new String[]{"clawhub", "https://clawhub.ai/author/skills/demo"})) {
            String body = "{\"sourceType\":\"" + source[0] + "\",\"sourceUrl\":\"" + source[1] + "\",\"skillPath\":\"  \"}";
            var parsed = SkillWebServer.parseRequest(body.getBytes(StandardCharsets.UTF_8));
            assertEquals(source[1], parsed.url().toString());
            assertNull(parsed.options().path());
        }
    }
}
