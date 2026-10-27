package dev.skills;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;

interface RemoteClient {
    void fetch(URI uri, Path target, long limit) throws IOException, InterruptedException;

    default JsonNode json(URI uri) throws IOException, InterruptedException {
        Path temporary = Files.createTempFile("skill-metadata-", ".json");
        try {
            fetch(uri, temporary, 16L * 1024 * 1024);
            JsonNode node;
            try {
                node = new ObjectMapper().readTree(temporary.toFile());
            } catch (com.fasterxml.jackson.core.JsonProcessingException invalid) {
                throw new DownloadException(DownloadException.Code.INVALID_RESPONSE, "API 返回无效 JSON");
            }
            if (node == null || !node.isObject()) throw new DownloadException(DownloadException.Code.INVALID_RESPONSE, "API 未返回 JSON 对象");
            return node;
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
