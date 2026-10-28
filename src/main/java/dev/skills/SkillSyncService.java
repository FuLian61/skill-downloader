package dev.skills;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.*;
import java.net.URI;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.time.Instant;
import java.util.*;

public final class SkillSyncService {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final SyncConfig config;
    private final Catalog catalog;
    private final Download download;
    private final Pause pause;

    @FunctionalInterface interface Catalog { HotSkillCatalog.Snapshot list(String platform) throws IOException, InterruptedException; }
    @FunctionalInterface interface Download { SkillDownloader.Result run(URI uri, Path output, SkillDownloader.Options options) throws IOException, InterruptedException; }
    @FunctionalInterface interface Pause { void sleep(long millis) throws InterruptedException; }

    public record ItemResult(String id, int rank, String sourceUrl, String status, String revision, String sha256,
                             String file, String errorCode, String message) {}
    public record PlatformResult(String platform, String ranking, String status, int requested, int listed,
                                 String notice, String errorCode, List<ItemResult> items) {}
    public record RunResult(String runId, String startedAt, String finishedAt, boolean catalogOnly,
                            String status, List<PlatformResult> platforms) {}

    public SkillSyncService(SyncConfig config, DownloadConfig downloadConfig) {
        this(config, downloadConfig, new HttpFileDownloader(downloadConfig));
    }

    SkillSyncService(SyncConfig config, DownloadConfig downloadConfig, RemoteClient remote) {
        this(config, new HotSkillCatalog(remote, downloadConfig, config)::list,
                new SkillDownloader(new DownloadConfig(config.skillsCatalog().equals("public")
                        ? DownloadConfig.SkillsShMode.SHORTCUT : DownloadConfig.SkillsShMode.API,
                        downloadConfig.skillsShApiBase(), downloadConfig.skillsShTokens()), remote)::download, Thread::sleep);
    }

    SkillSyncService(SyncConfig config, Catalog catalog, Download download, Pause pause) {
        this.config = config;
        this.catalog = catalog;
        this.download = download;
        this.pause = pause;
    }

    public RunResult runOnce(boolean catalogOnly) throws IOException, InterruptedException {
        Path root = config.outputDirectory();
        Files.createDirectories(root);
        try (FileChannel channel = FileChannel.open(root.resolve(".sync.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
            FileLock lock;
            try { lock = channel.tryLock(); }
            catch (OverlappingFileLockException busy) { throw new IOException("同一输出目录已有同步任务运行"); }
            if (lock == null) throw new IOException("同一输出目录已有同步任务运行");
            try (lock) { return runLocked(catalogOnly); }
        }
    }

    private RunResult runLocked(boolean catalogOnly) throws IOException, InterruptedException {
        String started = Instant.now().toString();
        String runId = started.replace(":", "-") + "-" + UUID.randomUUID();
        Path runDirectory = config.outputDirectory().resolve("runs").resolve(runId);
        Files.createDirectories(runDirectory);
        List<PlatformResult> platforms = new ArrayList<>();
        writeJson(runDirectory.resolve("report.json"), new RunResult(runId, started, null, catalogOnly, "RUNNING", List.of()));
        try {
            for (String platform : config.platforms()) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                HotSkillCatalog.Snapshot snapshot;
                try {
                    snapshot = catalog.list(platform);
                } catch (IOException | IllegalArgumentException failed) {
                    platforms.add(new PlatformResult(platform, ranking(platform), "FAILED", config.top(), 0,
                            safeMessage(failed), code(failed), List.of()));
                    checkpoint(runDirectory, runId, started, catalogOnly, platforms);
                    System.err.println(platform + " 榜单失败 [" + code(failed) + "]: " + safeMessage(failed) + "（继续其他平台）");
                    continue;
                }
                writeJson(runDirectory.resolve(platform + "-catalog.json"), snapshot);
                List<ItemResult> items = new ArrayList<>();
                PlatformResult progress = new PlatformResult(platform, snapshot.ranking(), "RUNNING", config.top(), snapshot.entries().size(), snapshot.notice(), null, List.of());
                platforms.add(progress);
                checkpoint(runDirectory, runId, started, catalogOnly, platforms);
                for (HotSkillCatalog.Entry entry : snapshot.entries()) {
                    if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                    ItemResult item;
                    if (catalogOnly) {
                        item = new ItemResult(entry.id(), entry.rank(), entry.sourceUrl(), "LISTED", entry.version(), null, null, null, "仅拉取榜单，未下载");
                    } else {
                        try { item = syncItem(entry); }
                        catch (IOException | IllegalArgumentException failed) {
                            item = new ItemResult(entry.id(), entry.rank(), entry.sourceUrl(), "FAILED", null, null, null, code(failed), safeMessage(failed));
                        }
                    }
                    items.add(item);
                    platforms.set(platforms.size() - 1, new PlatformResult(platform, snapshot.ranking(), "RUNNING", config.top(), snapshot.entries().size(), snapshot.notice(), null, List.copyOf(items)));
                    checkpoint(runDirectory, runId, started, catalogOnly, platforms);
                    System.out.println(platform + " [" + entry.rank() + "/" + snapshot.entries().size() + "] " + item.status()
                            + " " + entry.id() + (item.errorCode() == null ? "" : " [" + item.errorCode() + "] " + item.message()));
                    if (!catalogOnly && config.itemDelayMillis() > 0) pause.sleep(config.itemDelayMillis());
                }
                String status = items.stream().anyMatch(item -> item.status().equals("FAILED")) ? "PARTIAL"
                        : snapshot.entries().size() < config.top() ? "INCOMPLETE" : "SUCCESS";
                platforms.set(platforms.size() - 1, new PlatformResult(platform, snapshot.ranking(), status, config.top(), snapshot.entries().size(), snapshot.notice(), null, List.copyOf(items)));
                checkpoint(runDirectory, runId, started, catalogOnly, platforms);
            }
        } catch (InterruptedException interrupted) {
            RunResult report = new RunResult(runId, started, Instant.now().toString(), catalogOnly, "INTERRUPTED", List.copyOf(platforms));
            writeJson(runDirectory.resolve("report.json"), report);
            writeJson(config.outputDirectory().resolve("latest-run.json"), report);
            throw interrupted;
        }
        String status = platforms.stream().allMatch(platform -> platform.status().equals("SUCCESS")) ? "SUCCESS" : "PARTIAL";
        RunResult report = new RunResult(runId, started, Instant.now().toString(), catalogOnly, status, List.copyOf(platforms));
        writeJson(runDirectory.resolve("report.json"), report);
        writeJson(config.outputDirectory().resolve("latest-run.json"), report);
        return report;
    }

    private ItemResult syncItem(HotSkillCatalog.Entry entry) throws IOException, InterruptedException {
        Path stagingRoot = config.outputDirectory().resolve("staging");
        Files.createDirectories(stagingRoot);
        Path temporary = Files.createTempDirectory(stagingRoot, "item-");
        try {
            SkillDownloader.Result result = download.run(URI.create(entry.sourceUrl()), temporary,
                    new SkillDownloader.Options(null, null, null, entry.platform().equals("clawhub") ? entry.version() : null));
            Path source = result.zipPath().toRealPath();
            if (!source.startsWith(temporary.toRealPath()) || !Files.isRegularFile(source)) throw new IOException("下载结果不在临时任务目录中");
            String hash = hashFile(source);
            if (!hash.equals(result.sha256())) throw new IOException("输出文件摘要不匹配");
            String key = digest(entry.id().getBytes(StandardCharsets.UTF_8));
            Path directory = config.outputDirectory().resolve("artifacts").resolve(entry.platform()).resolve(key);
            Files.createDirectories(directory);
            Path archive = directory.resolve(hash + ".zip");
            boolean unchanged = Files.exists(archive) && hash.equals(hashFile(archive));
            if (!unchanged) move(source, archive);
            String relative = config.outputDirectory().relativize(archive).toString();
            writeJson(directory.resolve("latest.json"), Map.of("id", entry.id(), "sourceUrl", entry.sourceUrl(),
                    "checkedAt", Instant.now().toString(), "revision", result.revision(), "sha256", hash, "file", relative));
            return new ItemResult(entry.id(), entry.rank(), entry.sourceUrl(), unchanged ? "UNCHANGED" : "DOWNLOADED",
                    result.revision(), hash, relative, null, unchanged ? "相同 ZIP 已存在，不重复保存" : "已保存并校验 ZIP");
        } finally {
            try (var files = Files.walk(temporary)) {
                for (Path file : files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(file);
            }
        }
    }

    private void checkpoint(Path directory, String id, String started, boolean catalogOnly, List<PlatformResult> platforms) throws IOException {
        writeJson(directory.resolve("report.json"), new RunResult(id, started, null, catalogOnly, "RUNNING", List.copyOf(platforms)));
    }

    private String ranking(String platform) { return platform.equals("skills.sh") ? config.skillsView() : config.clawhubSort(); }

    private static String code(Exception error) {
        if (error instanceof DownloadException failure) return failure.code().name();
        return error instanceof IllegalArgumentException ? "INVALID_INPUT" : "IO_ERROR";
    }

    private static String safeMessage(Exception error) {
        return SyncDiagnostics.describe(error);
    }

    static String hashFile(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = new DigestInputStream(Files.newInputStream(path), digest)) { input.transferTo(OutputStream.nullOutputStream()); }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private static String digest(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private static void writeJson(Path target, Object value) throws IOException {
        Files.createDirectories(target.getParent());
        Path temporary = Files.createTempFile(target.getParent(), ".json-", ".tmp");
        try {
            JSON.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), value);
            move(temporary, target);
        } finally { Files.deleteIfExists(temporary); }
    }

    private static void move(Path source, Path target) throws IOException {
        try { Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
        catch (AtomicMoveNotSupportedException unsupported) { Files.move(source, target, StandardCopyOption.REPLACE_EXISTING); }
    }
}
