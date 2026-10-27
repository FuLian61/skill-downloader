package dev.skills;

import java.net.URI;
import java.nio.file.Path;
import java.util.Map;

public record DownloadConfig(SkillsShMode skillsShMode, URI skillsShApiBase, TokenProvider skillsShTokens) {
    public enum SkillsShMode { SHORTCUT, API }

    public DownloadConfig {
        if (skillsShMode == null || skillsShApiBase == null) throw new IllegalArgumentException("下载配置不能为空");
        if (!"https".equals(skillsShApiBase.getScheme()) || skillsShApiBase.getHost() == null
                || skillsShApiBase.getUserInfo() != null || skillsShApiBase.getQuery() != null
                || skillsShApiBase.getFragment() != null || (skillsShApiBase.getPort() != -1 && skillsShApiBase.getPort() != 443)) {
            throw new IllegalArgumentException("skills.sh API base 必须是可信的 HTTPS 地址，不含认证、查询或锚点");
        }
        skillsShApiBase = URI.create(skillsShApiBase.toString().replaceAll("/+$", "") + "/");
    }

    public static DownloadConfig fromEnvironment() {
        return fromEnvironment(System.getenv());
    }

    static DownloadConfig fromEnvironment(Map<String, String> environment) {
        String mode = environment.getOrDefault("SKILLS_SH_MODE", "shortcut");
        SkillsShMode selected = switch (mode) {
            case "shortcut" -> SkillsShMode.SHORTCUT;
            case "api" -> SkillsShMode.API;
            default -> throw new IllegalArgumentException("SKILLS_SH_MODE 只能为 shortcut 或 api");
        };
        String file = environment.get("SKILLS_SH_TOKEN_FILE");
        TokenProvider tokens = file == null || file.isBlank() ? () -> environment.get("SKILLS_SH_TOKEN")
                : TokenProvider.rotatingFile(Path.of(file));
        return new DownloadConfig(selected, URI.create(environment.getOrDefault("SKILLS_SH_API_BASE", "https://skills.sh")), tokens);
    }
}
