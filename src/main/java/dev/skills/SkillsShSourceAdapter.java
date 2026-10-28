package dev.skills;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.stream.Collectors;

final class SkillsShSourceAdapter implements SourceAdapter {
    private final RemoteClient remote;
    private final GitHubSourceAdapter github;
    private final DownloadConfig config;

    SkillsShSourceAdapter(RemoteClient remote, GitHubSourceAdapter github, DownloadConfig config) {
        this.remote = remote;
        this.github = github;
        this.config = config;
    }

    public ResolvedSource resolve(SkillDownloader.Source source, SkillDownloader.Options options)
            throws IOException, InterruptedException {
        if (config.skillsShMode() == DownloadConfig.SkillsShMode.SHORTCUT) {
            if (!source.repo().contains("/")) throw new DownloadException(DownloadException.Code.SOURCE_UNAVAILABLE, "非 GitHub 来源需要 skills.sh 正式 API 模式");
            System.err.println("skills.sh 使用 GitHub 快捷模式，未查询平台详情；正式接口需配置 API 模式及凭证。");
            return github.resolve(source, options);
        }
        String identifier = source.repo() + "/" + source.slug();
        String encoded = Arrays.stream(identifier.split("/")).map(SkillDownloader::encode).collect(Collectors.joining("/"));
        JsonNode detail = remote.json(config.skillsShApiBase().resolve("api/v1/skills/" + encoded));
        if (!identifier.equals(SkillDownloader.required(detail, "id"))
                || !source.repo().equals(SkillDownloader.required(detail, "source"))
                || !source.slug().equals(SkillDownloader.required(detail, "slug"))) {
            throw new DownloadException(DownloadException.Code.INVALID_RESPONSE, "skills.sh 详情与请求技能标识不一致");
        }
        if (source.repo().contains("/")) return github.resolve(source, options);
        if (options.ref() != null || options.path() != null || options.skill() != null) {
            throw new DownloadException(DownloadException.Code.INVALID_INPUT, "内联文件技能不支持 --ref、--path 或 --skill");
        }
        JsonNode files = detail.path("files");
        if (!files.isArray() || files.isEmpty()) throw new DownloadException(DownloadException.Code.SOURCE_UNAVAILABLE, "skills.sh 未提供可下载的文件快照");
        if (files.size() > 20000) throw new DownloadException(DownloadException.Code.INVALID_RESPONSE, "内联文件数量超过限制");
        var content = new ArrayList<ResolvedSource.InlineFile>();
        for (JsonNode file : files) {
            if (!file.path("contents").isTextual() || (file.has("encoding") && !"utf-8".equalsIgnoreCase(file.path("encoding").asText()))) {
                throw new DownloadException(DownloadException.Code.INVALID_RESPONSE, "仅支持官方 UTF-8 文本 contents，不支持未知或二进制编码");
            }
            content.add(new ResolvedSource.InlineFile(SkillDownloader.required(file, "path"), file.path("contents").textValue()));
        }
        return new ResolvedSource.InlineFiles(content, detail.path("hash").asText("未提供快照哈希"), source.slug());
    }
}
