package dev.skills;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.net.URI;

final class ClawHubSourceAdapter implements SourceAdapter {
    private final RemoteClient remote;

    ClawHubSourceAdapter(RemoteClient remote) { this.remote = remote; }

    public ResolvedSource.HostedZip resolve(SkillDownloader.Source source, SkillDownloader.Options options)
            throws IOException, InterruptedException {
        String owner = SkillDownloader.encode(source.owner());
        String slug = SkillDownloader.encode(source.slug());
        JsonNode metadata = remote.json(URI.create("https://clawhub.ai/api/v1/skills/" + slug + "?ownerHandle=" + owner));
        String version = options.version() == null ? metadata.path("latestVersion").path("version").asText("") : options.version();
        URI endpoint = URI.create("https://clawhub.ai/api/v1/download?slug=" + slug + "&ownerHandle=" + owner
                + (version.isBlank() ? "" : "&version=" + SkillDownloader.encode(version)));
        return new ResolvedSource.HostedZip(endpoint, version.isBlank() ? "平台未提供版本号" : version, source.slug());
    }

    static ResolvedSource.GitHub handoff(JsonNode descriptor, String name) throws IOException {
        if (descriptor == null || !descriptor.isObject() || !"public-github".equals(descriptor.path("sourceRef").asText())) {
            throw new DownloadException(DownloadException.Code.INVALID_RESPONSE, "不支持的 ClawHub 下载响应");
        }
        String repo = SkillDownloader.required(descriptor, "repo");
        SkillDownloader.validateRepo(repo);
        String commit = SkillDownloader.required(descriptor, "commit");
        if (!commit.matches("[0-9a-fA-F]{40}")) throw new DownloadException(DownloadException.Code.INVALID_RESPONSE, "ClawHub 未提供有效固定提交");
        String path = SkillDownloader.required(descriptor, "path");
        if (!path.equals(".")) SkillZipService.safeEntry(path);
        return new ResolvedSource.GitHub(repo, commit, path, name);
    }
}
