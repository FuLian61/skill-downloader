package dev.skills;

import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.net.ConnectException;
import java.net.http.HttpTimeoutException;
import static org.junit.jupiter.api.Assertions.*;

class SyncDiagnosticsTest {
    @Test void reportsKnownReasonsAndLimits() {
        assertTrue(SyncDiagnostics.describe(new IOException("解压大小超过限制")).contains("300 MiB"));
        assertTrue(SyncDiagnostics.describe(new IOException("下载正文超时")).contains("120 秒"));
        assertTrue(SyncDiagnostics.describe(new IOException("ZIP CRC 校验失败")).contains("ZIP CRC 校验失败"));
        assertTrue(SyncDiagnostics.describe(new IOException("无法唯一确定 Skill，请使用 --path 指定目录。候选: secret" )).contains("--path"));
    }

    @Test void includesHttpStatusAndNestedNetworkCause() {
        assertTrue(SyncDiagnostics.describe(new DownloadException(DownloadException.Code.RATE_LIMITED, 429, "secret")).contains("HTTP 429"));
        String detail = SyncDiagnostics.describe(new IOException("secret", new HttpTimeoutException("secret")));
        assertTrue(detail.contains("HttpTimeoutException: 网络请求超时"));
        assertFalse(detail.contains("secret"));
        assertTrue(SyncDiagnostics.describe(new ConnectException("secret")).contains("连接远端服务失败"));
    }

    @Test void doesNotLeakUnknownMessagesOrUntrustedSuffixes() {
        for (Exception error : new Exception[] {
                new IOException("Authorization: Bearer secret https://user:password@example.com?token=secret"),
                new IOException("指定目录没有 SKILL.md: secret"),
                new DownloadException(DownloadException.Code.AUTH_REQUIRED, "secret"),
                new IOException("无法唯一确定 Skill，请使用 --path 指定目录。候选: secret") }) {
            assertFalse(SyncDiagnostics.describe(error).contains("secret"));
        }
    }

    @Test void terminatesCyclicCauses() {
        IOException first = new IOException("secret");
        IOException second = new IOException("secret", first);
        first.initCause(second);
        assertTrue(SyncDiagnostics.describe(first).length() < 300);
    }
}
