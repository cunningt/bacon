package org.jboss.bacon.experimental.impl.config;

import com.fasterxml.jackson.annotation.JsonInclude;

import lombok.Data;

@Data
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public class LlmConfig {
    /**
     * Whether LLM-based SCM URL resolution is enabled.
     */
    private boolean enabled = false;

    /**
     * Base URL of the local LLM API endpoint (e.g., http://localhost:11434/v1).
     */
    private String apiUrl;

    /**
     * The model name to use (e.g., "llama3", "codellama").
     */
    private String model = "llama3";

    /**
     * Optional API key for the LLM endpoint.
     */
    private String apiKey;

    /**
     * Connection timeout in milliseconds.
     */
    private int connectTimeoutMs = 10_000;

    /**
     * Read timeout in milliseconds.
     */
    private int readTimeoutMs = 30_000;

    /**
     * Temperature for generation. Lower values produce more deterministic results.
     */
    private double temperature = 0.1;
}
