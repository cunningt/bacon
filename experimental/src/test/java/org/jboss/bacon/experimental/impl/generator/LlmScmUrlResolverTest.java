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

public class LlmScmUrlResolverTest {

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

    private Set<GAV> singleGav() {
        return Set.of(new GAV("org.apache.commons", "commons-lang3", "3.12.0"));
    }

    private String chatResponse(String content) {
        return "{\"choices\":[{\"message\":{\"content\":\"" + content + "\"}}]}";
    }

    @Test
    void successfulResolution() {
        wireMock.stubFor(
                post(urlEqualTo("/v1/chat/completions")).willReturn(
                        aResponse().withStatus(200)
                                .withHeader("Content-Type", "application/json")
                                .withBody(chatResponse("https://github.com/apache/commons-lang.git"))));

        LlmScmUrlResolver resolver = new LlmScmUrlResolver(createConfig());
        String url = resolver.resolveScmUrl(singleGav());

        assertThat(url).isEqualTo("https://github.com/apache/commons-lang.git");
    }

    @Test
    void urlWithoutDotGit() {
        wireMock.stubFor(
                post(urlEqualTo("/v1/chat/completions")).willReturn(
                        aResponse().withStatus(200)
                                .withHeader("Content-Type", "application/json")
                                .withBody(chatResponse("https://github.com/apache/commons-lang"))));

        LlmScmUrlResolver resolver = new LlmScmUrlResolver(createConfig());
        String url = resolver.resolveScmUrl(singleGav());

        assertThat(url).isEqualTo("https://github.com/apache/commons-lang");
    }

    @Test
    void unknownResponse() {
        wireMock.stubFor(
                post(urlEqualTo("/v1/chat/completions")).willReturn(
                        aResponse().withStatus(200)
                                .withHeader("Content-Type", "application/json")
                                .withBody(chatResponse("UNKNOWN"))));

        LlmScmUrlResolver resolver = new LlmScmUrlResolver(createConfig());
        String url = resolver.resolveScmUrl(singleGav());

        assertThat(url).isNull();
    }

    @Test
    void urlEmbeddedInText() {
        String content = "The repository is at https://github.com/apache/commons-lang.git for this project.";
        wireMock.stubFor(
                post(urlEqualTo("/v1/chat/completions")).willReturn(
                        aResponse().withStatus(200)
                                .withHeader("Content-Type", "application/json")
                                .withBody(chatResponse(content))));

        LlmScmUrlResolver resolver = new LlmScmUrlResolver(createConfig());
        String url = resolver.resolveScmUrl(singleGav());

        assertThat(url).isEqualTo("https://github.com/apache/commons-lang.git");
    }

    @Test
    void serverError() {
        wireMock.stubFor(
                post(urlEqualTo("/v1/chat/completions"))
                        .willReturn(aResponse().withStatus(500).withBody("Internal Server Error")));

        LlmScmUrlResolver resolver = new LlmScmUrlResolver(createConfig());
        String url = resolver.resolveScmUrl(singleGav());

        assertThat(url).isNull();
    }

    @Test
    void disabledConfig() {
        LlmConfig config = new LlmConfig();
        config.setEnabled(false);

        LlmScmUrlResolver resolver = new LlmScmUrlResolver(config);
        String url = resolver.resolveScmUrl(singleGav());

        assertThat(url).isNull();
        wireMock.verify(0, postRequestedFor(urlEqualTo("/v1/chat/completions")));
    }

    @Test
    void malformedJsonResponse() {
        wireMock.stubFor(
                post(urlEqualTo("/v1/chat/completions")).willReturn(
                        aResponse().withStatus(200)
                                .withHeader("Content-Type", "application/json")
                                .withBody("not valid json")));

        LlmScmUrlResolver resolver = new LlmScmUrlResolver(createConfig());
        String url = resolver.resolveScmUrl(singleGav());

        assertThat(url).isNull();
    }

    @Test
    void emptyGavSet() {
        LlmScmUrlResolver resolver = new LlmScmUrlResolver(createConfig());
        String url = resolver.resolveScmUrl(Set.of());

        assertThat(url).isNull();
    }

    @Test
    void responseWithNoUrl() {
        wireMock.stubFor(
                post(urlEqualTo("/v1/chat/completions")).willReturn(
                        aResponse().withStatus(200)
                                .withHeader("Content-Type", "application/json")
                                .withBody(chatResponse("I don't know the repository for this artifact."))));

        LlmScmUrlResolver resolver = new LlmScmUrlResolver(createConfig());
        String url = resolver.resolveScmUrl(singleGav());

        assertThat(url).isNull();
    }

    @Test
    void requestIncludesModelAndGav() {
        wireMock.stubFor(
                post(urlEqualTo("/v1/chat/completions")).willReturn(
                        aResponse().withStatus(200)
                                .withHeader("Content-Type", "application/json")
                                .withBody(chatResponse("https://github.com/apache/commons-lang.git"))));

        LlmScmUrlResolver resolver = new LlmScmUrlResolver(createConfig());
        resolver.resolveScmUrl(singleGav());

        wireMock.verify(
                postRequestedFor(urlEqualTo("/v1/chat/completions"))
                        .withRequestBody(containing("\"model\":\"test-model\""))
                        .withRequestBody(containing("commons-lang3"))
                        .withRequestBody(containing("org.apache.commons")));
    }
}
