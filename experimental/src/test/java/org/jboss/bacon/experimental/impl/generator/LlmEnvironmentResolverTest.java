package org.jboss.bacon.experimental.impl.generator;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;

import org.jboss.bacon.experimental.impl.config.LlmConfig;
import org.jboss.da.model.rest.GAV;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;

public class LlmEnvironmentResolverTest {

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

    private LlmEnvironmentResolver createResolver() {
        return new LlmEnvironmentResolver(createConfig());
    }

    private Set<GAV> singleGav() {
        return Set.of(new GAV("org.apache.commons", "commons-lang3", "3.12.0"));
    }

    private String chatResponse(String content) {
        return "{\"choices\":[{\"message\":{\"content\":\"" + content + "\"}}]}";
    }

    @Test
    void successfulMvnResolution() {
        wireMock.stubFor(
                post(urlEqualTo("/v1/chat/completions")).willReturn(
                        aResponse().withStatus(200)
                                .withHeader("Content-Type", "application/json")
                                .withBody(chatResponse("OpenJDK 17.0; RHEL 8; Mvn 3.9.6"))));

        LlmEnvironmentResolver resolver = createResolver();
        String result = resolver.resolveEnvironment(singleGav(), "MVN", "https://github.com/apache/commons-lang");

        assertThat(result).isEqualTo("OpenJDK 17.0; RHEL 8; Mvn 3.9.6");
    }

    @Test
    void successfulGradleResolution() {
        wireMock.stubFor(
                post(urlEqualTo("/v1/chat/completions")).willReturn(
                        aResponse().withStatus(200)
                                .withHeader("Content-Type", "application/json")
                                .withBody(
                                        chatResponse(
                                                "OpenJDK 17.0; RHEL 8; Mvn 3.8.6; Gradle 8.6; Gettext; JSS"))));

        LlmEnvironmentResolver resolver = createResolver();
        String result = resolver.resolveEnvironment(singleGav(), "GRADLE", "https://github.com/owner/repo");

        assertThat(result).isEqualTo("OpenJDK 17.0; RHEL 8; Mvn 3.8.6; Gradle 8.6; Gettext; JSS");
    }

    @Test
    void successfulSbtResolution() {
        wireMock.stubFor(
                post(urlEqualTo("/v1/chat/completions")).willReturn(
                        aResponse().withStatus(200)
                                .withHeader("Content-Type", "application/json")
                                .withBody(chatResponse("OpenJDK 11.0; Mvn 3.6.3; SBT 1.9.9; gcc-cpp; make"))));

        LlmEnvironmentResolver resolver = createResolver();
        String result = resolver.resolveEnvironment(singleGav(), "SBT", "https://github.com/owner/repo");

        assertThat(result).isEqualTo("OpenJDK 11.0; Mvn 3.6.3; SBT 1.9.9; gcc-cpp; make");
    }

    @Test
    void successfulNpmResolution() {
        wireMock.stubFor(
                post(urlEqualTo("/v1/chat/completions")).willReturn(
                        aResponse().withStatus(200)
                                .withHeader("Content-Type", "application/json")
                                .withBody(chatResponse("OpenJDK 17.0; RHEL 8; Mvn 3.8.6; Nodejs 18; npm 8"))));

        LlmEnvironmentResolver resolver = createResolver();
        String result = resolver.resolveEnvironment(singleGav(), "NPM", "https://github.com/owner/repo");

        assertThat(result).isEqualTo("OpenJDK 17.0; RHEL 8; Mvn 3.8.6; Nodejs 18; npm 8");
    }

    @Test
    void unknownResponse() {
        wireMock.stubFor(
                post(urlEqualTo("/v1/chat/completions")).willReturn(
                        aResponse().withStatus(200)
                                .withHeader("Content-Type", "application/json")
                                .withBody(chatResponse("UNKNOWN"))));

        LlmEnvironmentResolver resolver = createResolver();
        String result = resolver.resolveEnvironment(singleGav(), "MVN", null);

        assertThat(result).isNull();
    }

    @Test
    void serverError() {
        wireMock.stubFor(
                post(urlEqualTo("/v1/chat/completions"))
                        .willReturn(aResponse().withStatus(500).withBody("Internal Server Error")));

        LlmEnvironmentResolver resolver = createResolver();
        String result = resolver.resolveEnvironment(singleGav(), "MVN", null);

        assertThat(result).isNull();
    }

    @Test
    void disabledConfig() {
        LlmConfig config = new LlmConfig();
        config.setEnabled(false);
        config.setConnectTimeoutMs(5000);
        config.setReadTimeoutMs(5000);

        LlmEnvironmentResolver resolver = new LlmEnvironmentResolver(config);
        String result = resolver.resolveEnvironment(singleGav(), "MVN", null);

        assertThat(result).isNull();
        wireMock.verify(0, postRequestedFor(urlEqualTo("/v1/chat/completions")));
    }

    @Test
    void malformedJsonResponse() {
        wireMock.stubFor(
                post(urlEqualTo("/v1/chat/completions")).willReturn(
                        aResponse().withStatus(200)
                                .withHeader("Content-Type", "application/json")
                                .withBody("not valid json")));

        LlmEnvironmentResolver resolver = createResolver();
        String result = resolver.resolveEnvironment(singleGav(), "MVN", null);

        assertThat(result).isNull();
    }

    @Test
    void emptyGavSet() {
        LlmEnvironmentResolver resolver = createResolver();
        String result = resolver.resolveEnvironment(Set.of(), "MVN", null);

        assertThat(result).isNull();
    }

    @Test
    void environmentNameInProse() {
        wireMock.stubFor(
                post(urlEqualTo("/v1/chat/completions")).willReturn(
                        aResponse().withStatus(200)
                                .withHeader("Content-Type", "application/json")
                                .withBody(
                                        chatResponse(
                                                "I recommend OpenJDK 17.0; RHEL 8; Mvn 3.9.6 for this project."))));

        LlmEnvironmentResolver resolver = createResolver();
        String result = resolver.resolveEnvironment(singleGav(), "MVN", null);

        assertThat(result).isEqualTo("OpenJDK 17.0; RHEL 8; Mvn 3.9.6");
    }

    @Test
    void invalidEnvironmentName() {
        wireMock.stubFor(
                post(urlEqualTo("/v1/chat/completions")).willReturn(
                        aResponse().withStatus(200)
                                .withHeader("Content-Type", "application/json")
                                .withBody(chatResponse("NonExistent Environment 99.0"))));

        LlmEnvironmentResolver resolver = createResolver();
        String result = resolver.resolveEnvironment(singleGav(), "MVN", null);

        assertThat(result).isNull();
    }

    @Test
    void requestIncludesProjectContext() {
        wireMock.stubFor(
                post(urlEqualTo("/v1/chat/completions")).willReturn(
                        aResponse().withStatus(200)
                                .withHeader("Content-Type", "application/json")
                                .withBody(chatResponse("OpenJDK 17.0; RHEL 8; Mvn 3.9.6"))));

        LlmEnvironmentResolver resolver = createResolver();
        resolver.resolveEnvironment(singleGav(), "MVN", "https://github.com/apache/commons-lang");

        wireMock.verify(
                postRequestedFor(urlEqualTo("/v1/chat/completions"))
                        .withRequestBody(containing("\"model\":\"test-model\""))
                        .withRequestBody(containing("commons-lang3"))
                        .withRequestBody(containing("org.apache.commons"))
                        .withRequestBody(containing("MVN"))
                        .withRequestBody(containing("https://github.com/apache/commons-lang"))
                        .withRequestBody(containing("Available environments")));
    }

    @Test
    void requestIncludesEnvironmentNames() {
        wireMock.stubFor(
                post(urlEqualTo("/v1/chat/completions")).willReturn(
                        aResponse().withStatus(200)
                                .withHeader("Content-Type", "application/json")
                                .withBody(chatResponse("OpenJDK 17.0; RHEL 8; Mvn 3.9.6"))));

        LlmEnvironmentResolver resolver = createResolver();
        resolver.resolveEnvironment(singleGav(), "MVN", null);

        wireMock.verify(
                postRequestedFor(urlEqualTo("/v1/chat/completions"))
                        .withRequestBody(containing("OpenJDK 17.0; RHEL 8; Mvn 3.9.6")));
    }

    @Test
    void filterEnvironmentsByGradle() {
        LlmEnvironmentResolver resolver = createResolver();
        List<JsonNode> filtered = resolver.filterEnvironments("GRADLE");

        assertThat(filtered).isNotEmpty();
        assertThat(filtered).allSatisfy(env -> {
            JsonNode attrs = env.get("attributes");
            assertThat(attrs.has("GRADLE")).isTrue();
        });
    }

    @Test
    void filterEnvironmentsBySbt() {
        LlmEnvironmentResolver resolver = createResolver();
        List<JsonNode> filtered = resolver.filterEnvironments("SBT");

        assertThat(filtered).isNotEmpty();
        assertThat(filtered).allSatisfy(env -> {
            String name = env.get("name").asText();
            assertThat(name.toUpperCase()).contains("SBT");
        });
    }

    @Test
    void filterEnvironmentsByNpm() {
        LlmEnvironmentResolver resolver = createResolver();
        List<JsonNode> filtered = resolver.filterEnvironments("NPM");

        assertThat(filtered).isNotEmpty();
        assertThat(filtered).allSatisfy(env -> {
            String name = env.get("name").asText().toLowerCase();
            assertThat(name.contains("node") || name.contains("npm")).isTrue();
        });
    }

    @Test
    void filterEnvironmentsByMvnReturnsAll() {
        LlmEnvironmentResolver resolver = createResolver();
        List<JsonNode> mvnFiltered = resolver.filterEnvironments("MVN");
        List<JsonNode> nullFiltered = resolver.filterEnvironments(null);

        assertThat(mvnFiltered).hasSameSizeAs(nullFiltered);
        assertThat(mvnFiltered.size()).isGreaterThan(100);
    }

    @Test
    void hiddenEnvironmentsFiltered() {
        LlmEnvironmentResolver resolver = createResolver();
        List<JsonNode> filtered = resolver.filterEnvironments("MVN");

        assertThat(filtered).allSatisfy(env -> {
            assertThat(env.get("hidden").asBoolean()).isFalse();
        });
    }

    @Test
    void deprecatedEnvironmentsFiltered() {
        LlmEnvironmentResolver resolver = createResolver();
        List<JsonNode> filtered = resolver.filterEnvironments("MVN");

        assertThat(filtered).allSatisfy(env -> {
            assertThat(env.path("deprecated").asBoolean(false)).isFalse();
        });
    }

    @Test
    void deprecatedEnvironmentNameRejected() {
        wireMock.stubFor(
                post(urlEqualTo("/v1/chat/completions")).willReturn(
                        aResponse().withStatus(200)
                                .withHeader("Content-Type", "application/json")
                                .withBody(chatResponse("OpenJDK 1.8; Mvn 3.5.2"))));

        LlmEnvironmentResolver resolver = createResolver();
        String result = resolver.resolveEnvironment(singleGav(), "MVN", null);

        assertThat(result).isNull();
    }

    @Test
    void gradleFilterExcludesMvnOnly() {
        LlmEnvironmentResolver resolver = createResolver();
        List<JsonNode> mvnFiltered = resolver.filterEnvironments("MVN");
        List<JsonNode> gradleFiltered = resolver.filterEnvironments("GRADLE");

        assertThat(gradleFiltered.size()).isLessThan(mvnFiltered.size());
    }
}
