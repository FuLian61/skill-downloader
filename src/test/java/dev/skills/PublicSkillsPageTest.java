package dev.skills;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.net.URI;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.zip.*;
import static org.junit.jupiter.api.Assertions.*;

class PublicSkillsPageTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    @TempDir Path temporary;

    private Map<String, Object> skill(String source, String slug) {
        return Map.of("source", source, "skillId", slug, "name", "技能 \"名称\"", "installs", 100);
    }

    private String record(String view, List<Map<String, Object>> entries) throws Exception {
        return "56:" + JSON.writeValueAsString(List.of("$", "$L64", "placeholder",
                Map.of("initialSkills", entries, "view", view, "totalSkills", entries.size()))) + "\n";
    }

    private String script(String payload) throws Exception {
        return "<script>self.__next_f.push(" + JSON.writeValueAsString(List.of(1, payload)) + ")</script>";
    }

    private SyncConfig config(String view, int top) {
        return new SyncConfig(temporary, top, Duration.ofHours(6), 0, List.of("skills.sh"), view, "trending");
    }

    private DownloadConfig downloadConfig() {
        return new DownloadConfig(DownloadConfig.SkillsShMode.API, URI.create("https://proxy.example/base/"),
                () -> { throw new AssertionError("public mode must not need a token"); });
    }

    @Test void parsesCompleteTopFiftyInsteadOfVisibleGroupedLinks() throws Exception {
        List<Map<String, Object>> entries = new ArrayList<>();
        for (int rank = 1; rank <= 60; rank++) entries.add(skill("owner/repo", "skill-" + rank));
        List<URI> requests = new ArrayList<>();
        String html = "<a href='/wrong/repo/skill'>fake visible link</a>" + script(record("trending", entries));
        RemoteClient remote = (uri, output, limit) -> { requests.add(uri); Files.writeString(output, html); };
        var result = new HotSkillCatalog(remote, downloadConfig(), config("trending", 50)).list("skills.sh");
        assertEquals(List.of(URI.create("https://skills.sh/trending")), requests);
        assertEquals(50, result.entries().size());
        assertEquals("owner/repo/skill-50", result.entries().get(49).id());
        assertEquals(50, result.entries().get(49).rank());
        assertEquals("技能 \"名称\"", result.entries().get(0).name());
    }

    @Test void joinsFlightChunksBeforeParsing() throws Exception {
        String record = record("trending", List.of(skill("owner/repo", "demo")));
        int split = record.indexOf("initialSkills") + 5;
        String html = script(record.substring(0, split)) + "<p>ignored</p>" + script(record.substring(split));
        assertEquals("demo", PublicSkillsPage.parse(html, "trending").get(0).path("skillId").asText());
    }

    @Test void rejectsMissingMalformedAmbiguousAndWrongViewData() throws Exception {
        String payload = record("trending", List.of(skill("owner/repo", "demo")));
        for (String html : List.of("<html>login or challenge</html>", script(payload) + script(payload),
                script("56:{\"initialSkills\":broken}\n"), "<script>self.__next_f.push([1,runCode()])</script>")) {
            assertEquals(DownloadException.Code.INVALID_RESPONSE,
                    assertThrows(DownloadException.class, () -> PublicSkillsPage.parse(html, "trending")).code());
        }
        assertThrows(DownloadException.class, () -> PublicSkillsPage.parse(script(payload), "hot"));
    }

    @Test void usesCorrectPublicUrlForEveryView() throws Exception {
        for (String view : List.of("trending", "hot", "all-time")) {
            String html = script(record(view, List.of(skill("owner/repo", "demo"))));
            RemoteClient remote = (uri, output, limit) -> {
                assertEquals("https://skills.sh/" + (view.equals("all-time") ? "" : view), uri.toString());
                Files.writeString(output, html);
            };
            assertEquals(view, new HotSkillCatalog(remote, downloadConfig(), config(view, 1)).list("skills.sh").ranking());
        }
    }

    @Test void preservesNonGithubRankingAndReportsInsufficientCount() throws Exception {
        String html = script(record("trending", List.of(skill("example.com", "demo"), skill("owner/repo", "other"))));
        RemoteClient remote = (uri, output, limit) -> Files.writeString(output, html);
        var result = new HotSkillCatalog(remote, downloadConfig(), config("trending", 50)).list("skills.sh");
        assertEquals("example.com/demo", result.entries().get(0).id());
        assertEquals(2, result.entries().get(1).rank());
        assertFalse(result.notice().isBlank());
    }

    @Test void rejectsDuplicatesAndHostilePaths() throws Exception {
        for (List<Map<String, Object>> entries : List.of(
                List.of(skill("owner/repo", "demo"), skill("owner/repo", "demo")),
                List.of(skill("owner/repo", "../escape")), List.of(skill("owner/repo", "demo?query=1")))) {
            String html = script(record("trending", entries));
            RemoteClient remote = (uri, output, limit) -> Files.writeString(output, html);
            assertThrows(IOException.class, () -> new HotSkillCatalog(remote, downloadConfig(), config("trending", 50)).list("skills.sh"));
        }
    }

    @Test void publicConfigurationIsDefaultButApiCanBeSelected() throws Exception {
        assertEquals("public", SyncConfig.load(null).skillsCatalog());
        Path file = temporary.resolve("settings");
        Files.writeString(file, "skills.catalog=api\n");
        assertEquals("api", SyncConfig.load(file).skillsCatalog());
        Files.writeString(file, "skills.catalog=unknown\n");
        assertThrows(IllegalArgumentException.class, () -> SyncConfig.load(file));
    }

    @Test void publicModeDownloadsViaGithubAndIsolatesNonGithubWithoutApiCalls() throws Exception {
        String html = script(record("trending", List.of(skill("example.com", "demo"), skill("owner/repo", "demo"))));
        String sha = "a".repeat(40);
        List<String> requests = new ArrayList<>();
        RemoteClient remote = (uri, output, limit) -> {
            String url = uri.toString();
            requests.add(url);
            if (url.equals("https://skills.sh/trending")) Files.writeString(output, html);
            else if (url.equals("https://api.github.com/repos/owner/repo")) Files.writeString(output, "{\"default_branch\":\"main\"}");
            else if (url.equals("https://api.github.com/repos/owner/repo/commits/main")) Files.writeString(output, "{\"sha\":\"" + sha + "\"}");
            else if (url.equals("https://api.github.com/repos/owner/repo/zipball/" + sha)) {
                try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(output))) {
                    zip.putNextEntry(new ZipEntry("root/demo/SKILL.md"));
                    zip.write("---\nname: demo\n---\nhello".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    zip.closeEntry();
                }
            } else throw new AssertionError("Unexpected request: " + url);
        };
        var report = new SkillSyncService(config("trending", 2), downloadConfig(), remote).runOnce(false);
        assertEquals("PARTIAL", report.status());
        var items = report.platforms().get(0).items();
        assertEquals("SOURCE_UNAVAILABLE", items.get(0).errorCode());
        assertEquals("DOWNLOADED", items.get(1).status());
        assertTrue(Files.isRegularFile(temporary.resolve(items.get(1).file())));
        assertEquals(4, requests.size());
        assertTrue(requests.stream().noneMatch(url -> url.contains("/api/v1/")));
    }
}
