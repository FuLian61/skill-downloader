package dev.skills;

import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.*;

public final class SkillSyncMain {
    public static void main(String[] args) throws Exception {
        String mode = args.length == 0 ? "--schedule" : args[0];
        if (!Set.of("--sync-once", "--sync-list", "--schedule").contains(mode)) throw new IllegalArgumentException("使用 --sync-once、--sync-list 或 --schedule");
        Path file = null;
        if (args.length > 1) {
            if (args.length != 3 || !args[1].equals("--config")) throw new IllegalArgumentException("可选参数: --config sync.properties");
            file = Path.of(args[2]);
        }
        SyncConfig config = SyncConfig.load(file);
        SkillSyncService service = new SkillSyncService(config, DownloadConfig.fromEnvironment());
        if (!mode.equals("--schedule")) {
            SkillSyncService.RunResult report = service.runOnce(mode.equals("--sync-list"));
            print(report, config);
            if (!report.status().equals("SUCCESS")) System.exit(2);
            return;
        }
        System.out.println("定时热门同步已启动；每个平台前 " + config.top() + " 项，上一轮完成后间隔 " + config.interval().toMinutes() + " 分钟。按 Ctrl+C 停止。");
        System.out.println("skills.sh 榜单模式: " + config.skillsCatalog() + "（public 无需平台 Token；api 需要凭证）；只下载、不执行。");
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        CountDownLatch stopped = new CountDownLatch(1);
        Thread shutdown = new Thread(() -> {
            scheduler.shutdownNow();
            try { scheduler.awaitTermination(10, TimeUnit.SECONDS); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            stopped.countDown();
        }, "skill-sync-shutdown");
        Runtime.getRuntime().addShutdownHook(shutdown);
        try {
            scheduler.scheduleWithFixedDelay(() -> {
                try { print(service.runOnce(false), config); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                catch (Exception failed) { System.err.println("本轮同步未完成: " + SyncDiagnostics.describe(failed) + "；后续周期仍会执行。"); }
            }, 0, config.interval().toMillis(), TimeUnit.MILLISECONDS);
            stopped.await();
        } finally {
            scheduler.shutdownNow();
            try { Runtime.getRuntime().removeShutdownHook(shutdown); }
            catch (IllegalStateException stopping) { }
        }
    }

    private static void print(SkillSyncService.RunResult report, SyncConfig config) {
        System.out.println("同步完成: " + report.status() + "；执行记录: " + config.outputDirectory().resolve("runs").resolve(report.runId()).resolve("report.json"));
        for (SkillSyncService.PlatformResult platform : report.platforms()) {
            System.out.println(platform.platform() + " / " + platform.ranking() + ": " + platform.status()
                    + "，榜单 " + platform.listed() + "/" + platform.requested() + " 项");
        }
    }
}
