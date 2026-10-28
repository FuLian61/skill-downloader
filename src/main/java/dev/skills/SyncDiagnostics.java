package dev.skills;

import java.net.*;
import java.net.http.HttpTimeoutException;
import java.nio.file.*;
import java.nio.channels.UnresolvedAddressException;
import java.util.*;
import java.util.zip.ZipException;
import javax.net.ssl.SSLException;

final class SyncDiagnostics {
    private static final List<String> LOCAL_MESSAGES = List.of(
            "下载超过大小限制", "下载正文超时", "文件大小超过限制", "解压大小超过限制",
            "归档条目过多", "归档存在重复路径", "仓库归档缺少顶层目录", "仓库归档有多个顶层目录",
            "ZIP CRC 校验失败", "指定目录没有 SKILL.md", "无法唯一确定 Skill",
            "内联文件数量超过限制", "内联文件路径为空目录或重复", "内联内容超过大小限制",
            "内联文件根目录缺少 SKILL.md", "非法归档路径", "不安全的归档路径",
            "下载结果不在临时任务目录中", "输出文件摘要不匹配", "同一输出目录已有同步任务运行",
            "公开榜单结构改变、缺失或排序不符", "公开榜单包含重复技能", "公开榜单技能路径无效");

    static String describe(Throwable error) {
        List<String> details = new ArrayList<>();
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable current = error; current != null && visited.add(current) && details.size() < 4; current = current.getCause()) {
            details.add(current.getClass().getSimpleName() + ": " + reason(current));
        }
        return String.join("；原因 → ", details);
    }

    private static String reason(Throwable error) {
        if (error instanceof DownloadException failure) {
            String reason = switch (failure.code()) {
                case AUTH_REQUIRED -> "缺少或失效的平台凭证";
                case RATE_LIMITED -> "平台限流，有限重试后仍不可用";
                case FORBIDDEN -> "平台拒绝访问，不切换来源绕过";
                case NOT_FOUND -> "资源或版本不存在";
                case SOURCE_UNAVAILABLE -> "来源不可用；公开榜单模式的非 GitHub 技能暂不支持下载，可配置正式 API 模式";
                case INVALID_INPUT -> "请求参数或来源地址不被允许";
                case INVALID_RESPONSE -> knownMessage(error, "平台返回数据格式或内容校验失败");
                case REMOTE_ERROR -> "远端服务请求失败";
            };
            return reason + (failure.status() > 0 ? "（HTTP " + failure.status() + "）" : "");
        }
        if (error instanceof HttpTimeoutException || error instanceof SocketTimeoutException) return "网络请求超时";
        if (error instanceof UnknownHostException || error instanceof UnresolvedAddressException) return "域名解析失败";
        if (error instanceof ConnectException) return "连接远端服务失败";
        if (error instanceof SSLException) return "TLS 连接或证书校验失败";
        if (error instanceof SocketException) return "网络连接中断或套接字异常";
        if (error instanceof ZipException) return "ZIP 格式损坏或解压失败";
        if (error instanceof AccessDeniedException) return "文件读写权限不足";
        if (error instanceof NoSuchFileException) return "所需文件或目录不存在";
        if (error instanceof FileSystemException) return "文件系统操作失败，请检查磁盘空间和目录权限";
        return knownMessage(error, "未分类异常，原始消息未输出以避免泄露凭证");
    }

    private static String knownMessage(Throwable error, String fallback) {
        String message = Objects.toString(error.getMessage(), "");
        for (String known : LOCAL_MESSAGES) {
            if (message.equals(known) || message.startsWith(known + ":") || message.startsWith(known + "，") || message.startsWith(known + "；")) {
                if (known.equals("解压大小超过限制")) return known + "（当前总量上限 300 MiB）";
                if (known.equals("下载正文超时")) return known + "（当前单次正文期限 120 秒）";
                if (known.equals("无法唯一确定 Skill")) return known + "，需检查技能目录或通过单项下载 --path 指定";
                if (known.equals("归档条目过多")) return known + "（当前上限 20,000 个）";
                return known;
            }
        }
        return fallback;
    }
}
