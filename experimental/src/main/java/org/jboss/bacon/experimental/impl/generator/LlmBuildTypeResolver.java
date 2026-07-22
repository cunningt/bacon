package org.jboss.bacon.experimental.impl.generator;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.apache.http.HttpResponse;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.HttpGet;
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
public class LlmBuildTypeResolver {

    private static final String SYSTEM_PROMPT = "You are a software build system assistant. "
            + "When given Maven coordinates (groupId, artifactId, version) and optionally a git repository URL, "
            + "determine the build system used by the project. "
            + "Respond with ONLY one of these values: MVN, GRADLE, NPM, SBT. "
            + "MVN means Maven, GRADLE means Gradle, NPM means Node.js/npm, SBT means Scala Build Tool. "
            + "If you are not confident, respond with 'UNKNOWN'.";

    private static final Pattern GITHUB_URL_PATTERN = Pattern
            .compile("https?://github\\.com/([^/]+)/([^/.]+)(?:\\.git)?(?:/.*)?$");

    private static final Set<String> VALID_BUILD_TYPES = Set.of("MVN", "GRADLE", "NPM", "SBT");

    private static final Map<String, String> BUILD_TYPE_ALIASES = Map.of("MAVEN", "MVN", "SCALA", "SBT");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final LlmConfig config;
    private final CloseableHttpClient httpClient;
    private final String githubRawBaseUrl;
    private boolean lastResolutionUsedLlm;

    public LlmBuildTypeResolver(LlmConfig config) {
        this(config, "https://raw.githubusercontent.com");
    }

    LlmBuildTypeResolver(LlmConfig config, String githubRawBaseUrl) {
        this.config = config;
        this.githubRawBaseUrl = githubRawBaseUrl;
        this.httpClient = HttpClientBuilder.create()
                .setDefaultRequestConfig(
                        RequestConfig.custom()
                                .setConnectTimeout(config.getConnectTimeoutMs())
                                .setSocketTimeout(config.getReadTimeoutMs())
                                .build())
                .build();
    }

    public String resolveBuildType(Set<GAV> gavs, String scmUrl, String scmRevision) {
        lastResolutionUsedLlm = false;
        String fileResult = detectFromRepository(scmUrl, scmRevision);
        if (fileResult != null) {
            return fileResult;
        }
        String llmResult = queryLlm(gavs, scmUrl);
        if (llmResult != null) {
            lastResolutionUsedLlm = true;
        }
        return llmResult;
    }

    public boolean wasLastResolutionLlmBased() {
        return lastResolutionUsedLlm;
    }

    String detectFromRepository(String scmUrl, String scmRevision) {
        if (scmUrl == null || scmRevision == null) {
            return null;
        }
        Matcher matcher = GITHUB_URL_PATTERN.matcher(scmUrl);
        if (!matcher.matches()) {
            log.debug("SCM URL is not a GitHub URL, skipping file-based detection: {}", scmUrl);
            return null;
        }
        String owner = matcher.group(1);
        String repo = matcher.group(2);

        try {
            if (fileExists(owner, repo, scmRevision, "pom.xml")) {
                log.debug("Detected MVN build type from pom.xml in {}/{}", owner, repo);
                return "MVN";
            }
            if (fileExists(owner, repo, scmRevision, "build.gradle")
                    || fileExists(owner, repo, scmRevision, "build.gradle.kts")) {
                log.debug("Detected GRADLE build type from build.gradle in {}/{}", owner, repo);
                return "GRADLE";
            }
            if (fileExists(owner, repo, scmRevision, "package.json")) {
                log.debug("Detected NPM build type from package.json in {}/{}", owner, repo);
                return "NPM";
            }
            if (fileExists(owner, repo, scmRevision, "build.sbt")) {
                log.debug("Detected SBT build type from build.sbt in {}/{}", owner, repo);
                return "SBT";
            }
        } catch (Exception e) {
            log.warn("Error during file-based build type detection for {}/{}: {}", owner, repo, e.getMessage());
        }

        return null;
    }

    private boolean fileExists(String owner, String repo, String ref, String path) throws IOException {
        String url = githubRawBaseUrl + "/" + owner + "/" + repo + "/" + ref + "/" + path;
        HttpGet get = new HttpGet(url);
        HttpResponse response = httpClient.execute(get);
        int statusCode = response.getStatusLine().getStatusCode();
        EntityUtils.consumeQuietly(response.getEntity());
        return statusCode == 200;
    }

    private String queryLlm(Set<GAV> gavs, String scmUrl) {
        if (!config.isEnabled() || httpClient == null) {
            return null;
        }
        GAV primary = gavs.stream().sorted().findFirst().orElse(null);
        if (primary == null) {
            return null;
        }
        try {
            String requestBody = buildLlmRequestBody(primary, gavs, scmUrl);
            String responseBody = callLlm(requestBody);
            return extractBuildType(responseBody, primary);
        } catch (Exception e) {
            log.warn("Unexpected error during LLM build type resolution for {}: {}", primary, e.getMessage());
            return null;
        }
    }

    private String buildLlmRequestBody(GAV primary, Set<GAV> gavs, String scmUrl) throws IOException {
        StringBuilder userContent = new StringBuilder();
        userContent.append("What build system does this project use?\n");
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
        return EntityUtils.toString(response.getEntity(), "UTF-8");
    }

    private String extractBuildType(String responseBody, GAV gav) {
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
                log.debug("LLM could not determine build type for {}", gav);
                return null;
            }

            String upper = content.toUpperCase();
            if (VALID_BUILD_TYPES.contains(upper)) {
                return upper;
            }
            if (BUILD_TYPE_ALIASES.containsKey(upper)) {
                return BUILD_TYPE_ALIASES.get(upper);
            }

            for (String token : upper.split("\\s+")) {
                String cleaned = token.replaceAll("[.,;:\"'`]+", "");
                if (VALID_BUILD_TYPES.contains(cleaned)) {
                    return cleaned;
                }
                if (BUILD_TYPE_ALIASES.containsKey(cleaned)) {
                    return BUILD_TYPE_ALIASES.get(cleaned);
                }
            }

            log.debug("LLM response did not contain a valid build type for {}: {}", gav, content);
            return null;
        } catch (Exception e) {
            log.warn("Failed to parse LLM response: {}", e.getMessage());
            return null;
        }
    }
}
