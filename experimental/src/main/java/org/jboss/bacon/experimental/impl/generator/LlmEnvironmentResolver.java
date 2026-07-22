package org.jboss.bacon.experimental.impl.generator;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

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
public class LlmEnvironmentResolver {

    private static final String SYSTEM_PROMPT = "You are a PNC build environment selector. "
            + "Given a project's Maven coordinates, build type, and SCM URL, "
            + "select the most appropriate build environment from the provided list. "
            + "Consider the JDK version, build tool versions (Maven, Gradle, SBT), and any additional tools needed. "
            + "Prefer newer JDK and Maven versions when the project does not indicate a specific requirement. "
            + "Respond with ONLY the exact environment name from the list, nothing else. "
            + "If you cannot determine the best environment, respond with 'UNKNOWN'.";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final LlmConfig config;
    private final CloseableHttpClient httpClient;
    private final List<JsonNode> environments;
    private final Set<String> environmentNames;

    public LlmEnvironmentResolver(LlmConfig config) {
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
        this.environments = loadEnvironments();
        this.environmentNames = environments.stream()
                .filter(e -> !e.get("hidden").asBoolean(false))
                .filter(e -> !e.path("deprecated").asBoolean(false))
                .map(e -> e.get("name").asText())
                .collect(Collectors.toSet());
    }

    public String resolveEnvironment(Set<GAV> gavs, String buildType, String scmUrl) {
        if (!config.isEnabled() || httpClient == null || environments.isEmpty()) {
            return null;
        }
        GAV primary = gavs.stream().sorted().findFirst().orElse(null);
        if (primary == null) {
            return null;
        }
        try {
            List<JsonNode> filtered = filterEnvironments(buildType);
            if (filtered.isEmpty()) {
                return null;
            }
            String requestBody = buildRequestBody(primary, gavs, buildType, scmUrl, filtered);
            String responseBody = callLlm(requestBody);
            return extractEnvironmentName(responseBody, primary);
        } catch (Exception e) {
            log.warn("Unexpected error during LLM environment resolution for {}: {}", primary, e.getMessage());
            return null;
        }
    }

    List<JsonNode> filterEnvironments(String buildType) {
        return environments.stream()
                .filter(e -> !e.get("hidden").asBoolean(false))
                .filter(e -> !e.path("deprecated").asBoolean(false))
                .filter(e -> matchesBuildType(e, buildType))
                .collect(Collectors.toList());
    }

    private boolean matchesBuildType(JsonNode env, String buildType) {
        if (buildType == null || "MVN".equals(buildType)) {
            return true;
        }
        JsonNode attrs = env.get("attributes");
        String name = env.get("name").asText();
        switch (buildType) {
            case "GRADLE":
                return attrs != null && attrs.has("GRADLE");
            case "SBT":
                return name.toUpperCase().contains("SBT");
            case "NPM":
                return name.toLowerCase().contains("node") || name.toLowerCase().contains("npm");
            default:
                return true;
        }
    }

    private String buildRequestBody(
            GAV primary,
            Set<GAV> gavs,
            String buildType,
            String scmUrl,
            List<JsonNode> filtered)
            throws IOException {
        StringBuilder userContent = new StringBuilder();
        userContent.append("Select the best PNC build environment for this project:\n");
        userContent.append("- groupId: ").append(primary.getGroupId()).append("\n");
        userContent.append("- artifactId: ").append(primary.getArtifactId()).append("\n");
        userContent.append("- version: ").append(primary.getVersion()).append("\n");
        userContent.append("- buildType: ").append(buildType != null ? buildType : "MVN");

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

        userContent.append("\n\nAvailable environments:\n");
        for (JsonNode env : filtered) {
            userContent.append("- ").append(env.get("name").asText()).append("\n");
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
        return EntityUtils.toString(response.getEntity(), "UTF-8");
    }

    private String extractEnvironmentName(String responseBody, GAV gav) {
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
                log.debug("LLM could not determine environment for {}", gav);
                return null;
            }

            if (environmentNames.contains(content)) {
                return content;
            }

            for (String name : environmentNames) {
                if (content.contains(name)) {
                    return name;
                }
            }

            log.debug("LLM response did not contain a valid environment name for {}: {}", gav, content);
            return null;
        } catch (Exception e) {
            log.warn("Failed to parse LLM response: {}", e.getMessage());
            return null;
        }
    }

    public String getJdkVersion(String environmentName) {
        if (environmentName == null) {
            return null;
        }
        return environments.stream()
                .filter(e -> environmentName.equals(e.get("name").asText()))
                .filter(e -> !e.path("deprecated").asBoolean(false))
                .map(e -> e.path("attributes").path("JDK").asText(null))
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);
    }

    private List<JsonNode> loadEnvironments() {
        try (InputStream is = getClass().getResourceAsStream("/pnc-environments.json")) {
            if (is == null) {
                log.warn("pnc-environments.json not found on classpath");
                return List.of();
            }
            JsonNode array = MAPPER.readTree(is);
            if (!array.isArray()) {
                return List.of();
            }
            List<JsonNode> result = new ArrayList<>();
            array.forEach(result::add);
            return result;
        } catch (Exception e) {
            log.warn("Failed to load pnc-environments.json: {}", e.getMessage());
            return List.of();
        }
    }
}
