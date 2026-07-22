package org.jboss.bacon.experimental.impl.generator;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
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
public class LlmScmUrlResolver {

    private static final String SYSTEM_PROMPT = "You are a software build system assistant. "
            + "When given Maven coordinates (groupId, artifactId, version), respond with ONLY the git repository URL "
            + "where the source code for that project is hosted. Respond with just the URL, nothing else. "
            + "If you are not confident, respond with 'UNKNOWN'.";

    private static final Pattern URL_PATTERN = Pattern.compile("(https?://[\\w.@:~/$!&'()*+,;=-]+)");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final LlmConfig config;
    private final CloseableHttpClient httpClient;

    public LlmScmUrlResolver(LlmConfig config) {
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

    public String resolveScmUrl(Set<GAV> gavs) {
        if (!config.isEnabled() || httpClient == null) {
            return null;
        }
        GAV primary = gavs.stream().sorted().findFirst().orElse(null);
        if (primary == null) {
            return null;
        }
        try {
            String requestBody = buildRequestBody(primary, gavs);
            String responseBody = callLlm(requestBody);
            return extractUrl(responseBody, primary);
        } catch (Exception e) {
            log.warn("Unexpected error during LLM SCM resolution for {}: {}", primary, e.getMessage());
            return null;
        }
    }

    private String buildRequestBody(GAV primary, Set<GAV> gavs) throws IOException {
        StringBuilder userContent = new StringBuilder();
        userContent.append("What is the git repository URL for the Maven artifact?\n");
        userContent.append("- groupId: ").append(primary.getGroupId()).append("\n");
        userContent.append("- artifactId: ").append(primary.getArtifactId()).append("\n");
        userContent.append("- version: ").append(primary.getVersion());

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

    private String extractUrl(String responseBody, GAV gav) {
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
                log.debug("LLM could not determine SCM URL for {}", gav);
                return null;
            }

            Matcher matcher = URL_PATTERN.matcher(content);
            if (matcher.find()) {
                String url = matcher.group(1);
                if (url.endsWith(".") || url.endsWith(",")) {
                    url = url.substring(0, url.length() - 1);
                }
                return url;
            }

            log.debug("LLM response did not contain a valid URL for {}: {}", gav, content);
            return null;
        } catch (Exception e) {
            log.warn("Failed to parse LLM response: {}", e.getMessage());
            return null;
        }
    }
}
