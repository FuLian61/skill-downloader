package dev.skills;

import java.net.URI;
import java.util.List;

public sealed interface ResolvedSource {
    String name();
    String revision();

    record GitHub(String repository, String revision, String skillPath, String name) implements ResolvedSource {}
    record HostedZip(URI downloadUrl, String revision, String name) implements ResolvedSource {}
    record InlineFile(String path, String contents) {}
    record InlineFiles(List<InlineFile> files, String revision, String name) implements ResolvedSource {
        public InlineFiles { files = List.copyOf(files); }
    }
}
