package dev.skills;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;

public final class SkillDownloader {
    private static final ObjectMapper JSON = new ObjectMapper();
    static final long MAX_DOWNLOAD = Long.MAX_VALUE;
    private final DownloadConfig config;
    private final RemoteClient remote;

    public SkillDownloader() { this(DownloadConfig.fromEnvironment()); }

    public SkillDownloader(DownloadConfig config) {
        this(config, new HttpFileDownloader(config));
    }

    SkillDownloader(DownloadConfig config, RemoteClient remote) {
        this.config = config;
        this.remote = remote;
    }

    public record Options(String ref, String path, String skill, String version) {}
    public record Result(Path zipPath, String source, String revision, String skillPath, String sha256) {}
    record Source(String platform, String repo, String slug, String ref, String path, String owner) {}

    public static void main(String[] args) {
        try {
            if (args.length > 0 && Set.of("--sync-once", "--sync-list", "--schedule").contains(args[0])) {
                SkillSyncMain.main(args);
                return;
            }
            if (args.length > 0 && args[0].equals("--serve")) {
                SkillWebServer.main(Arrays.copyOfRange(args, 1, args.length));
                return;
            }
            if (args.length == 0 || args[0].equals("--help")) {
                System.out.println("用法: java -jar skill-downloader-1.0.0.jar <URL> [--out 目录] [--skill 名称] [--ref 分支或提交] [--path 技能目录] [--version ClawHub版本]");
                System.out.println("网页模式: java -jar skill-downloader-1.0.0.jar --serve [--port 8080]");
                System.out.println("热门同步: --sync-once | --sync-list | --schedule [--config sync.properties]");
                return;
            }
            Map<String, String> flags = new HashMap<>();
            for (int index = 1; index < args.length; index += 2) {
                if (!Set.of("--out", "--skill", "--ref", "--path", "--version").contains(args[index])
                        || index + 1 >= args.length || flags.put(args[index], args[index + 1]) != null) {
                    throw new IOException("未知、重复或缺少值的参数: " + args[index]);
                }
            }
            Result result = new SkillDownloader().download(URI.create(args[0]),
                    Path.of(flags.getOrDefault("--out", "downloads")),
                    new Options(flags.get("--ref"), flags.get("--path"), flags.get("--skill"), flags.get("--version")));
            System.out.println("下载完成: " + result.zipPath());
            System.out.println("版本或提交: " + result.revision());
            System.out.println("技能目录: " + result.skillPath());
            System.out.println("SHA-256: " + result.sha256());
            System.out.println("仅下载，未安装或执行技能。审查内容后再使用。");
        } catch (Exception error) {
            System.err.println("下载失败" + (error instanceof DownloadException failure ? " [" + failure.code() + "]" : "") + ": " + error.getMessage());
            System.exit(1);
        }
    }

    public Result download(URI url, Path output, Options options) throws IOException, InterruptedException {
        Source source = parseSource(url);
        if (!source.platform().equals("clawhub") && options.version() != null) {
            throw new IOException("--version 仅适用于 ClawHub");
        }
        if (source.platform().equals("clawhub") && (options.ref() != null || options.path() != null || options.skill() != null)) {
            throw new IOException("ClawHub 请仅使用 --version 和 --out");
        }
        Files.createDirectories(output);
        Path staging = Files.createTempDirectory(output, ".skill-download-");
        try {
            GitHubSourceAdapter github = new GitHubSourceAdapter(remote);
            SourceAdapter adapter = switch (source.platform()) {
                case "github" -> github;
                case "skills.sh" -> new SkillsShSourceAdapter(remote, github, config);
                case "clawhub" -> new ClawHubSourceAdapter(remote);
                default -> throw new DownloadException(DownloadException.Code.INVALID_INPUT, "不支持的来源");
            };
            ResolvedSource resolved = adapter.resolve(source, options);
            Path archive = staging.resolve("source.zip");
            Path packed = staging.resolve("skill.zip");
            if (resolved instanceof ResolvedSource.HostedZip hosted) {
                remote.fetch(hosted.downloadUrl(), archive, MAX_DOWNLOAD);
                if (!isZip(archive)) {
                    if (Files.size(archive) > 2 * 1024 * 1024) throw new DownloadException(DownloadException.Code.INVALID_RESPONSE, "非 ZIP 响应超过 JSON 大小限制");
                    resolved = ClawHubSourceAdapter.handoff(JSON.readTree(archive.toFile()), hosted.name());
                    System.err.println("ClawHub 返回 GitHub 来源描述；本程序未确认内容安全，下载后仍需审查。");
                }
            }
            String selected;
            if (resolved instanceof ResolvedSource.GitHub git) {
                github.download(git, archive);
                selected = SkillZipService.repack(archive, packed, true, git.skillPath(), git.name());
            } else if (resolved instanceof ResolvedSource.InlineFiles inline) {
                SkillZipService.packInline(inline, archive);
                selected = SkillZipService.repack(archive, packed, false, ".", null);
            } else {
                selected = SkillZipService.repack(archive, packed, false, null, null);
            }
            String revision = resolved.revision();
            String skill = resolved.name();
            String label = skill != null ? skill : selected.isEmpty() ? "skill" : selected.substring(selected.lastIndexOf('/') + 1);
            label = label.replaceAll("[^a-zA-Z0-9._-]", "_");
            if (label.length() > 70) label = label.substring(0, 70);
            String hash = sha256(packed);
            Path target = output.resolve(label + "-" + hash.substring(0, 12) + ".zip").toAbsolutePath();
            Files.move(packed, target);
            return new Result(target, url.toString(), revision, selected, hash);
        } finally {
            try (var files = Files.walk(staging)) {
                for (Path file : files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(file);
            }
        }
    }

    static Source parseSource(URI url) throws IOException {
        if (!"https".equalsIgnoreCase(url.getScheme()) || url.getHost() == null || url.getPath() == null || url.getUserInfo() != null
                || (url.getPort() != -1 && url.getPort() != 443) || url.getQuery() != null) {
            throw new IOException("请输入不含认证信息或查询参数的 HTTPS 页面链接");
        }
        String host = Objects.toString(url.getHost(), "").toLowerCase(Locale.ROOT);
        if (host.equals("www.skills.sh")) host = "skills.sh";
        String raw = url.getPath().replaceAll("^/+|/+$", "");
        String[] parts = raw.split("/");
        if (!host.equals("github.com") && url.getFragment() != null) throw new IOException("只有 GitHub 仓库链接支持 #ref");
        if (host.equals("github.com")) {
            if (parts.length < 2) throw new IOException("GitHub 链接缺少 owner/repo");
            String repo = parts[0] + "/" + parts[1].replaceFirst("\\.git$", "");
            validateRepo(repo);
            if (parts.length == 2) return new Source("github", repo, null, url.getFragment(), null, null);
            if (parts.length >= 4 && parts[2].equals("tree")) {
                if (url.getFragment() != null) throw new IOException("tree URL 不允许同时携带 #ref");
                return new Source("github", repo, null, parts[3], parts.length == 4 ? null : String.join("/", Arrays.copyOfRange(parts, 4, parts.length)), null);
            }
            throw new IOException("GitHub 请提供仓库或 tree 目录链接；含 / 的分支请改用仓库 URL 加 --ref 和 --path");
        }
        if (host.equals("skills.sh")) {
            if (parts.length == 3) {
                validateRepo(parts[0] + "/" + parts[1]);
                return new Source("skills.sh", parts[0] + "/" + parts[1], validSlug(parts[2]), null, null, null);
            }
            if (parts.length == 2 && parts[0].matches("[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}")) {
                return new Source("skills.sh", parts[0], validSlug(parts[1]), null, null, null);
            }
            throw new IOException("skills.sh 请提供 /owner/repo/skill 或 /domain/skill 页面链接");
        }
        if (host.equals("clawhub.ai") || host.equals("clawhub.com")) {
            if (parts.length == 2 || (parts.length == 3 && parts[1].equals("skills"))) {
                return new Source("clawhub", null, validSlug(parts[parts.length - 1]), null, null, validSlug(parts[0]));
            }
            throw new IOException("ClawHub 请提供 /owner/skills/slug 或 /owner/slug 页面链接");
        }
        throw new IOException("仅支持 github.com、skills.sh、clawhub.ai 和 clawhub.com 的技能页面");
    }

    private static String validSlug(String slug) throws IOException {
        if (!slug.matches("[a-zA-Z0-9][a-zA-Z0-9._-]{0,199}")) throw new IOException("无效的技能标识");
        return slug;
    }

    static void validateRepo(String repo) throws IOException {
        if (!repo.matches("[a-zA-Z0-9_-]+/[a-zA-Z0-9_.-]+") || repo.endsWith("/..") || repo.endsWith("/.")) {
            throw new IOException("无效 GitHub 仓库");
        }
    }

    static String required(JsonNode node, String field) throws IOException {
        String value = node.path(field).asText("");
        if (value.isBlank() || !node.path(field).isTextual()) throw new DownloadException(DownloadException.Code.INVALID_RESPONSE, "平台响应缺少字符串字段: " + field);
        return value;
    }

    static String encode(String text) {
        return URLEncoder.encode(text, StandardCharsets.UTF_8).replace("+", "%20");
    }

    static String safeEntry(String name) throws IOException { return SkillZipService.safeEntry(name); }

    static String repack(Path archive, Path target, boolean repository, String path, String skill) throws IOException {
        return SkillZipService.repack(archive, target, repository, path, skill);
    }

    static long copyLimited(InputStream input, OutputStream output, long limit) throws IOException {
        return SkillZipService.copyLimited(input, output, limit);
    }

    static void validateDownloadUri(URI uri) throws IOException { HttpFileDownloader.validateUri(uri, null); }

    private static boolean isZip(Path path) throws IOException {
        try (InputStream input = Files.newInputStream(path)) {
            return Arrays.equals(input.readNBytes(4), new byte[]{80, 75, 3, 4});
        }
    }

    private static String sha256(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = new DigestInputStream(Files.newInputStream(path), digest)) {
                input.transferTo(OutputStream.nullOutputStream());
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException error) {
            throw new IOException(error);
        }
    }
}
