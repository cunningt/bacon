package org.jboss.bacon.experimental.impl.generator;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.util.EntityUtils;
import org.jboss.bacon.experimental.impl.config.LlmConfig;
import org.jboss.da.model.rest.GAV;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class LlmTagResolver {

    private static final String SYSTEM_PROMPT = "You are a software build system assistant. "
            + "When given Maven coordinates (groupId, artifactId, version) and optionally a git repository URL, "
            + "respond with ONLY the git tag that corresponds to that version of the project. "
            + "Common tag formats include: '3.12.0', 'v3.12.0', 'commons-lang3-3.12.0', 'rel/commons-lang-3.12.0'. "
            + "Respond with just the tag, nothing else. "
            + "If you are not confident, respond with 'UNKNOWN'.";

    private static final Pattern TAG_PATTERN = Pattern.compile("[\\w./_-]*[\\d./][\\w./_-]*");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final LlmConfig config;
    private final CloseableHttpClient httpClient;

    public LlmTagResolver(LlmConfig config) {
        this.config = config;
        if (config.isEnabled()) {
            this.httpClient = HttpClientBuilder.create()
                    .setDefaultRequestConfig(
                            RequestConfig.custom()
                                    .setConnectTimeout(config.getConnectTimeoutMs())
                                    .setSocketTimeout(config.getReadTimeoutMs())
                                    .build())
                    .build();
        } else {
            this.httpClient = null;
        }
    }

    public String resolveTag(Set<GAV> gavs, String scmUrl) {
        if (!config.isEnabled() || httpClient == null) {
            return null;
        }
        GAV primary = gavs.stream().sorted().findFirst().orElse(null);
        if (primary == null) {
            return null;
        }
        try {
            String requestBody = buildRequestBody(primary, gavs, scmUrl);
            String responseBody = callLlm(requestBody);
            return extractTag(responseBody, primary);
        } catch (Exception e) {
            log.warn("Unexpected error during LLM tag resolution for {}: {}", primary, e.getMessage());
            return null;
        }
    }

    private String buildRequestBody(GAV primary, Set<GAV> gavs, String scmUrl) throws IOException {
        StringBuilder userContent = new StringBuilder();
        userContent.append("What is the git tag for this Maven artifact release?\n");
        userContent.append("- groupId: ").append(primary.getGroupId()).append("\n");
        userContent.append("- artifactId: ").append(primary.getArtifactId()).append("\n");
        userContent.append("- version: ").append(primary.getVersion());

        if (scmUrl != null && !scmUrl.isEmpty()) {
            userContent.append("\n\nGit repository: ").append(scmUrl);
        }

        if (gavs.size() > 1) {
            String others = gavs.stream()
                    .sorted()
                    .filter(g -> !g.equals(primary))
                    .limit(5)
                    .map(g -> g.getGroupId() + ":" + g.getArtifactId() + ":" + g.getVersion())
                    .collect(Collectors.joining(", "));
            userContent.append("\n\nOther artifacts in the same project: ").append(others);
        }

        Map<String, Object> request = Map.of(
                "model",
                config.getModel(),
                "messages",
                List.of(
                        Map.of("role", "system", "content", SYSTEM_PROMPT),
                        Map.of("role", "user", "content", userContent.toString())),
                "temperature",
                config.getTemperature());

        return MAPPER.writeValueAsString(request);
    }

    private String callLlm(String requestBody) throws IOException {
        String url = config.getApiUrl();
        if (!url.endsWith("/")) {
            url += "/";
        }
        url += "chat/completions";

        HttpPost post = new HttpPost(url);
        post.setEntity(new StringEntity(requestBody, ContentType.APPLICATION_JSON));
        post.setHeader("Accept", "application/json");
        if (config.getApiKey() != null && !config.getApiKey().isEmpty()) {
            post.setHeader("Authorization", "Bearer " + config.getApiKey());
        }

        HttpResponse response = httpClient.execute(post);
        int statusCode = response.getStatusLine().getStatusCode();
        if (statusCode >= 300) {
            log.warn("LLM returned HTTP {}", statusCode);
            return null;
        }
        HttpEntity entity = response.getEntity();
        return EntityUtils.toString(entity, "UTF-8");
    }

    private String extractTag(String responseBody, GAV gav) {
        if (responseBody == null) {
            return null;
        }
        try {
            JsonNode root = MAPPER.readTree(responseBody);
            JsonNode choices = root.get("choices");
            if (choices == null || !choices.isArray() || choices.isEmpty()) {
                log.warn("LLM response missing choices array");
                return null;
            }
            String content = choices.get(0).get("message").get("content").asText().trim();

            if (content.isEmpty() || content.toUpperCase().contains("UNKNOWN")) {
                log.debug("LLM could not determine tag for {}", gav);
                return null;
            }

            if (TAG_PATTERN.matcher(content).matches()) {
                return content;
            }

            for (String token : content.split("\\s+")) {
                String cleaned = token.replaceAll("[.,;:\"'`]+$", "");
                if (!cleaned.isEmpty() && TAG_PATTERN.matcher(cleaned).matches()) {
                    return cleaned;
                }
            }

            log.debug("LLM response did not contain a valid tag for {}: {}", gav, content);
            return null;
        } catch (Exception e) {
            log.warn("Failed to parse LLM response: {}", e.getMessage());
            return null;
        }
    }
}
