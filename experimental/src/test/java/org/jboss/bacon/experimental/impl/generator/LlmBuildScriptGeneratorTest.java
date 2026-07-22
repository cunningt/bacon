package org.jboss.bacon.experimental.impl.generator;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

import org.jboss.bacon.experimental.impl.config.LlmConfig;
import org.jboss.da.model.rest.GAV;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;

public class LlmBuildScriptGeneratorTest {

    @RegisterExtension
    static WireMockExtension wireMock = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    private Path tempDir;

    @BeforeEach
    void setUp() throws IOException {
        tempDir = Files.createTempDirectory("llm-build-script-test-");
    }

    @AfterEach
    void tearDown() {
        LlmBuildScriptGenerator.deleteDirectory(tempDir);
    }

    private LlmConfig createConfig() {
        LlmConfig config = new LlmConfig();
        config.setEnabled(true);
        config.setApiUrl("http://localhost:" + wireMock.getPort() + "/v1");
        config.setModel("test-model");
        config.setConnectTimeoutMs(5000);
        config.setReadTimeoutMs(5000);
        return config;
    }

    private LlmBuildScriptGenerator createGenerator() {
        return new LlmBuildScriptGenerator(createConfig(), tempDir);
    }

    private Set<GAV> singleGav() {
        return Set.of(new GAV("org.apache.commons", "commons-lang3", "3.12.0"));
    }

    private String chatResponse(String content) {
        return "{\"choices\":[{\"message\":{\"content\":\""
                + content.replace("\"", "\\\"").replace("\n", "\\n") + "\"}}]}";
    }

    @Test
    void gradleBuildScript() throws IOException {
        Files.writeString(
                tempDir.resolve("build.gradle"),
                "apply plugin: 'java'\ntask integrationTest(type: Test) {}");
        Files.writeString(tempDir.resolve("README.md"), "# My Project\nBuild with gradle publish");

        String expectedScript = "gradle --no-daemon --stacktrace -x test -x integrationTest publish";
        wireMock.stubFor(
                post(urlEqualTo("/v1/chat/completions")).willReturn(
                        aResponse().withStatus(200)
                                .withHeader("Content-Type", "application/json")
                                .withBody(chatResponse(expectedScript))));

        LlmBuildScriptGenerator generator = createGenerator();
        String result = generator.generateBuildScript(singleGav(), "GRADLE", "https://github.com/owner/repo", "main");

        assertThat(result).isEqualTo(expectedScript);
    }

    @Test
    void mavenBuildScript() throws IOException {
        Files.writeString(tempDir.resolve("pom.xml"), "<project><packaging>jar</packaging></project>");
        Files.writeString(tempDir.resolve("README.md"), "# My Project");

        String expectedScript = "mvn deploy -DskipTests";
        wireMock.stubFor(
                post(urlEqualTo("/v1/chat/completions")).willReturn(
                        aResponse().withStatus(200)
                                .withHeader("Content-Type", "application/json")
                                .withBody(chatResponse(expectedScript))));

        LlmBuildScriptGenerator generator = createGenerator();
        String result = generator.generateBuildScript(singleGav(), "MVN", "https://github.com/owner/repo", "v1.0");

        assertThat(result).isEqualTo(expectedScript);
    }

    @Test
    void unknownResponse() throws IOException {
        Files.writeString(tempDir.resolve("pom.xml"), "<project/>");

        wireMock.stubFor(
                post(urlEqualTo("/v1/chat/completions")).willReturn(
                        aResponse().withStatus(200)
                                .withHeader("Content-Type", "application/json")
                                .withBody(chatResponse("UNKNOWN"))));

        LlmBuildScriptGenerator generator = createGenerator();
        String result = generator.generateBuildScript(singleGav(), "MVN", null, null);

        assertThat(result).isNull();
    }

    @Test
    void serverError() throws IOException {
        Files.writeString(tempDir.resolve("pom.xml"), "<project/>");

        wireMock.stubFor(
                post(urlEqualTo("/v1/chat/completions"))
                        .willReturn(aResponse().withStatus(500).withBody("Internal Server Error")));

        LlmBuildScriptGenerator generator = createGenerator();
        String result = generator.generateBuildScript(singleGav(), "MVN", null, null);

        assertThat(result).isNull();
    }

    @Test
    void disabledConfig() {
        LlmConfig config = new LlmConfig();
        config.setEnabled(false);
        config.setConnectTimeoutMs(5000);
        config.setReadTimeoutMs(5000);

        LlmBuildScriptGenerator generator = new LlmBuildScriptGenerator(config, tempDir);
        String result = generator.generateBuildScript(singleGav(), "MVN", null, null);

        assertThat(result).isNull();
        wireMock.verify(0, postRequestedFor(urlEqualTo("/v1/chat/completions")));
    }

    @Test
    void malformedJson() throws IOException {
        Files.writeString(tempDir.resolve("pom.xml"), "<project/>");

        wireMock.stubFor(
                post(urlEqualTo("/v1/chat/completions")).willReturn(
                        aResponse().withStatus(200)
                                .withHeader("Content-Type", "application/json")
                                .withBody("not valid json")));

        LlmBuildScriptGenerator generator = createGenerator();
        String result = generator.generateBuildScript(singleGav(), "MVN", null, null);

        assertThat(result).isNull();
    }

    @Test
    void emptyGavSet() {
        LlmBuildScriptGenerator generator = createGenerator();
        String result = generator.generateBuildScript(Set.of(), "MVN", null, null);

        assertThat(result).isNull();
    }

    @Test
    void noProjectFilesReturnsNull() {
        LlmBuildScriptGenerator generator = createGenerator();
        String result = generator.generateBuildScript(singleGav(), "MVN", null, null);

        assertThat(result).isNull();
        wireMock.verify(0, postRequestedFor(urlEqualTo("/v1/chat/completions")));
    }

    @Test
    void requestIncludesFileContents() throws IOException {
        Files.writeString(tempDir.resolve("pom.xml"), "<project><artifactId>my-artifact</artifactId></project>");
        Files.writeString(tempDir.resolve("README.md"), "# Build instructions here");

        wireMock.stubFor(
                post(urlEqualTo("/v1/chat/completions")).willReturn(
                        aResponse().withStatus(200)
                                .withHeader("Content-Type", "application/json")
                                .withBody(chatResponse("mvn deploy"))));

        LlmBuildScriptGenerator generator = createGenerator();
        generator.generateBuildScript(singleGav(), "MVN", "https://github.com/owner/repo", "v1.0");

        wireMock.verify(
                postRequestedFor(urlEqualTo("/v1/chat/completions"))
                        .withRequestBody(containing("\"model\":\"test-model\""))
                        .withRequestBody(containing("commons-lang3"))
                        .withRequestBody(containing("org.apache.commons"))
                        .withRequestBody(containing("pom.xml"))
                        .withRequestBody(containing("my-artifact"))
                        .withRequestBody(containing("README.md"))
                        .withRequestBody(containing("Build instructions here")));
    }

    @Test
    void gradleRequestUsesGradleSystemPrompt() throws IOException {
        Files.writeString(tempDir.resolve("build.gradle"), "apply plugin: 'java'");

        wireMock.stubFor(
                post(urlEqualTo("/v1/chat/completions")).willReturn(
                        aResponse().withStatus(200)
                                .withHeader("Content-Type", "application/json")
                                .withBody(chatResponse("gradle --no-daemon --stacktrace -x test publish"))));

        LlmBuildScriptGenerator generator = createGenerator();
        generator.generateBuildScript(singleGav(), "GRADLE", null, null);

        wireMock.verify(
                postRequestedFor(urlEqualTo("/v1/chat/completions"))
                        .withRequestBody(containing("gradle --no-daemon --stacktrace publish"))
                        .withRequestBody(containing("-x test")));
    }

    @Test
    void mvnRequestUsesMvnSystemPrompt() throws IOException {
        Files.writeString(tempDir.resolve("pom.xml"), "<project/>");

        wireMock.stubFor(
                post(urlEqualTo("/v1/chat/completions")).willReturn(
                        aResponse().withStatus(200)
                                .withHeader("Content-Type", "application/json")
                                .withBody(chatResponse("mvn deploy"))));

        LlmBuildScriptGenerator generator = createGenerator();
        generator.generateBuildScript(singleGav(), "MVN", null, null);

        wireMock.verify(
                postRequestedFor(urlEqualTo("/v1/chat/completions"))
                        .withRequestBody(containing("mvn deploy")));
    }

    // --- readProjectFiles unit tests ---

    @Test
    void readProjectFilesGradle() throws IOException {
        Files.writeString(tempDir.resolve("build.gradle"), "apply plugin: 'java'");
        Files.writeString(tempDir.resolve("settings.gradle"), "rootProject.name = 'test'");
        Files.createDirectories(tempDir.resolve("buildSrc/src/main/java"));
        Files.writeString(tempDir.resolve("buildSrc/src/main/java/MyPlugin.java"), "class MyPlugin {}");
        Files.createDirectories(tempDir.resolve("gradle"));
        Files.writeString(tempDir.resolve("gradle/conventions.gradle.kts"), "// conventions");
        Files.writeString(tempDir.resolve("README.md"), "# Project");

        LlmBuildScriptGenerator generator = createGenerator();
        Map<String, String> files = generator.readProjectFiles(tempDir, "GRADLE");

        assertThat(files).containsKeys(
                "README.md",
                "build.gradle",
                "settings.gradle",
                "buildSrc/src/main/java/MyPlugin.java",
                "gradle/conventions.gradle.kts");
    }

    @Test
    void readProjectFilesMvn() throws IOException {
        Files.writeString(tempDir.resolve("pom.xml"), "<project/>");
        Files.writeString(tempDir.resolve("build.gradle"), "should not be read");
        Files.writeString(tempDir.resolve("README.md"), "# Project");

        LlmBuildScriptGenerator generator = createGenerator();
        Map<String, String> files = generator.readProjectFiles(tempDir, "MVN");

        assertThat(files).containsKeys("README.md", "pom.xml");
        assertThat(files).doesNotContainKey("build.gradle");
    }

    @Test
    void readProjectFilesNpm() throws IOException {
        Files.writeString(tempDir.resolve("package.json"), "{\"name\": \"test\"}");

        LlmBuildScriptGenerator generator = createGenerator();
        Map<String, String> files = generator.readProjectFiles(tempDir, "NPM");

        assertThat(files).containsKey("package.json");
    }

    @Test
    void readProjectFilesSbt() throws IOException {
        Files.writeString(tempDir.resolve("build.sbt"), "name := \"test\"");

        LlmBuildScriptGenerator generator = createGenerator();
        Map<String, String> files = generator.readProjectFiles(tempDir, "SBT");

        assertThat(files).containsKey("build.sbt");
    }

    @Test
    void fileTruncation() throws IOException {
        String longContent = "x".repeat(10000);
        Files.writeString(tempDir.resolve("pom.xml"), longContent);

        LlmBuildScriptGenerator generator = createGenerator();
        Map<String, String> files = generator.readProjectFiles(tempDir, "MVN");

        assertThat(files.get("pom.xml").length()).isLessThan(longContent.length());
        assertThat(files.get("pom.xml")).endsWith("... [truncated]");
    }
}
