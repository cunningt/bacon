/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2018 Red Hat, Inc., and individual contributors
 * as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jboss.pnc.bacon.pnc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class SecretSanitizerTest {

    @Test
    void shouldReturnNullForNull() {
        assertNull(SecretSanitizer.maskKnownSecrets(null));
    }

    @Test
    void shouldMaskBearerToken() {
        assertEquals(
                "Authorization: Bearer [REDACTED]",
                SecretSanitizer.maskKnownSecrets("Authorization: Bearer ghp_abcdefghijklmnopqrstuvwxyz0123456789"));
    }

    @Test
    void shouldMaskBasicCredentials() {
        assertEquals(
                "Authorization: Basic [REDACTED]",
                SecretSanitizer.maskKnownSecrets("Authorization: Basic dXNlcjpzdXBlcnNlY3JldHBhc3N3b3Jk"));
    }

    @Test
    void shouldMaskUrlUserInfo() {
        assertEquals(
                "cloning https://[REDACTED]@github.ibm.com/org/repo.git",
                SecretSanitizer.maskKnownSecrets("cloning https://user:token123@github.ibm.com/org/repo.git"));
    }

    @Test
    void shouldMaskKeyValueSecrets() {
        assertEquals("password=[REDACTED] next", SecretSanitizer.maskKnownSecrets("password=hunter2 next"));
        assertEquals("-Dmaven.token=[REDACTED]", SecretSanitizer.maskKnownSecrets("-Dmaven.token=abc123"));
        assertEquals("--access-key=[REDACTED]", SecretSanitizer.maskKnownSecrets("--access-key=AKIA1234567890"));
    }

    @Test
    void shouldLeaveNonSecretContentUntouched() {
        String line = "+        <version>1.2.3.redhat-00001</version>";
        assertEquals(line, SecretSanitizer.maskKnownSecrets(line));
    }

    @Test
    void shouldNotFlagScpStyleGitUrl() {
        String line = "Submodule url = git@github.com:org/repo.git";
        assertFalse(SecretSanitizer.maskKnownSecrets(line).contains("[REDACTED]"));
    }
}
