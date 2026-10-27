package dev.skills;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.net.URI;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import static org.junit.jupiter.api.Assertions.*;

class SkillDownloaderTest {
    @TempDir Path temporary;

    private Path archive(Map<String, String> files) throws IOException {
        Path source = temporary.resolve("source.zip");
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(source))) {
            for (var item : files.entrySet()) {
                output.putNextEntry(new ZipEntry(item.getKey()));
                output.write(item.getValue().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                output.closeEntry();
            }
        }
        return source;
    }

    @Test void parsesSupportedSources() throws Exception {
        assertEquals("skills.sh", SkillDownloader.parseSource(URI.create("https://www.skills.sh/mattpocock/skills/improve-codebase-architecture")).platform());
        assertEquals("mattpocock/skills", SkillDownloader.parseSource(URI.create("https://www.skills.sh/mattpocock/skills/improve-codebase-architecture")).repo());
        assertThrows(IOException.class, () -> SkillDownloader.parseSource(URI.create("https://www.skills.sh.evil.test/org/repo/demo")));
        assertEquals("find-skills", SkillDownloader.parseSource(URI.create("https://skills.sh/vercel-labs/skills/find-skills")).slug());
        assertEquals("skills/demo", SkillDownloader.parseSource(URI.create("https://github.com/org/repo/tree/main/skills/demo")).path());
        assertEquals("demo", SkillDownloader.parseSource(URI.create("https://clawhub.ai/owner/skills/demo")).slug());
        assertEquals("owner", SkillDownloader.parseSource(URI.create("https://clawhub.ai/owner/skills/demo")).owner());
        assertEquals("demo", SkillDownloader.parseSource(URI.create("https://clawhub.com/owner/demo")).slug());
    }

    @Test void rejectsUntrustedSources() {
        for (String url : List.of("http://github.com/org/repo", "https://github.com.evil.test/org/repo", "https://user@github.com/org/repo", "https://github.com:8443/org/repo")) {
            assertThrows(IOException.class, () -> SkillDownloader.parseSource(URI.create(url)));
        }
    }

    @Test void extractsOnlySelectedSkill() throws Exception {
        Path source = archive(Map.of("repo-sha/skills/demo/SKILL.md", "---\nname: demo\n---\n说明", "repo-sha/skills/demo/assets/data.txt", "data", "repo-sha/README.md", "unrelated"));
        Path target = temporary.resolve("result.zip");
        assertEquals("skills/demo", SkillDownloader.repack(source, target, true, null, "demo"));
        try (ZipFile zip = new ZipFile(target.toFile())) {
            assertNotNull(zip.getEntry("SKILL.md"));
            assertNotNull(zip.getEntry("assets/data.txt"));
            assertEquals(2, zip.size());
        }
    }

    @Test void discoversByFrontmatterName() throws Exception {
        Path source = archive(Map.of("repo-sha/other/SKILL.md", "---\nname: 'demo'\n---\nhello"));
        assertEquals("other", SkillDownloader.repack(source, temporary.resolve("result.zip"), true, null, "demo"));
    }

    @Test void supportsHostedZipRoot() throws Exception {
        Path source = archive(Map.of("SKILL.md", "hello", "scripts/run.sh", "not executed"));
        assertEquals("", SkillDownloader.repack(source, temporary.resolve("result.zip"), false, null, null));
    }

    @Test void rejectsAmbiguousSkills() throws Exception {
        Path source = archive(Map.of("root/one/SKILL.md", "one", "root/two/SKILL.md", "two"));
        assertThrows(IOException.class, () -> SkillDownloader.repack(source, temporary.resolve("result.zip"), true, null, null));
        assertEquals("two", SkillDownloader.repack(source, temporary.resolve("result.zip"), true, "two", null));
    }

    @Test void rejectsZipSlip() throws Exception {
        Path source = archive(Map.of("root/../escape", "bad", "root/SKILL.md", "hello"));
        assertThrows(IOException.class, () -> SkillDownloader.repack(source, temporary.resolve("result.zip"), true, null, null));
        for (String name : List.of("/absolute", "C:/bad", "a\\b", "a/./b", "a//b")) {
            assertThrows(IOException.class, () -> SkillDownloader.safeEntry(name));
        }
    }

    @Test void rejectsHtmlAndMissingSkill() throws Exception {
        Path html = temporary.resolve("html.zip");
        Files.writeString(html, "<html>not a ZIP</html>");
        assertThrows(IOException.class, () -> SkillDownloader.repack(html, temporary.resolve("result.zip"), false, null, null));
        Path source = archive(Map.of("README.md", "no skill"));
        assertThrows(IOException.class, () -> SkillDownloader.repack(source, temporary.resolve("result.zip"), false, null, null));
    }

    @Test void boundsStreamingCopy() {
        assertThrows(IOException.class, () -> SkillDownloader.copyLimited(new ByteArrayInputStream(new byte[100]), OutputStream.nullOutputStream(), 99));
    }

    @Test void rejectsRedirectToLocalhostWithoutNetwork() {
        assertThrows(IOException.class, () -> SkillDownloader.validateDownloadUri(URI.create("https://127.0.0.1/private")));
        assertThrows(IOException.class, () -> SkillDownloader.validateDownloadUri(URI.create("http://github.com/org/repo")));
    }
}
