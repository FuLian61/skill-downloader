package dev.skills;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.net.URI;
import java.util.*;

final class HotSkillCatalog {
    record Entry(String platform, String id, int rank, String name, String sourceUrl, String version) {}
    record Snapshot(String platform, String ranking, int requested, List<Entry> entries, String notice) {
        Snapshot { entries = List.copyOf(entries); }
    }

    private final RemoteClient remote;
    private final DownloadConfig downloadConfig;
    private final SyncConfig syncConfig;

    HotSkillCatalog(RemoteClient remote, DownloadConfig downloadConfig, SyncConfig syncConfig) {
        this.remote = remote;
        this.downloadConfig = downloadConfig;
        this.syncConfig = syncConfig;
    }

    Snapshot list(String platform) throws IOException, InterruptedException {
        return switch (platform) {
            case "skills.sh" -> syncConfig.skillsCatalog().equals("public") ? publicSkills() : skills();
            case "clawhub" -> clawhub();
            default -> throw new IllegalArgumentException("不支持的榜单来源");
        };
    }

    private Snapshot publicSkills() throws IOException, InterruptedException {
        String path = syncConfig.skillsView().equals("all-time") ? "/" : "/" + syncConfig.skillsView();
        java.nio.file.Path temporary = java.nio.file.Files.createTempFile("skills-public-", ".html");
        JsonNode data;
        try {
            remote.fetch(URI.create("https://skills.sh" + path), temporary, 16L * 1024 * 1024);
            data = PublicSkillsPage.parse(java.nio.file.Files.readString(temporary), syncConfig.skillsView());
        } finally { java.nio.file.Files.deleteIfExists(temporary); }
        LinkedHashMap<String, Entry> entries = new LinkedHashMap<>();
        for (JsonNode item : data) {
            String source = text(item, "source");
            String slug = text(item, "skillId");
            String id = source + "/" + slug;
            String url = "https://skills.sh/" + id;
            SkillDownloader.Source parsed = validated(url, "skills.sh");
            if (!source.equals(parsed.repo()) || !slug.equals(parsed.slug())) throw invalid("公开榜单技能路径无效");
            if (entries.containsKey(id)) throw invalid("公开榜单包含重复技能，无法确认排名");
            entries.put(id, new Entry("skills.sh", id, entries.size() + 1, item.path("name").asText(slug), url, null));
            if (entries.size() == syncConfig.top()) break;
        }
        return snapshot("skills.sh", syncConfig.skillsView(), entries);
    }

    private Snapshot skills() throws IOException, InterruptedException {
        LinkedHashMap<String, Entry> entries = new LinkedHashMap<>();
        for (int page = 0; page < 20; page++) {
            JsonNode response = remote.json(downloadConfig.skillsShApiBase().resolve("api/v1/skills?view=" + syncConfig.skillsView()
                    + "&page=" + page + "&per_page=" + syncConfig.top()));
            JsonNode data = array(response, "data");
            boolean hasMore = bool(response.path("pagination"), "hasMore");
            int before = entries.size();
            for (JsonNode item : data) {
                String id = text(item, "id");
                if (entries.containsKey(id)) continue;
                String source = text(item, "source");
                String slug = text(item, "slug");
                if (!id.equals(source + "/" + slug)) throw invalid("skills.sh 榜单 ID 与来源不匹配");
                String type = text(item, "sourceType");
                if (!(type.equals("github") && source.split("/", -1).length == 2)
                        && !(type.equals("well-known") && !source.contains("/"))) throw invalid("skills.sh 返回不支持的来源类型");
                String url = "https://skills.sh/" + id;
                SkillDownloader.Source parsed = validated(url, "skills.sh");
                if (!source.equals(parsed.repo()) || !slug.equals(parsed.slug())) throw invalid("skills.sh 来源路径不匹配");
                entries.put(id, new Entry("skills.sh", id, entries.size() + 1, item.path("name").asText(slug), url, null));
                if (entries.size() == syncConfig.top()) return snapshot("skills.sh", syncConfig.skillsView(), entries);
            }
            if (!hasMore) return snapshot("skills.sh", syncConfig.skillsView(), entries);
            if (entries.size() == before) throw invalid("skills.sh 分页未推进，终止重复请求");
        }
        throw invalid("skills.sh 分页超过安全上限 20 页");
    }

    private Snapshot clawhub() throws IOException, InterruptedException {
        LinkedHashMap<String, Entry> entries = new LinkedHashMap<>();
        Set<String> cursors = new HashSet<>();
        String cursor = null;
        for (int page = 0; page < 20; page++) {
            String endpoint = "https://clawhub.ai/api/v1/skills?sort=" + syncConfig.clawhubSort() + "&limit=" + syncConfig.top()
                    + (cursor == null ? "" : "&cursor=" + SkillDownloader.encode(cursor));
            JsonNode response = remote.json(URI.create(endpoint));
            for (JsonNode item : array(response, "items")) {
                String owner = text(item, "ownerHandle");
                String slug = text(item, "slug");
                String id = owner + "/" + slug;
                if (entries.containsKey(id)) continue;
                String url = "https://clawhub.ai/" + owner + "/skills/" + slug;
                SkillDownloader.Source parsed = validated(url, "clawhub");
                if (!owner.equals(parsed.owner()) || !slug.equals(parsed.slug())) throw invalid("ClawHub 来源路径不匹配");
                String version = item.path("latestVersion").path("version").asText("");
                entries.put(id, new Entry("clawhub", id, entries.size() + 1, item.path("displayName").asText(slug), url,
                        version.isBlank() ? null : version));
                if (entries.size() == syncConfig.top()) return snapshot("clawhub", syncConfig.clawhubSort(), entries);
            }
            if (syncConfig.clawhubSort().equals("trending")) return snapshot("clawhub", "trending", entries);
            JsonNode next = response.get("nextCursor");
            if (next == null || next.isNull() || (next.isTextual() && next.asText().isBlank())) return snapshot("clawhub", syncConfig.clawhubSort(), entries);
            if (!next.isTextual() || next.asText().length() > 8192) throw invalid("ClawHub 分页游标格式错误");
            cursor = next.textValue();
            if (!cursors.add(cursor)) throw invalid("ClawHub 分页游标重复");
        }
        throw invalid("ClawHub 分页超过安全上限 20 页");
    }

    private Snapshot snapshot(String platform, String ranking, Map<String, Entry> entries) {
        return new Snapshot(platform, ranking, syncConfig.top(), List.copyOf(entries.values()), entries.size() < syncConfig.top()
                ? "平台本次返回不足目标数量，不使用其他排序或重复条目凑数" : "");
    }

    private static SkillDownloader.Source validated(String url, String platform) throws IOException {
        try {
            SkillDownloader.Source parsed = SkillDownloader.parseSource(URI.create(url));
            if (!parsed.platform().equals(platform)) throw invalid("榜单链接来源不匹配");
            return parsed;
        } catch (IllegalArgumentException invalid) {
            throw invalid("榜单链接格式无效");
        }
    }

    private static String text(JsonNode node, String name) throws IOException {
        String value = SkillDownloader.required(node, name);
        if (value.length() > 2048 || value.chars().anyMatch(character -> character <= 32 || character >= 127)) throw invalid("榜单标识字段无效: " + name);
        return value;
    }

    private static JsonNode array(JsonNode node, String field) throws IOException {
        if (!node.path(field).isArray()) throw invalid("榜单缺少数组: " + field);
        return node.path(field);
    }

    private static boolean bool(JsonNode node, String field) throws IOException {
        if (!node.path(field).isBoolean()) throw invalid("榜单缺少布尔字段: " + field);
        return node.path(field).booleanValue();
    }

    private static DownloadException invalid(String message) {
        return new DownloadException(DownloadException.Code.INVALID_RESPONSE, message);
    }
}
