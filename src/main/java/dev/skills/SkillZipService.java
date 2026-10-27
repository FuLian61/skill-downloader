package dev.skills;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;
import java.util.zip.*;

final class SkillZipService {
    private static final long MAX_EXPANDED = 300L * 1024 * 1024;

    static void packInline(ResolvedSource.InlineFiles source, Path target) throws IOException {
        if (source.files().size() > 20000) throw new IOException("内联文件数量超过限制");
        Set<String> seen = new HashSet<>();
        long expanded = 0;
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(target))) {
            for (ResolvedSource.InlineFile file : source.files()) {
                String name = safeEntry(file.path());
                if (name.endsWith("/") || !seen.add(name)) throw new IOException("内联文件路径为空目录或重复");
                byte[] bytes = file.contents().getBytes(StandardCharsets.UTF_8);
                expanded += bytes.length;
                if (expanded > MAX_EXPANDED) throw new IOException("内联内容超过大小限制");
                ZipEntry entry = new ZipEntry(name);
                entry.setTime(0);
                output.putNextEntry(entry);
                output.write(bytes);
                output.closeEntry();
            }
        }
        if (!seen.contains("SKILL.md")) throw new IOException("内联文件根目录缺少 SKILL.md");
    }

    static String safeEntry(String name) throws IOException {
        if (name.isEmpty() || name.startsWith("/") || name.contains("\\") || name.contains(":")) throw new IOException("非法归档路径");
        for (String part : name.split("/", -1)) {
            if (part.equals("..") || part.equals(".") || part.chars().anyMatch(character -> character < 32)) throw new IOException("不安全的归档路径");
        }
        if (name.contains("//")) throw new IOException("非法归档路径");
        return name;
    }

    static String repack(Path archive, Path target, boolean repository, String requestedPath, String skill) throws IOException {
        try (ZipFile zip = new ZipFile(archive.toFile(), StandardCharsets.UTF_8)) {
            List<? extends ZipEntry> entries = Collections.list(zip.entries());
            if (entries.size() > 20000) throw new IOException("归档条目过多");
            Map<String, ZipEntry> files = new TreeMap<>();
            Set<String> seen = new HashSet<>();
            String wrapper = null;
            long expanded = 0;
            for (ZipEntry entry : entries) {
                String name = safeEntry(entry.getName());
                if (!seen.add(name)) throw new IOException("归档存在重复路径");
                if (repository) {
                    int slash = name.indexOf('/');
                    if (slash < 1) throw new IOException("仓库归档缺少顶层目录");
                    String root = name.substring(0, slash);
                    if (wrapper != null && !wrapper.equals(root)) throw new IOException("仓库归档有多个顶层目录");
                    wrapper = root;
                    name = name.substring(slash + 1);
                }
                if (entry.isDirectory()) continue;
                if (entry.getSize() < 0 || entry.getSize() > MAX_EXPANDED - expanded) throw new IOException("解压大小超过限制");
                try (InputStream input = zip.getInputStream(entry)) {
                    CRC32 checksum = new CRC32();
                    CheckedInputStream checked = new CheckedInputStream(input, checksum);
                    expanded += copyLimited(checked, OutputStream.nullOutputStream(), MAX_EXPANDED - expanded);
                    if (checksum.getValue() != entry.getCrc()) throw new IOException("ZIP CRC 校验失败");
                }
                files.put(name, entry);
            }
            List<String> candidates = files.keySet().stream().filter(name -> name.equals("SKILL.md") || name.endsWith("/SKILL.md"))
                    .map(name -> name.equals("SKILL.md") ? "" : name.substring(0, name.length() - 9)).toList();
            String selected;
            if (requestedPath != null) {
                selected = requestedPath.equals(".") ? "" : requestedPath.replaceAll("/+$", "");
                if (!selected.isEmpty()) safeEntry(selected);
                if (!candidates.contains(selected)) throw new IOException("指定目录没有 SKILL.md: " + selected);
            } else {
                List<String> matches = new ArrayList<>();
                for (String candidate : candidates) {
                    if (skill == null || candidate.substring(candidate.lastIndexOf('/') + 1).equals(skill)
                            || skill.equals(frontmatterName(zip, files.get(candidate.isEmpty() ? "SKILL.md" : candidate + "/SKILL.md")))) matches.add(candidate);
                }
                if (matches.size() != 1) throw new IOException("无法唯一确定 Skill，请使用 --path 指定目录。候选: " + candidates.stream().limit(30).toList());
                selected = matches.get(0);
            }
            String prefix = selected.isEmpty() ? "" : selected + "/";
            try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(target))) {
                for (var item : files.entrySet()) {
                    if (!item.getKey().startsWith(prefix)) continue;
                    ZipEntry entry = new ZipEntry(item.getKey().substring(prefix.length()));
                    entry.setTime(0);
                    output.putNextEntry(entry);
                    try (InputStream input = zip.getInputStream(item.getValue())) { copyLimited(input, output, MAX_EXPANDED); }
                    output.closeEntry();
                }
            }
            return selected;
        }
    }

    private static String frontmatterName(ZipFile zip, ZipEntry entry) throws IOException {
        try (InputStream input = zip.getInputStream(entry)) {
            String text = new String(input.readNBytes(65536), StandardCharsets.UTF_8).replace("\r\n", "\n");
            if (!text.startsWith("---\n")) return "";
            int end = text.indexOf("\n---", 4);
            if (end < 0) return "";
            var matcher = Pattern.compile("(?m)^name:\\s*[\"']?([a-zA-Z0-9._-]+)[\"']?\\s*$").matcher(text.substring(4, end));
            return matcher.find() ? matcher.group(1) : "";
        }
    }

    static long copyLimited(InputStream input, OutputStream output, long limit) throws IOException {
        byte[] buffer = new byte[16384];
        long total = 0;
        int count;
        while ((count = input.read(buffer)) != -1) {
            total += count;
            if (total > limit) throw new IOException("文件大小超过限制");
            output.write(buffer, 0, count);
        }
        return total;
    }

}
