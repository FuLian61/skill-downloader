package dev.skills;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.net.URI;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.*;
import static org.junit.jupiter.api.Assertions.*;

class SkillSyncTest {
    @TempDir Path temporary;

    private SyncConfig config(int top, String sort) {
        return new SyncConfig(temporary, top, Duration.ofHours(6), 0,
                List.of("skills.sh", "clawhub"), "trending", sort, "api");
    }

    private HotSkillCatalog catalog(SyncConfig config, List<String> requests, String... responses) {
        AtomicInteger index = new AtomicInteger();
        RemoteClient remote = (uri, target, limit) -> {
            requests.add(uri.toString());
            if (index.get() >= responses.length) throw new IOException("Unexpected request");
            Files.writeString(target, responses[index.getAndIncrement()]);
        };
        return new HotSkillCatalog(remote, new DownloadConfig(DownloadConfig.SkillsShMode.API,
                URI.create("https://skills.sh"), () -> "test-token"), config);
    }

    private String skill(String source, String slug, String type) {
        return "{\"id\":\"" + source + "/" + slug + "\",\"source\":\"" + source
                + "\",\"slug\":\"" + slug + "\",\"sourceType\":\"" + type + "\"}";
    }

    private String claw(String owner, String slug) {
        return "{\"ownerHandle\":\"" + owner + "\",\"slug\":\"" + slug
                + "\",\"latestVersion\":{\"version\":\"1.0.0\"}}";
    }

    @Test void defaultsAndInvalidConfiguration() throws Exception {
        assertEquals(50, SyncConfig.load(null).top());
        assertEquals(Duration.ofHours(6), SyncConfig.load(null).interval());
        assertThrows(IllegalArgumentException.class, () -> config(51, "trending"));
        assertThrows(IllegalArgumentException.class, () -> config(1, "unknown"));
        Path file = temporary.resolve("config.properties");
        Files.writeString(file, "top=2\nplatforms=clawhub\ninterval.minutes=10\n");
        assertEquals(List.of("clawhub"), SyncConfig.load(file).platforms());
        Files.writeString(file, "token=secret\n");
        assertThrows(IllegalArgumentException.class, () -> SyncConfig.load(file));
    }

    @Test void skillsPaginationDeduplicatesAndSupportsWellKnown() throws Exception {
        List<String> requests = new ArrayList<>();
        String first = skill("org/repo", "demo", "github");
        var snapshot = catalog(config(2, "trending"), requests,
                "{\"data\":[" + first + "],\"pagination\":{\"hasMore\":true}}",
                "{\"data\":[" + first + "," + skill("example.com", "other", "well-known")
                        + "],\"pagination\":{\"hasMore\":false}}").list("skills.sh");
        assertEquals(2, snapshot.entries().size());
        assertTrue(requests.get(0).endsWith("view=trending&page=0&per_page=2"));
        assertTrue(requests.get(1).contains("page=1"));
        assertEquals("https://skills.sh/example.com/other", snapshot.entries().get(1).sourceUrl());
        assertEquals(2, snapshot.entries().get(1).rank());
    }

    @Test void invalidAndStalledSkillsPagesFail() {
        assertThrows(IOException.class, () -> catalog(config(2, "trending"), new ArrayList<>(),
                "{\"data\":[],\"pagination\":{\"hasMore\":true}}").list("skills.sh"));
        assertThrows(IOException.class, () -> catalog(config(2, "trending"), new ArrayList<>(),
                "{\"data\":[]}").list("skills.sh"));
        assertThrows(IOException.class, () -> catalog(config(2, "trending"), new ArrayList<>(),
                "{\"data\":[" + skill("org/repo", "../bad", "github")
                        + "],\"pagination\":{\"hasMore\":false}}").list("skills.sh"));
    }

    @Test void trendingNeverFollowsCursorAndPreservesOwner() throws Exception {
        List<String> requests = new ArrayList<>();
        var snapshot = catalog(config(50, "trending"), requests,
                "{\"items\":[" + claw("alice", "demo") + "," + claw("bob", "demo")
                        + "],\"nextCursor\":\"ignored\"}").list("clawhub");
        assertEquals(1, requests.size());
        assertTrue(requests.get(0).endsWith("sort=trending&limit=50"));
        assertEquals(2, snapshot.entries().size());
        assertFalse(snapshot.notice().isBlank());
        assertEquals("https://clawhub.ai/bob/skills/demo", snapshot.entries().get(1).sourceUrl());
        assertEquals("1.0.0", snapshot.entries().get(0).version());
    }

    @Test void downloadsPaginationHandlesEmptyPageAndRepeatedCursor() throws Exception {
        List<String> requests = new ArrayList<>();
        var snapshot = catalog(config(1, "downloads"), requests,
                "{\"items\":[],\"nextCursor\":\"abc\"}",
                "{\"items\":[" + claw("alice", "demo") + "],\"nextCursor\":null}").list("clawhub");
        assertEquals(1, snapshot.entries().size());
        assertTrue(requests.get(1).endsWith("&cursor=abc"));
        assertThrows(IOException.class, () -> catalog(config(2, "downloads"), new ArrayList<>(),
                "{\"items\":[],\"nextCursor\":\"abc\"}",
                "{\"items\":[],\"nextCursor\":\"abc\"}").list("clawhub"));
    }

    private HotSkillCatalog.Snapshot snapshot(String platform, int count) {
        List<HotSkillCatalog.Entry> entries = new ArrayList<>();
        for (int index = 1; index <= count; index++) {
            String id = "owner/demo" + index;
            entries.add(new HotSkillCatalog.Entry(platform, id, index, "demo",
                    "https://clawhub.ai/owner/skills/demo" + index, "1.0.0"));
        }
        return new HotSkillCatalog.Snapshot(platform, "trending", count, entries, "");
    }

    private SkillDownloader.Result archive(Path output, String content) throws IOException {
        Path zip = output.resolve("demo.zip");
        try (ZipOutputStream stream = new ZipOutputStream(Files.newOutputStream(zip))) {
            ZipEntry entry = new ZipEntry("SKILL.md");
            entry.setTime(0);
            stream.putNextEntry(entry);
            stream.write(content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            stream.closeEntry();
        }
        return new SkillDownloader.Result(zip, "fixture", "1.0.0", "", SkillSyncService.hashFile(zip));
    }

    @Test void catalogOnlyListsFiftyPerPlatformWithoutDownloading() throws Exception {
        var service = new SkillSyncService(config(50, "trending"), platform -> snapshot(platform, 50),
                (uri, output, options) -> { fail("must not download"); return null; }, millis -> {});
        var result = service.runOnce(true);
        assertEquals("SUCCESS", result.status());
        assertEquals(100, result.platforms().stream().mapToInt(platform -> platform.items().size()).sum());
        assertTrue(Files.exists(temporary.resolve("latest-run.json")));
    }

    @Test void authenticationFailureDoesNotBlockOtherPlatformOrLeakToken() throws Exception {
        var service = new SkillSyncService(config(1, "trending"), platform -> {
            if (platform.equals("skills.sh")) throw new DownloadException(DownloadException.Code.AUTH_REQUIRED, "secret-token");
            return snapshot(platform, 1);
        }, (uri, output, options) -> {
            assertEquals("1.0.0", options.version());
            return archive(output, "hello");
        }, millis -> {});
        var result = service.runOnce(false);
        assertEquals("PARTIAL", result.status());
        assertEquals("AUTH_REQUIRED", result.platforms().get(0).errorCode());
        assertEquals("SUCCESS", result.platforms().get(1).status());
        assertFalse(Files.readString(temporary.resolve("latest-run.json")).contains("secret-token"));
    }

    @Test void duplicateArchivesAreReusedAndCorruptionIsRepaired() throws Exception {
        var service = new SkillSyncService(config(1, "trending"), platform -> snapshot(platform, 1),
                (uri, output, options) -> archive(output, "hello"), millis -> {});
        var first = service.runOnce(false).platforms().get(0).items().get(0);
        assertEquals("DOWNLOADED", first.status());
        assertEquals("UNCHANGED", service.runOnce(false).platforms().get(0).items().get(0).status());
        Path archive = temporary.resolve(first.file());
        Files.writeString(archive, "corrupted");
        assertEquals("DOWNLOADED", service.runOnce(false).platforms().get(0).items().get(0).status());
        assertEquals(first.sha256(), SkillSyncService.hashFile(archive));
        try (var staging = Files.list(temporary.resolve("staging"))) { assertEquals(0, staging.count()); }
    }

    @Test void failedItemDoesNotStopFollowingItems() throws Exception {
        var service = new SkillSyncService(config(2, "trending"), platform -> snapshot(platform, 2),
                (uri, output, options) -> {
                    if (uri.toString().endsWith("demo1")) throw new IOException("secret");
                    return archive(output, "hello");
                }, millis -> {});
        var result = service.runOnce(false);
        assertEquals("PARTIAL", result.status());
        assertEquals("FAILED", result.platforms().get(0).items().get(0).status());
        assertEquals("DOWNLOADED", result.platforms().get(0).items().get(1).status());
        assertFalse(Files.readString(temporary.resolve("latest-run.json")).contains("secret"));
    }

    @Test void changedContentKeepsHistoryAndFailurePreservesManifest() throws Exception {
        AtomicInteger generation = new AtomicInteger();
        var service = new SkillSyncService(config(1, "trending"), platform -> snapshot(platform, 1),
                (uri, output, options) -> {
                    if (generation.get() == 2) throw new IOException("download failed");
                    return archive(output, "content-" + generation.get());
                }, millis -> {});
        var first = service.runOnce(false).platforms().get(0).items().get(0);
        generation.set(1);
        var second = service.runOnce(false).platforms().get(0).items().get(0);
        assertNotEquals(first.file(), second.file());
        assertTrue(Files.exists(temporary.resolve(first.file())));
        Path manifest = temporary.resolve(second.file()).resolveSibling("latest.json");
        String previous = Files.readString(manifest);
        generation.set(2);
        assertEquals("PARTIAL", service.runOnce(false).status());
        assertEquals(previous, Files.readString(manifest));
    }

    @Test void outputLockRejectsOverlappingRunAndReleasesAfterInterruption() throws Exception {
        var other = new SkillSyncService(config(1, "trending"), platform -> snapshot(platform, 1),
                (uri, output, options) -> archive(output, "hello"), millis -> {});
        var interrupted = new SkillSyncService(config(1, "trending"), platform -> {
            assertThrows(IOException.class, () -> other.runOnce(true));
            throw new InterruptedException();
        }, (uri, output, options) -> archive(output, "hello"), millis -> {});
        assertThrows(InterruptedException.class, () -> interrupted.runOnce(true));
        assertTrue(Files.readString(temporary.resolve("latest-run.json")).contains("INTERRUPTED"));
        assertEquals("SUCCESS", other.runOnce(true).status());
    }

    @Test void shortCatalogIsNotReportedAsSuccess() throws Exception {
        var service = new SkillSyncService(config(50, "trending"), platform -> snapshot(platform, 1),
                (uri, output, options) -> archive(output, "hello"), millis -> {});
        var result = service.runOnce(true);
        assertEquals("PARTIAL", result.status());
        assertEquals("INCOMPLETE", result.platforms().get(0).status());
        assertEquals(1, result.platforms().get(0).listed());
    }
}
