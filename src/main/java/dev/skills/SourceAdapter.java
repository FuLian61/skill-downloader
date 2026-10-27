package dev.skills;

import java.io.IOException;

interface SourceAdapter {
    ResolvedSource resolve(SkillDownloader.Source source, SkillDownloader.Options options)
            throws IOException, InterruptedException;
}
