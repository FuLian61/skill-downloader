package dev.skills;

import java.io.IOException;
import java.net.URI;

final class GitHubSourceAdapter implements SourceAdapter {
    private final RemoteClient remote;

    GitHubSourceAdapter(RemoteClient remote) { this.remote = remote; }

    public ResolvedSource.GitHub resolve(SkillDownloader.Source source, SkillDownloader.Options options)
            throws IOException, InterruptedException {
        String ref = options.ref() == null ? source.ref() : options.ref();
        String path = options.path() == null ? source.path() : options.path();
        String name = options.skill() == null ? source.slug() : options.skill();
        return resolve(source.repo(), ref, path, name);
    }

    ResolvedSource.GitHub resolve(String repo, String ref, String path, String name) throws IOException, InterruptedException {
        SkillDownloader.validateRepo(repo);
        if (ref == null) ref = SkillDownloader.required(remote.json(URI.create("https://api.github.com/repos/" + repo)), "default_branch");
        if (!ref.matches("[0-9a-fA-F]{40}")) {
            ref = SkillDownloader.required(remote.json(URI.create("https://api.github.com/repos/" + repo + "/commits/" + SkillDownloader.encode(ref))), "sha");
        }
        if (!ref.matches("[0-9a-fA-F]{40}")) throw new DownloadException(DownloadException.Code.INVALID_RESPONSE, "GitHub 响应缺少有效提交 SHA");
        return new ResolvedSource.GitHub(repo, ref, path, name);
    }

    void download(ResolvedSource.GitHub source, java.nio.file.Path target) throws IOException, InterruptedException {
        remote.fetch(URI.create("https://api.github.com/repos/" + source.repository() + "/zipball/" + source.revision()),
                target, SkillDownloader.MAX_DOWNLOAD);
    }
}
