package dev.skills;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.util.*;
import javax.net.ssl.SSLSession;
import static org.junit.jupiter.api.Assertions.*;

class HttpFileDownloaderTest {
    @TempDir Path temporary;
    private static final URI API = URI.create("https://skills.sh/api/v1/skills/org/repo/demo");

    record Reply(int statusCode, HttpHeaders headers, InputStream body, HttpRequest request) implements HttpResponse<InputStream> {
        public Optional<HttpResponse<InputStream>> previousResponse() { return Optional.empty(); }
        public Optional<SSLSession> sslSession() { return Optional.empty(); }
        public URI uri() { return request.uri(); }
        public HttpClient.Version version() { return HttpClient.Version.HTTP_1_1; }
    }

    private HttpResponse<InputStream> reply(HttpRequest request, int status, Map<String, List<String>> headers) {
        return new Reply(status, HttpHeaders.of(headers, (name, value) -> true), new ByteArrayInputStream("{}".getBytes()), request);
    }

    private DownloadConfig config(TokenProvider tokens) {
        return new DownloadConfig(DownloadConfig.SkillsShMode.API, URI.create("https://skills.sh"), tokens);
    }

    @Test void refreshes401OnceWithNewToken() throws Exception {
        List<String> tokens = new ArrayList<>();
        TokenProvider provider = new TokenProvider() {
            public String token() { return "old-token"; }
            public String refresh() { return "new-token"; }
        };
        var downloader = new HttpFileDownloader(config(provider), request -> {
            tokens.add(request.headers().firstValue("Authorization").orElseThrow());
            return reply(request, tokens.size() == 1 ? 401 : 200, Map.of());
        }, millis -> fail("401 does not back off"), uri -> assertEquals(API, uri));
        downloader.fetch(API, temporary.resolve("result"), 100);
        assertEquals(List.of("Bearer old-token", "Bearer new-token"), tokens);
    }

    @Test void neverLoopsOnInvalidCredentialsOrRefresh() {
        List<HttpRequest> requests = new ArrayList<>();
        TokenProvider provider = new TokenProvider() {
            public String token() { return "old"; }
            public String refresh() { return "new"; }
        };
        var downloader = new HttpFileDownloader(config(provider), request -> {
            requests.add(request);
            return reply(request, 401, Map.of());
        }, millis -> fail("no sleep"), uri -> { });
        var error = assertThrows(DownloadException.class, () -> downloader.fetch(API, temporary.resolve("result"), 100));
        assertEquals(DownloadException.Code.AUTH_REQUIRED, error.code());
        assertEquals(2, requests.size());
    }

    @Test void respects429AndStopsAfterTwoRetries() {
        List<Long> waits = new ArrayList<>();
        List<HttpRequest> requests = new ArrayList<>();
        var downloader = new HttpFileDownloader(config(() -> "token"), request -> {
            requests.add(request);
            return reply(request, 429, Map.of("Retry-After", List.of("1")));
        }, waits::add, uri -> { });
        var error = assertThrows(DownloadException.class, () -> downloader.fetch(API, temporary.resolve("result"), 100));
        assertEquals(DownloadException.Code.RATE_LIMITED, error.code());
        assertEquals(3, requests.size());
        assertEquals(2, waits.size());
        assertTrue(waits.stream().allMatch(delay -> delay >= 1000));
    }

    @Test void longRetryAfterDoesNotRetryEarly() {
        var downloader = new HttpFileDownloader(config(() -> "token"), request -> reply(request, 429, Map.of("Retry-After", List.of("120"))),
                millis -> fail("long retry should return"), uri -> { });
        assertThrows(DownloadException.class, () -> downloader.fetch(API, temporary.resolve("result"), 100));
    }

    @Test void refusesCredentialRedirectBeforeSendingSecondRequest() {
        List<HttpRequest> requests = new ArrayList<>();
        var downloader = new HttpFileDownloader(config(() -> "secret"), request -> {
            requests.add(request);
            return reply(request, 302, Map.of("Location", List.of("https://github.com/elsewhere")));
        }, millis -> fail("no sleep"), uri -> { });
        var error = assertThrows(DownloadException.class, () -> downloader.fetch(API, temporary.resolve("result"), 100));
        assertEquals(DownloadException.Code.FORBIDDEN, error.code());
        assertEquals(1, requests.size());
    }

    @Test void forbiddenIsTerminal() {
        var downloader = new HttpFileDownloader(config(() -> "token"), request -> reply(request, 403, Map.of()),
                millis -> fail("403 must not retry"), uri -> { });
        assertEquals(DownloadException.Code.FORBIDDEN,
                assertThrows(DownloadException.class, () -> downloader.fetch(API, temporary.resolve("result"), 100)).code());
    }

    @Test void publicPageAndWwwRedirectNeverReadOrSendPlatformToken() throws Exception {
        List<URI> requests = new ArrayList<>();
        var downloader = new HttpFileDownloader(config(() -> { fail("must not read token"); return null; }), request -> {
            assertTrue(request.headers().firstValue("Authorization").isEmpty());
            requests.add(request.uri());
            return requests.size() == 1 ? reply(request, 307, Map.of("Location", List.of("https://www.skills.sh/trending")))
                    : reply(request, 200, Map.of());
        }, millis -> fail("no retry"), uri -> { });
        downloader.fetch(URI.create("https://skills.sh/trending"), temporary.resolve("page"), 100);
        assertEquals(2, requests.size());
    }

    @Test void publicPageWhitelistDoesNotAllowOtherPathsOrHosts() {
        for (String url : List.of("https://www.skills.sh/api/v1/skills", "https://skills.sh/other", "https://evil.example/trending",
                "https://skills.sh/trending?redirect=1", "http://skills.sh/trending")) {
            assertThrows(DownloadException.class, () -> HttpFileDownloader.validateUri(URI.create(url), null));
        }
    }

    @Test void zipDownloadAllowsMoreThanOneHundredMiB() throws Exception {
        long size = 100L * 1024 * 1024 + 1;
        InputStream body = new InputStream() {
            long remaining = size;
            public int read() {
                if (remaining == 0) return -1;
                remaining--;
                return 0;
            }
            public int read(byte[] bytes, int offset, int length) {
                if (length == 0) return 0;
                if (remaining == 0) return -1;
                int count = (int) Math.min(length, remaining);
                Arrays.fill(bytes, offset, offset + count, (byte) 0);
                remaining -= count;
                return count;
            }
        };
        var downloader = new HttpFileDownloader(config(() -> null), request -> new Reply(200,
                HttpHeaders.of(Map.of("Content-Length", List.of(Long.toString(size))), (name, value) -> true), body, request),
                millis -> fail("no retry"), uri -> {});
        Path target = temporary.resolve("large.zip");
        downloader.fetch(URI.create("https://codeload.github.com/owner/repo/zip/main"), target, SkillDownloader.MAX_DOWNLOAD);
        assertEquals(size, Files.size(target));
    }

    @Test void metadataDownloadStillEnforcesSizeLimit() {
        var downloader = new HttpFileDownloader(config(() -> null),
                request -> reply(request, 200, Map.of("Content-Length", List.of("16777217"))),
                millis -> fail("no retry"), uri -> {});
        assertThrows(IOException.class, () -> downloader.fetch(URI.create("https://clawhub.ai/api/v1/skills"),
                temporary.resolve("metadata.json"), 16L * 1024 * 1024));
    }
}
