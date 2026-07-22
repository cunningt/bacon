package org.jboss.bacon.experimental.impl.generator;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;

import org.jboss.bacon.experimental.impl.config.LlmConfig;
import org.jboss.da.model.rest.GAV;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;

public class LlmBuildTypeResolverTest {

    @RegisterExtension
    static WireMockExtension wireMock = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    private LlmConfig createConfig() {
        LlmConfig config = new LlmConfig();
        config.setEnabled(true);
        config.setApiUrl("http://localhost:" + wireMock.getPort() + "/v1");
        config.setModel("test-model");
        config.setConnectTimeoutMs(5000);
        config.setReadTimeoutMs(5000);
        return config;
    }

    private LlmBuildTypeResolver createResolver() {
        return new LlmBuildTypeResolver(createConfig(), "http://localhost:" + wireMock.getPort());
    }

    private LlmBuildTypeResolver createResolverLlmDisabled() {
        LlmConfig config = new LlmConfig();
        config.setEnabled(false);
        config.setConnectTimeoutMs(5000);
        config.setReadTimeoutMs(5000);
        return new LlmBuildTypeResolver(config, "http://localhost:" + wireMock.getPort());
    }

    private Set<GAV> singleGav() {
        return Set.of(new GAV("org.apache.commons", "commons-lang3", "3.12.0"));
    }

    private String chatResponse(String content) {
        return "{\"choices\":[{\"message\":{\"content\":\"" + content + "\"}}]}";
    }

    // --- File-based detection tests ---

    @Test
    void detectsMavenFromPomXml() {
        wireMock.stubFor(
                get(urlEqualTo("/spring-projects/spring-framework/v7.0.7/pom.xml"))
                        .willReturn(aResponse().withStatus(200).withBody("<project/>")));

        LlmBuildTypeResolver resolver = createResolver();
        String result = resolver.resolveBuildType(
                singleGav(),
                "https://github.com/spring-projects/spring-framework",
                "v7.0.7");

        assertThat(result).isEqualTo("MVN");
    }

    @Test
    void detectsGradleFromBuildGradle() {
        wireMock.stubFor(
                get(urlEqualTo("/owner/repo/main/pom.xml")).willReturn(aResponse().withStatus(404)));
        wireMock.stubFor(
                get(urlEqualTo("/owner/repo/main/build.gradle"))
                        .willReturn(aResponse().withStatus(200).withBody("apply plugin: 'java'")));

        LlmBuildTypeResolver resolver = createResolver();
        String result = resolver.resolveBuildType(
                singleGav(),
                "https://github.com/owner/repo.git",
                "main");

        assertThat(result).isEqualTo("GRADLE");
    }

    @Test
    void detectsGradleFromBuildGradleKts() {
        wireMock.stubFor(
                get(urlEqualTo("/owner/repo/main/pom.xml")).willReturn(aResponse().withStatus(404)));
        wireMock.stubFor(
                get(urlEqualTo("/owner/repo/main/build.gradle")).willReturn(aResponse().withStatus(404)));
        wireMock.stubFor(
                get(urlEqualTo("/owner/repo/main/build.gradle.kts"))
                        .willReturn(aResponse().withStatus(200).withBody("plugins { }")));

        LlmBuildTypeResolver resolver = createResolver();
        String result = resolver.resolveBuildType(
                singleGav(),
                "https://github.com/owner/repo",
                "main");

        assertThat(result).isEqualTo("GRADLE");
    }

    @Test
    void detectsNpmFromPackageJson() {
        wireMock.stubFor(
                get(urlEqualTo("/owner/repo/v1.0.0/pom.xml")).willReturn(aResponse().withStatus(404)));
        wireMock.stubFor(
                get(urlEqualTo("/owner/repo/v1.0.0/build.gradle")).willReturn(aResponse().withStatus(404)));
        wireMock.stubFor(
                get(urlEqualTo("/owner/repo/v1.0.0/build.gradle.kts")).willReturn(aResponse().withStatus(404)));
        wireMock.stubFor(
                get(urlEqualTo("/owner/repo/v1.0.0/package.json"))
                        .willReturn(aResponse().withStatus(200).withBody("{}")));

        LlmBuildTypeResolver resolver = createResolver();
        String result = resolver.resolveBuildType(
                singleGav(),
                "https://github.com/owner/repo",
                "v1.0.0");

        assertThat(result).isEqualTo("NPM");
    }

    @Test
    void detectsSbtFromBuildSbt() {
        wireMock.stubFor(
                get(urlEqualTo("/owner/repo/v1.0.0/pom.xml")).willReturn(aResponse().withStatus(404)));
        wireMock.stubFor(
                get(urlEqualTo("/owner/repo/v1.0.0/build.gradle")).willReturn(aResponse().withStatus(404)));
        wireMock.stubFor(
                get(urlEqualTo("/owner/repo/v1.0.0/build.gradle.kts")).willReturn(aResponse().withStatus(404)));
        wireMock.stubFor(
                get(urlEqualTo("/owner/repo/v1.0.0/package.json")).willReturn(aResponse().withStatus(404)));
        wireMock.stubFor(
                get(urlEqualTo("/owner/repo/v1.0.0/build.sbt"))
                        .willReturn(aResponse().withStatus(200).withBody("name := \"project\"")));

        LlmBuildTypeResolver resolver = createResolver();
        String result = resolver.resolveBuildType(
                singleGav(),
                "https://github.com/owner/repo",
                "v1.0.0");

        assertThat(result).isEqualTo("SBT");
    }

    @Test
    void pomXmlTakesPriorityOverBuildGradle() {
        wireMock.stubFor(
                get(urlEqualTo("/owner/repo/main/pom.xml"))
                        .willReturn(aResponse().withStatus(200).withBody("<project/>")));
        wireMock.stubFor(
                get(urlEqualTo("/owner/repo/main/build.gradle"))
                        .willReturn(aResponse().withStatus(200).withBody("apply plugin: 'java'")));

        LlmBuildTypeResolver resolver = createResolver();
        String result = resolver.resolveBuildType(
                singleGav(),
                "https://github.com/owner/repo",
                "main");

        assertThat(result).isEqualTo("MVN");
    }

    @Test
    void noFilesDetectedFallsToLlm() {
        wireMock.stubFor(get(urlPathMatching("/owner/repo/main/.*")).willReturn(aResponse().withStatus(404)));
        wireMock.stubFor(
                post(urlEqualTo("/v1/chat/completions")).willReturn(
                        aResponse().withStatus(200)
                                .withHeader("Content-Type", "application/json")
                                .withBody(chatResponse("GRADLE"))));

        LlmBuildTypeResolver resolver = createResolver();
        String result = resolver.resolveBuildType(
                singleGav(),
                "https://github.com/owner/repo",
                "main");

        assertThat(result).isEqualTo("GRADLE");
        wireMock.verify(postRequestedFor(urlEqualTo("/v1/chat/completions")));
    }

    @Test
    void nonGithubUrlSkipsFileCheckFallsToLlm() {
        wireMock.stubFor(
                post(urlEqualTo("/v1/chat/completions")).willReturn(
                        aResponse().withStatus(200)
                                .withHeader("Content-Type", "application/json")
                                .withBody(chatResponse("MVN"))));

        LlmBuildTypeResolver resolver = createResolver();
        String result = resolver.resolveBuildType(
                singleGav(),
                "https://gitlab.com/owner/repo",
                "main");

        assertThat(result).isEqualTo("MVN");
        wireMock.verify(0, getRequestedFor(urlPathMatching("/owner/repo/.*")));
        wireMock.verify(postRequestedFor(urlEqualTo("/v1/chat/completions")));
    }

    @Test
    void nullScmUrlSkipsFileCheckFallsToLlm() {
        wireMock.stubFor(
                post(urlEqualTo("/v1/chat/completions")).willReturn(
                        aResponse().withStatus(200)
                                .withHeader("Content-Type", "application/json")
                                .withBody(chatResponse("MVN"))));

        LlmBuildTypeResolver resolver = createResolver();
        String result = resolver.resolveBuildType(singleGav(), null, null);

        assertThat(result).isEqualTo("MVN");
    }

    // --- LLM fallback tests ---

    @Test
    void llmReturnsMvn() {
        wireMock.stubFor(
                post(urlEqualTo("/v1/chat/completions")).willReturn(
                        aResponse().withStatus(200)
                                .withHeader("Content-Type", "application/json")
                                .withBody(chatResponse("MVN"))));

        LlmBuildTypeResolver resolver = createResolver();
        String result = resolver.resolveBuildType(singleGav(), "https://gitlab.com/owner/repo", "main");

        assertThat(result).isEqualTo("MVN");
    }

    @Test
    void llmReturnsGradle() {
        wireMock.stubFor(
                post(urlEqualTo("/v1/chat/completions")).willReturn(
                        aResponse().withStatus(200)
                                .withHeader("Content-Type", "application/json")
                                .withBody(chatResponse("GRADLE"))));

        LlmBuildTypeResolver resolver = createResolver();
        String result = resolver.resolveBuildType(singleGav(), "https://gitlab.com/owner/repo", "main");

        assertThat(result).isEqualTo("GRADLE");
    }

    @Test
    void llmReturnsMavenAlias() {
        wireMock.stubFor(
                post(urlEqualTo("/v1/chat/completions")).willReturn(
                        aResponse().withStatus(200)
                                .withHeader("Content-Type", "application/json")
                                .withBody(chatResponse("Maven"))));

        LlmBuildTypeResolver resolver = createResolver();
        String result = resolver.resolveBuildType(singleGav(), "https://gitlab.com/owner/repo", "main");

        assertThat(result).isEqualTo("MVN");
    }

    @Test
    void llmReturnsBuildTypeInProse() {
        wireMock.stubFor(
                post(urlEqualTo("/v1/chat/completions")).willReturn(
                        aResponse().withStatus(200)
                                .withHeader("Content-Type", "application/json")
                                .withBody(chatResponse("The build system is GRADLE for this project."))));

        LlmBuildTypeResolver resolver = createResolver();
        String result = resolver.resolveBuildType(singleGav(), "https://gitlab.com/owner/repo", "main");

        assertThat(result).isEqualTo("GRADLE");
    }

    @Test
    void llmReturnsUnknown() {
        wireMock.stubFor(
                post(urlEqualTo("/v1/chat/completions")).willReturn(
                        aResponse().withStatus(200)
                                .withHeader("Content-Type", "application/json")
                                .withBody(chatResponse("UNKNOWN"))));

        LlmBuildTypeResolver resolver = createResolver();
        String result = resolver.resolveBuildType(singleGav(), "https://gitlab.com/owner/repo", "main");

        assertThat(result).isNull();
    }

    @Test
    void llmServerError() {
        wireMock.stubFor(
                post(urlEqualTo("/v1/chat/completions"))
                        .willReturn(aResponse().withStatus(500).withBody("Internal Server Error")));

        LlmBuildTypeResolver resolver = createResolver();
        String result = resolver.resolveBuildType(singleGav(), "https://gitlab.com/owner/repo", "main");

        assertThat(result).isNull();
    }

    @Test
    void llmDisabledAndNonGithubReturnsNull() {
        LlmBuildTypeResolver resolver = createResolverLlmDisabled();
        String result = resolver.resolveBuildType(singleGav(), "https://gitlab.com/owner/repo", "main");

        assertThat(result).isNull();
        wireMock.verify(0, postRequestedFor(urlEqualTo("/v1/chat/completions")));
    }

    @Test
    void fileDetectionSucceedsLlmNotCalled() {
        wireMock.stubFor(
                get(urlEqualTo("/owner/repo/main/pom.xml"))
                        .willReturn(aResponse().withStatus(200).withBody("<project/>")));

        LlmBuildTypeResolver resolver = createResolver();
        String result = resolver.resolveBuildType(
                singleGav(),
                "https://github.com/owner/repo",
                "main");

        assertThat(result).isEqualTo("MVN");
        wireMock.verify(0, postRequestedFor(urlEqualTo("/v1/chat/completions")));
    }

    @Test
    void llmRequestIncludesGavAndScmUrl() {
        wireMock.stubFor(
                post(urlEqualTo("/v1/chat/completions")).willReturn(
                        aResponse().withStatus(200)
                                .withHeader("Content-Type", "application/json")
                                .withBody(chatResponse("MVN"))));

        LlmBuildTypeResolver resolver = createResolver();
        resolver.resolveBuildType(singleGav(), "https://gitlab.com/owner/repo", "main");

        wireMock.verify(
                postRequestedFor(urlEqualTo("/v1/chat/completions"))
                        .withRequestBody(containing("\"model\":\"test-model\""))
                        .withRequestBody(containing("commons-lang3"))
                        .withRequestBody(containing("org.apache.commons"))
                        .withRequestBody(containing("https://gitlab.com/owner/repo")));
    }

    @Test
    void emptyGavSetWithNonGithubUrl() {
        LlmBuildTypeResolver resolver = createResolver();
        String result = resolver.resolveBuildType(Set.of(), "https://gitlab.com/owner/repo", "main");

        assertThat(result).isNull();
    }

    @Test
    void malformedJsonResponse() {
        wireMock.stubFor(
                post(urlEqualTo("/v1/chat/completions")).willReturn(
                        aResponse().withStatus(200)
                                .withHeader("Content-Type", "application/json")
                                .withBody("not valid json")));

        LlmBuildTypeResolver resolver = createResolver();
        String result = resolver.resolveBuildType(singleGav(), "https://gitlab.com/owner/repo", "main");

        assertThat(result).isNull();
    }

    @Test
    void fileDetectionWithLlmDisabledStillWorks() {
        wireMock.stubFor(
                get(urlEqualTo("/owner/repo/main/pom.xml"))
                        .willReturn(aResponse().withStatus(200).withBody("<project/>")));

        LlmBuildTypeResolver resolver = createResolverLlmDisabled();
        String result = resolver.resolveBuildType(
                singleGav(),
                "https://github.com/owner/repo",
                "main");

        assertThat(result).isEqualTo("MVN");
    }
}
