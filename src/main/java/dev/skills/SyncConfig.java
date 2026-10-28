package dev.skills;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;

public record SyncConfig(Path outputDirectory, int top, Duration interval, long itemDelayMillis,
                         List<String> platforms, String skillsView, String clawhubSort, String skillsCatalog) {
    public SyncConfig(Path outputDirectory, int top, Duration interval, long itemDelayMillis,
                      List<String> platforms, String skillsView, String clawhubSort) {
        this(outputDirectory, top, interval, itemDelayMillis, platforms, skillsView, clawhubSort, "public");
    }

    public SyncConfig {
        Objects.requireNonNull(outputDirectory);
        Objects.requireNonNull(interval);
        platforms = List.copyOf(platforms);
        outputDirectory = outputDirectory.toAbsolutePath().normalize();
        if (top < 1 || top > 50) throw new IllegalArgumentException("top 必须在 1 到 50 之间");
        if (interval.compareTo(Duration.ofMinutes(1)) < 0 || interval.compareTo(Duration.ofDays(365)) > 0) {
            throw new IllegalArgumentException("interval.minutes 必须在 1 到 525600 之间");
        }
        if (itemDelayMillis < 0 || itemDelayMillis > 60000) throw new IllegalArgumentException("item.delay.millis 必须在 0 到 60000 之间");
        if (platforms.isEmpty() || new HashSet<>(platforms).size() != platforms.size()
                || !Set.of("skills.sh", "clawhub").containsAll(platforms)) throw new IllegalArgumentException("platforms 只能包含不重复的 skills.sh、clawhub");
        if (!Set.of("trending", "hot", "all-time").contains(skillsView)) throw new IllegalArgumentException("skills.view 只支持 trending、hot、all-time");
        if (!Set.of("trending", "downloads", "stars").contains(clawhubSort)) throw new IllegalArgumentException("clawhub.sort 只支持 trending、downloads、stars");
        if (!Set.of("public", "api").contains(skillsCatalog)) throw new IllegalArgumentException("skills.catalog 只支持 public、api");
    }

    public static SyncConfig load(Path file) throws IOException {
        Properties properties = new Properties();
        if (file != null) {
            try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) { properties.load(reader); }
        }
        Set<String> allowed = Set.of("output.directory", "top", "interval.minutes", "item.delay.millis", "platforms", "skills.view", "clawhub.sort", "skills.catalog");
        if (!allowed.containsAll(properties.stringPropertyNames())) throw new IllegalArgumentException("同步配置包含未知字段；不要将 Token 放入此配置文件");
        return new SyncConfig(Path.of(properties.getProperty("output.directory", "sync-data").strip()),
                Integer.parseInt(properties.getProperty("top", "50").strip()),
                Duration.ofMinutes(Long.parseLong(properties.getProperty("interval.minutes", "360").strip())),
                Long.parseLong(properties.getProperty("item.delay.millis", "1000").strip()),
                Arrays.stream(properties.getProperty("platforms", "skills.sh,clawhub").split(",", -1)).map(String::strip).toList(),
                properties.getProperty("skills.view", "trending").strip(), properties.getProperty("clawhub.sort", "trending").strip(),
                properties.getProperty("skills.catalog", "public").strip());
    }
}
