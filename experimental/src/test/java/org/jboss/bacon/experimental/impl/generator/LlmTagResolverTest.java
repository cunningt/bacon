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

public class LlmTagResolverTest {

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
                                .withBody(chatResponse("3.12.0"))));

        LlmTagResolver resolver = new LlmTagResolver(createConfig());
        String tag = resolver.resolveTag(singleGav(), "https://github.com/apache/commons-lang.git");

        assertThat(tag).isEqualTo("3.12.0");
    }

    @Test
    void tagWithVPrefix() {
        wireMock.stubFor(
                post(urlEqualTo("/v1/chat/completions")).willReturn(
                        aResponse().withStatus(200)
                                .withHeader("Content-Type", "application/json")
                                .withBody(chatResponse("v3.12.0"))));

        LlmTagResolver resolver = new LlmTagResolver(createConfig());
        String tag = resolver.resolveTag(singleGav(), null);

        assertThat(tag).isEqualTo("v3.12.0");
    }

    @Test
    void tagWithArtifactPrefix() {
        wireMock.stubFor(
                post(urlEqualTo("/v1/chat/completions")).willReturn(
                        aResponse().withStatus(200)
                                .withHeader("Content-Type", "application/json")
                                .withBody(chatResponse("commons-lang3-3.12.0"))));

        LlmTagResolver resolver = new LlmTagResolver(createConfig());
        String tag = resolver.resolveTag(singleGav(), null);

        assertThat(tag).isEqualTo("commons-lang3-3.12.0");
    }

    @Test
    void tagWithSlash() {
        wireMock.stubFor(
                post(urlEqualTo("/v1/chat/completions")).willReturn(
                        aResponse().withStatus(200)
                                .withHeader("Content-Type", "application/json")
                                .withBody(chatResponse("rel/commons-lang-3.12.0"))));

        LlmTagResolver resolver = new LlmTagResolver(createConfig());
        String tag = resolver.resolveTag(singleGav(), null);

        assertThat(tag).isEqualTo("rel/commons-lang-3.12.0");
    }

    @Test
    void unknownResponse() {
        wireMock.stubFor(
                post(urlEqualTo("/v1/chat/completions")).willReturn(
                        aResponse().withStatus(200)
                                .withHeader("Content-Type", "application/json")
                                .withBody(chatResponse("UNKNOWN"))));

        LlmTagResolver resolver = new LlmTagResolver(createConfig());
        String tag = resolver.resolveTag(singleGav(), null);

        assertThat(tag).isNull();
    }

    @Test
    void tagEmbeddedInText() {
        String content = "The tag is v3.12.0 for this release.";
        wireMock.stubFor(
                post(urlEqualTo("/v1/chat/completions")).willReturn(
                        aResponse().withStatus(200)
                                .withHeader("Content-Type", "application/json")
                                .withBody(chatResponse(content))));

        LlmTagResolver resolver = new LlmTagResolver(createConfig());
        String tag = resolver.resolveTag(singleGav(), null);

        assertThat(tag).isEqualTo("v3.12.0");
    }

    @Test
    void serverError() {
        wireMock.stubFor(
                post(urlEqualTo("/v1/chat/completions"))
                        .willReturn(aResponse().withStatus(500).withBody("Internal Server Error")));

        LlmTagResolver resolver = new LlmTagResolver(createConfig());
        String tag = resolver.resolveTag(singleGav(), null);

        assertThat(tag).isNull();
    }

    @Test
    void disabledConfig() {
        LlmConfig config = new LlmConfig();
        config.setEnabled(false);

        LlmTagResolver resolver = new LlmTagResolver(config);
        String tag = resolver.resolveTag(singleGav(), null);

        assertThat(tag).isNull();
        wireMock.verify(0, postRequestedFor(urlEqualTo("/v1/chat/completions")));
    }

    @Test
    void malformedJsonResponse() {
        wireMock.stubFor(
                post(urlEqualTo("/v1/chat/completions")).willReturn(
                        aResponse().withStatus(200)
                                .withHeader("Content-Type", "application/json")
                                .withBody("not valid json")));

        LlmTagResolver resolver = new LlmTagResolver(createConfig());
        String tag = resolver.resolveTag(singleGav(), null);

        assertThat(tag).isNull();
    }

    @Test
    void emptyGavSet() {
        LlmTagResolver resolver = new LlmTagResolver(createConfig());
        String tag = resolver.resolveTag(Set.of(), null);

        assertThat(tag).isNull();
    }

    @Test
    void requestIncludesModelGavAndScmUrl() {
        wireMock.stubFor(
                post(urlEqualTo("/v1/chat/completions")).willReturn(
                        aResponse().withStatus(200)
                                .withHeader("Content-Type", "application/json")
                                .withBody(chatResponse("3.12.0"))));

        LlmTagResolver resolver = new LlmTagResolver(createConfig());
        resolver.resolveTag(singleGav(), "https://github.com/apache/commons-lang.git");

        wireMock.verify(
                postRequestedFor(urlEqualTo("/v1/chat/completions"))
                        .withRequestBody(containing("\"model\":\"test-model\""))
                        .withRequestBody(containing("commons-lang3"))
                        .withRequestBody(containing("org.apache.commons"))
                        .withRequestBody(containing("https://github.com/apache/commons-lang.git")));
    }

    @Test
    void requestWithoutScmUrl() {
        wireMock.stubFor(
                post(urlEqualTo("/v1/chat/completions")).willReturn(
                        aResponse().withStatus(200)
                                .withHeader("Content-Type", "application/json")
                                .withBody(chatResponse("3.12.0"))));

        LlmTagResolver resolver = new LlmTagResolver(createConfig());
        resolver.resolveTag(singleGav(), null);

        wireMock.verify(
                postRequestedFor(urlEqualTo("/v1/chat/completions"))
                        .withRequestBody(containing("commons-lang3"))
                        .withRequestBody(notContaining("Git repository:")));
    }

    @Test
    void responseWithNoValidTag() {
        wireMock.stubFor(
                post(urlEqualTo("/v1/chat/completions")).willReturn(
                        aResponse().withStatus(200)
                                .withHeader("Content-Type", "application/json")
                                .withBody(chatResponse("I don't know the tag for this artifact."))));

        LlmTagResolver resolver = new LlmTagResolver(createConfig());
        String tag = resolver.resolveTag(singleGav(), null);

        assertThat(tag).isNull();
    }
}
