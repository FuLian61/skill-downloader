package dev.skills;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

final class PublicSkillsPage {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern SCRIPTS = Pattern.compile("<script\\b[^>]*>(.*?)</script\\s*>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private static final Pattern PUSH = Pattern.compile("self\\.__next_f\\.push\\((\\[.*])\\);?", Pattern.DOTALL);

    static JsonNode parse(String html, String view) throws IOException {
        try { return parsePayload(html, view); }
        catch (com.fasterxml.jackson.core.JsonProcessingException malformed) { throw invalid(); }
    }

    private static JsonNode parsePayload(String html, String view) throws IOException {
        StringBuilder flight = new StringBuilder();
        var scripts = SCRIPTS.matcher(html);
        while (scripts.find()) {
            var push = PUSH.matcher(scripts.group(1).strip());
            if (!push.matches()) continue;
            JsonNode chunk = JSON.readTree(push.group(1));
            if (chunk.isArray() && chunk.size() == 2 && chunk.get(0).isInt()
                    && chunk.get(0).intValue() == 1 && chunk.get(1).isTextual()) {
                flight.append(chunk.get(1).textValue());
            }
        }
        List<JsonNode> candidates = new ArrayList<>();
        for (String record : flight.toString().split("\n")) {
            if (!record.contains("\"initialSkills\"")) continue;
            int separator = record.indexOf(':');
            if (separator < 1 || !record.substring(0, separator).matches("[0-9a-fA-F]+")) continue;
            collect(JSON.readTree(record.substring(separator + 1)), candidates);
        }
        if (candidates.size() != 1) throw invalid();
        JsonNode props = candidates.get(0);
        if (!view.equals(props.path("view").asText()) || !props.path("initialSkills").isArray()) throw invalid();
        return props.get("initialSkills");
    }

    private static void collect(JsonNode node, List<JsonNode> candidates) {
        if (node == null) return;
        if (node.isObject() && node.has("initialSkills")) { candidates.add(node); return; }
        if (node.isContainerNode()) for (JsonNode child : node) collect(child, candidates);
    }

    private static DownloadException invalid() {
        return new DownloadException(DownloadException.Code.INVALID_RESPONSE, "公开榜单结构改变、缺失或排序不符；未回退到需要凭证的 API");
    }
}
