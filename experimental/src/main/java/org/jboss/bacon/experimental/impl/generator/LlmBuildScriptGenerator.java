package org.jboss.bacon.experimental.impl.generator;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
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
public class LlmBuildScriptGenerator {

    private static final String GRADLE_SYSTEM_PROMPT = "You are a PNC build script generator. "
            + "Given a Gradle project's build files and documentation, generate a build script. "
            + "The base command is: gradle --no-daemon --stacktrace publish\n\n"
            + "You MUST discover all test tasks and exclude them with -x flags. Always include -x test. "
            + "Search the build files for additional test tasks (integrationTest, functionalTest, smokeTest, "
            + "e2eTest, dockerTest, containerTest, acceptanceTest, jmh, benchmark, etc.) and exclude each "
            + "with -x <taskName>.\n\n"
            + "Also check for checkstyle, pmd, spotbugs task configurations and exclude them if present "
            + "(e.g. -x checkstyleMain -x checkstyleTest).\n\n"
            + "Check the README or build docs for required build flags (system properties, memory settings, "
            + "specific publish targets like publishToMavenLocal).\n\n"
            + "Respond with ONLY the build script command(s), one per line, nothing else.";

    private static final String MVN_SYSTEM_PROMPT = "You are a PNC build script generator. "
            + "Given a Maven project's pom.xml and documentation, generate a build script. "
            + "The base command is: mvn deploy\n\n"
            + "Check the pom.xml and README for required profiles, system properties, or flags needed "
            + "to build the project successfully. Common examples: -DskipTests, specific profiles (-Prelease), "
            + "property overrides, additional goals.\n\n"
            + "Respond with ONLY the build script command(s), one per line, nothing else.";

    private static final String GENERIC_SYSTEM_PROMPT = "You are a PNC build script generator. "
            + "Given a project's build files and documentation, generate a build script appropriate "
            + "for the project's build system.\n\n"
            + "Respond with ONLY the build script command(s), one per line, nothing else.";

    private static final int MAX_FILE_CHARS = 8000;
    private static final int MAX_TOTAL_CHARS = 40000;
    private static final long CLONE_TIMEOUT_SECONDS = 60;

    private static final Set<String> DOC_FILES = Set.of("README.md", "CONTRIBUTING.md", "BUILDING.md");

    private static final Set<String> GRADLE_BUILD_EXTENSIONS = Set.of(".gradle", ".gradle.kts", ".java", ".kt");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final LlmConfig config;
    private final CloseableHttpClient httpClient;
    private final Path testProjectDir;

    public LlmBuildScriptGenerator(LlmConfig config) {
        this(config, null);
    }

    LlmBuildScriptGenerator(LlmConfig config, Path testProjectDir) {
        this.config = config;
        this.testProjectDir = testProjectDir;
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

    public String generateBuildScript(Set<GAV> gavs, String buildType, String scmUrl, String scmRevision) {
        if (!config.isEnabled() || httpClient == null) {
            return null;
        }
        GAV primary = gavs.stream().sorted().findFirst().orElse(null);
        if (primary == null) {
            return null;
        }

        Path projectDir = null;
        boolean cloned = false;
        try {
            if (testProjectDir != null) {
                projectDir = testProjectDir;
            } else {
                projectDir = cloneRepository(scmUrl, scmRevision);
                cloned = true;
                if (projectDir == null) {
                    return null;
                }
            }

            Map<String, String> files = readProjectFiles(projectDir, buildType);
            if (files.isEmpty()) {
                log.debug("No project files found for {}", primary);
                return null;
            }

            String requestBody = buildRequestBody(primary, gavs, buildType, scmUrl, files);
            String responseBody = callLlm(requestBody);
            return extractBuildScript(responseBody, primary);
        } catch (Exception e) {
            log.warn("Unexpected error during LLM build script generation for {}: {}", primary, e.getMessage());
            return null;
        } finally {
            if (cloned && projectDir != null) {
                deleteDirectory(projectDir);
            }
        }
    }

    Path cloneRepository(String scmUrl, String scmRevision) {
        if (scmUrl == null) {
            return null;
        }
        try {
            Path tempDir = Files.createTempDirectory("bacon-llm-");
            if (scmRevision != null && tryClone(scmUrl, scmRevision, tempDir)) {
                return tempDir;
            }

            deleteDirectory(tempDir);
            tempDir = Files.createTempDirectory("bacon-llm-");
            if (tryCloneAndCheckout(scmUrl, scmRevision, tempDir)) {
                return tempDir;
            }

            deleteDirectory(tempDir);
            return null;
        } catch (Exception e) {
            log.warn("Failed to clone repository {}: {}", scmUrl, e.getMessage());
            return null;
        }
    }

    private boolean tryClone(String scmUrl, String revision, Path targetDir) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(
                "git",
                "clone",
                "--depth",
                "1",
                "--branch",
                revision,
                scmUrl,
                targetDir.toString());
        pb.redirectErrorStream(true);
        Process process = pb.start();
        try {
            return process.waitFor(CLONE_TIMEOUT_SECONDS, TimeUnit.SECONDS) && process.exitValue() == 0;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            return false;
        }
    }

    private boolean tryCloneAndCheckout(String scmUrl, String scmRevision, Path targetDir) throws IOException {
        ProcessBuilder clonePb = new ProcessBuilder("git", "clone", "--depth", "1", scmUrl, targetDir.toString());
        clonePb.redirectErrorStream(true);
        Process cloneProcess = clonePb.start();
        try {
            if (!cloneProcess.waitFor(CLONE_TIMEOUT_SECONDS, TimeUnit.SECONDS) || cloneProcess.exitValue() != 0) {
                return false;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            cloneProcess.destroyForcibly();
            return false;
        }

        if (scmRevision == null) {
            return true;
        }

        ProcessBuilder checkoutPb = new ProcessBuilder("git", "-C", targetDir.toString(), "checkout", scmRevision);
        checkoutPb.redirectErrorStream(true);
        Process checkoutProcess = checkoutPb.start();
        try {
            return checkoutProcess.waitFor(CLONE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    && checkoutProcess.exitValue() == 0;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            checkoutProcess.destroyForcibly();
            return false;
        }
    }

    Map<String, String> readProjectFiles(Path projectDir, String buildType) {
        Map<String, String> files = new LinkedHashMap<>();
        int totalChars = 0;

        for (String docFile : DOC_FILES) {
            if (totalChars >= MAX_TOTAL_CHARS) {
                break;
            }
            totalChars += readFileIfExists(projectDir, docFile, files);
        }

        if ("GRADLE".equals(buildType)) {
            for (String f : List.of("build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts")) {
                if (totalChars >= MAX_TOTAL_CHARS) {
                    break;
                }
                totalChars += readFileIfExists(projectDir, f, files);
            }
            totalChars += readDirectoryFiles(projectDir, "buildSrc", files, totalChars);
            totalChars += readDirectoryFiles(projectDir, "gradle", files, totalChars);
        } else if ("MVN".equals(buildType)) {
            totalChars += readFileIfExists(projectDir, "pom.xml", files);
        } else if ("NPM".equals(buildType)) {
            totalChars += readFileIfExists(projectDir, "package.json", files);
        } else if ("SBT".equals(buildType)) {
            totalChars += readFileIfExists(projectDir, "build.sbt", files);
        }

        return files;
    }

    private int readFileIfExists(Path projectDir, String relativePath, Map<String, String> files) {
        Path file = projectDir.resolve(relativePath);
        if (!Files.isRegularFile(file)) {
            return 0;
        }
        try {
            String content = Files.readString(file);
            if (content.length() > MAX_FILE_CHARS) {
                content = content.substring(0, MAX_FILE_CHARS) + "\n... [truncated]";
            }
            files.put(relativePath, content);
            return content.length();
        } catch (IOException e) {
            log.debug("Could not read file {}: {}", file, e.getMessage());
            return 0;
        }
    }

    private int readDirectoryFiles(Path projectDir, String dirName, Map<String, String> files, int currentTotal) {
        Path dir = projectDir.resolve(dirName);
        if (!Files.isDirectory(dir)) {
            return 0;
        }
        int added = 0;
        try {
            List<Path> matchingFiles = Files.walk(dir)
                    .filter(Files::isRegularFile)
                    .filter(p -> GRADLE_BUILD_EXTENSIONS.stream().anyMatch(ext -> p.toString().endsWith(ext)))
                    .sorted()
                    .collect(Collectors.toList());

            for (Path file : matchingFiles) {
                if (currentTotal + added >= MAX_TOTAL_CHARS) {
                    break;
                }
                String relativePath = projectDir.relativize(file).toString();
                added += readFileIfExists(projectDir, relativePath, files);
            }
        } catch (IOException e) {
            log.debug("Could not walk directory {}: {}", dir, e.getMessage());
        }
        return added;
    }

    private String buildRequestBody(
            GAV primary,
            Set<GAV> gavs,
            String buildType,
            String scmUrl,
            Map<String, String> files) throws IOException {
        String systemPrompt = getSystemPrompt(buildType);

        StringBuilder userContent = new StringBuilder();
        userContent.append("Generate a build script for this project:\n");
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

        userContent.append("\n\nProject files:\n");
        for (Map.Entry<String, String> entry : files.entrySet()) {
            userContent.append("\n--- ").append(entry.getKey()).append(" ---\n");
            userContent.append(entry.getValue()).append("\n");
        }

        Map<String, Object> request = Map.of(
                "model",
                config.getModel(),
                "messages",
                List.of(
                        Map.of("role", "system", "content", systemPrompt),
                        Map.of("role", "user", "content", userContent.toString())),
                "temperature",
                config.getTemperature());

        return MAPPER.writeValueAsString(request);
    }

    private String getSystemPrompt(String buildType) {
        if ("GRADLE".equals(buildType)) {
            return GRADLE_SYSTEM_PROMPT;
        } else if ("MVN".equals(buildType)) {
            return MVN_SYSTEM_PROMPT;
        }
        return GENERIC_SYSTEM_PROMPT;
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

    private String extractBuildScript(String responseBody, GAV gav) {
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
                log.debug("LLM could not generate build script for {}", gav);
                return null;
            }

            return content;
        } catch (Exception e) {
            log.warn("Failed to parse LLM response: {}", e.getMessage());
            return null;
        }
    }

    static void deleteDirectory(Path dir) {
        if (dir == null || !Files.exists(dir)) {
            return;
        }
        try {
            Files.walkFileTree(dir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.delete(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path d, IOException exc) throws IOException {
                    Files.delete(d);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            log.debug("Could not fully delete temp directory {}: {}", dir, e.getMessage());
        }
    }
}
