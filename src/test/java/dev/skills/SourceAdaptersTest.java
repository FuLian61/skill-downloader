package dev.skills;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.zip.*;
import static org.junit.jupiter.api.Assertions.*;

class SourceAdaptersTest {
    @TempDir Path temporary;
    private static final String SHA = "a".repeat(40);
    private static final SkillDownloader.Options DEFAULT = new SkillDownloader.Options(null, null, null, null);

    private DownloadConfig config(DownloadConfig.SkillsShMode mode) {
        return new DownloadConfig(mode, URI.create("https://skills.sh"), () -> "fixture-token");
    }

    private static final class FakeRemote implements RemoteClient {
        final Map<String, byte[]> responses = new HashMap<>();
        final List<String> requested = new ArrayList<>();

        FakeRemote json(String uri, String json) {
            responses.put(uri, json.getBytes(StandardCharsets.UTF_8));
            return this;
        }

        public void fetch(URI uri, Path target, long limit) throws IOException {
            requested.add(uri.toString());
            byte[] content = responses.get(uri.toString());
            if (content == null) throw new IOException("Unexpected request: " + uri);
            if (content.length > limit) throw new IOException("fixture exceeds limit");
            Files.write(target, content);
        }
    }

    private byte[] zip(String path) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry(path));
            zip.write("---\nname: demo\n---\nhello".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return bytes.toByteArray();
    }

    @Test void githubFragmentPinsCommitWithoutMetadataLookup() throws Exception {
        FakeRemote remote = new FakeRemote();
        remote.responses.put("https://api.github.com/repos/org/repo/zipball/" + SHA, zip("root/demo/SKILL.md"));
        var result = new SkillDownloader(config(DownloadConfig.SkillsShMode.SHORTCUT), remote)
                .download(URI.create("https://github.com/org/repo#" + SHA), temporary, DEFAULT);
        assertEquals(SHA, result.revision());
        assertEquals(1, remote.requested.size());
        assertTrue(Files.isRegularFile(result.zipPath()));
    }

    @Test void formalSkillsApiPrecedesGithubResolution() throws Exception {
        FakeRemote remote = new FakeRemote()
                .json("https://skills.sh/api/v1/skills/org/repo/demo", "{\"id\":\"org/repo/demo\",\"source\":\"org/repo\",\"slug\":\"demo\",\"files\":null}")
                .json("https://api.github.com/repos/org/repo", "{\"default_branch\":\"main\"}")
                .json("https://api.github.com/repos/org/repo/commits/main", "{\"sha\":\"" + SHA + "\"}");
        var adapter = new SkillsShSourceAdapter(remote, new GitHubSourceAdapter(remote), config(DownloadConfig.SkillsShMode.API));
        var resolved = adapter.resolve(SkillDownloader.parseSource(URI.create("https://skills.sh/org/repo/demo")), DEFAULT);
        assertInstanceOf(ResolvedSource.GitHub.class, resolved);
        assertEquals("https://skills.sh/api/v1/skills/org/repo/demo", remote.requested.get(0));
        assertEquals(SHA, resolved.revision());
    }

    @Test void inlineSnapshotProducesSkillZip() throws Exception {
        FakeRemote remote = new FakeRemote().json("https://skills.sh/api/v1/skills/example.com/demo", """
                {"id":"example.com/demo","source":"example.com","slug":"demo","hash":"snapshot",
                 "files":[{"path":"SKILL.md","contents":"中文内容"},{"path":"assets/data.txt","contents":"data"}]}
                """);
        var result = new SkillDownloader(config(DownloadConfig.SkillsShMode.API), remote)
                .download(URI.create("https://skills.sh/example.com/demo"), temporary, DEFAULT);
        assertEquals("snapshot", result.revision());
        try (ZipFile zip = new ZipFile(result.zipPath().toFile())) {
            assertEquals(2, zip.size());
            assertEquals("中文内容", new String(zip.getInputStream(zip.getEntry("SKILL.md")).readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    @Test void missingSnapshotDoesNotFallbackToInventedGithub() throws Exception {
        FakeRemote remote = new FakeRemote().json("https://skills.sh/api/v1/skills/example.com/demo",
                "{\"id\":\"example.com/demo\",\"source\":\"example.com\",\"slug\":\"demo\",\"files\":null}");
        var exception = assertThrows(DownloadException.class, () -> new SkillDownloader(config(DownloadConfig.SkillsShMode.API), remote)
                .download(URI.create("https://skills.sh/example.com/demo"), temporary, DEFAULT));
        assertEquals(DownloadException.Code.SOURCE_UNAVAILABLE, exception.code());
        assertEquals(1, remote.requested.size());
        try (var files = Files.list(temporary)) { assertEquals(0, files.count()); }
    }

    @Test void rejectsWrongSkillAndUnknownEncoding() throws Exception {
        for (String payload : List.of(
                "{\"id\":\"wrong\",\"source\":\"example.com\",\"slug\":\"demo\"}",
                "{\"id\":\"example.com/demo\",\"source\":\"example.com\",\"slug\":\"demo\",\"files\":[{\"path\":\"SKILL.md\",\"contents\":\"abc\",\"encoding\":\"base64\"}]}")) {
            FakeRemote remote = new FakeRemote().json("https://skills.sh/api/v1/skills/example.com/demo", payload);
            assertThrows(DownloadException.class, () -> new SkillDownloader(config(DownloadConfig.SkillsShMode.API), remote)
                    .download(URI.create("https://skills.sh/example.com/demo"), temporary, DEFAULT));
        }
    }

    @Test void clawhubHandoffUsesPinnedRepoAndPath() throws Exception {
        FakeRemote remote = new FakeRemote()
                .json("https://clawhub.ai/api/v1/skills/demo?ownerHandle=author", "{\"latestVersion\":{\"version\":\"1.0\"}}")
                .json("https://clawhub.ai/api/v1/download?slug=demo&ownerHandle=author&version=1.0",
                        "{\"sourceRef\":\"public-github\",\"repo\":\"org/repo\",\"commit\":\"" + SHA + "\",\"path\":\"skills/demo\",\"archiveUrl\":\"https://evil.invalid/file.zip\"}");
        remote.responses.put("https://api.github.com/repos/org/repo/zipball/" + SHA, zip("root/skills/demo/SKILL.md"));
        var result = new SkillDownloader(config(DownloadConfig.SkillsShMode.SHORTCUT), remote)
                .download(URI.create("https://clawhub.ai/author/skills/demo"), temporary, DEFAULT);
        assertEquals("skills/demo", result.skillPath());
        assertEquals(SHA, result.revision());
        assertFalse(remote.requested.stream().anyMatch(uri -> uri.contains("evil.invalid")));
    }

    @Test void clawhubHostedZipDoesNotContactGithub() throws Exception {
        FakeRemote remote = new FakeRemote().json("https://clawhub.ai/api/v1/skills/demo?ownerHandle=author", "{\"latestVersion\":{\"version\":\"1.0\"}}");
        remote.responses.put("https://clawhub.ai/api/v1/download?slug=demo&ownerHandle=author&version=1.0", zip("SKILL.md"));
        new SkillDownloader(config(DownloadConfig.SkillsShMode.SHORTCUT), remote)
                .download(URI.create("https://clawhub.ai/author/demo"), temporary, DEFAULT);
        assertEquals(2, remote.requested.size());
        assertTrue(remote.requested.stream().allMatch(uri -> uri.contains("clawhub.ai")));
    }

    @Test void rejectsUnsafeHandoff() throws Exception {
        var mapper = new ObjectMapper();
        assertThrows(IOException.class, () -> ClawHubSourceAdapter.handoff(mapper.readTree("{\"sourceRef\":\"unknown\"}"), "demo"));
        assertThrows(IOException.class, () -> ClawHubSourceAdapter.handoff(mapper.readTree("{\"sourceRef\":\"public-github\",\"repo\":\"org/repo\",\"commit\":\"main\",\"path\":\"demo\"}"), "demo"));
    }

    @Test void rejectsInlineTraversalDuplicatesAndMissingSkill() {
        for (var files : List.of(
                List.of(new ResolvedSource.InlineFile("../escape", "bad")),
                List.of(new ResolvedSource.InlineFile("SKILL.md", "one"), new ResolvedSource.InlineFile("SKILL.md", "two")),
                List.of(new ResolvedSource.InlineFile("README.md", "not a skill")))) {
            assertThrows(IOException.class, () -> SkillZipService.packInline(new ResolvedSource.InlineFiles(files, "hash", "demo"), temporary.resolve("bad.zip")));
        }
    }

    @Test void tokenFileReloadsAndMissingTokenFailsBeforeNetwork() throws Exception {
        Path file = temporary.resolve("token");
        Files.writeString(file, "first");
        TokenProvider provider = TokenProvider.rotatingFile(file);
        assertEquals("first", provider.token());
        Files.writeString(file, "second");
        assertEquals("second", provider.refresh());
        DownloadConfig config = new DownloadConfig(DownloadConfig.SkillsShMode.API, URI.create("https://skills.sh"), () -> null);
        var exception = assertThrows(DownloadException.class, () -> new HttpFileDownloader(config)
                .fetch(URI.create("https://skills.sh/api/v1/skills/org/repo/demo"), temporary.resolve("response"), 100));
        assertEquals(DownloadException.Code.AUTH_REQUIRED, exception.code());
    }

    @Test void respectsRetryAfterAndClassifiesErrors() {
        Instant now = Instant.parse("2026-10-27T00:00:00Z");
        assertEquals(3000, HttpFileDownloader.retryDelayMillis("3", 0, now));
        assertEquals(5000, HttpFileDownloader.retryDelayMillis("Tue, 27 Oct 2026 00:00:05 GMT", 0, now));
        assertEquals(1000, HttpFileDownloader.retryDelayMillis("invalid", 1, now));
        assertEquals(Long.MAX_VALUE, HttpFileDownloader.retryDelayMillis("9223372036854775807", 0, now));
        assertEquals(DownloadException.Code.FORBIDDEN, DownloadException.http(403, "example").code());
        assertEquals(DownloadException.Code.RATE_LIMITED, DownloadException.http(429, "example").code());
    }

    @Test void configRejectsInvalidModeAndUnsafeGateway() {
        assertThrows(IllegalArgumentException.class, () -> DownloadConfig.fromEnvironment(Map.of("SKILLS_SH_MODE", "typo")));
        assertThrows(IllegalArgumentException.class, () -> new DownloadConfig(DownloadConfig.SkillsShMode.API, URI.create("http://localhost"), () -> "token"));
    }
}
