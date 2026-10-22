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

import java.util.regex.Pattern;

/**
 * Masks secrets that may leak into downloaded build/alignment logs and alignment diffs.
 *
 * <p>
 * Ported from {@code org.jboss.pnc.common.log.LogSanitizer#maskKnownSecrets}; inlined here because the pnc-common
 * version currently resolved by Bacon does not yet expose that method. Keep this in sync with pnc-common and drop it in
 * favour of the shared implementation once Bacon depends on a version that provides it.
 */
final class SecretSanitizer {

    /**
     * Placeholder that replaces masked secret values.
     */
    static final String REDACTED = "[REDACTED]";

    private static final String SECRET_KEYWORDS = "(pass(?:word)?|secret|token|api[_-]?key|access[_-]?key|private[_-]?key|credential)";

    // Quoted form "..." takes priority; unquoted \S+ is the fallback. Neither includes space, so arguments after the
    // value are always preserved.
    private static final String VALUE = "(?:\"[^\"]*\"|\\S+)";

    private static final Pattern CLI_LONG_OPT_SECRET = Pattern
            .compile("(?i)(--[a-z0-9_.-]*" + SECRET_KEYWORDS + "[a-z0-9_.-]*=)" + VALUE);

    private static final Pattern JAVA_PROP_SECRET = Pattern
            .compile("(?i)(-D[a-z0-9_.-]*" + SECRET_KEYWORDS + "[a-z0-9_.-]*=)" + VALUE);

    private static final Pattern ENV_VAR_SECRET = Pattern
            .compile("(?i)(\\b[a-z0-9_.-]*" + SECRET_KEYWORDS + "[a-z0-9_.-]*=)" + VALUE);

    // Minimum length 20 prevents false positives on short words. The character class excludes space and " so the match
    // self-terminates before a closing quote or the next argument.
    private static final Pattern BEARER_TOKEN = Pattern.compile("(?i)\\b(Bearer\\s+)[A-Za-z0-9._\\-+/=]{20,}");

    private static final Pattern BASIC_CREDENTIALS = Pattern.compile("(?i)\\b(Basic\\s+)[A-Za-z0-9+/=]{20,}");

    // Credentials embedded in a URL's userinfo (scheme://user:token@host). Userinfo cannot contain '/', '@' or
    // whitespace, so the match self-terminates at the authority's '@'. scp-like git@host:org/repo has no "://" and is
    // left untouched.
    private static final Pattern URL_USERINFO_SECRET = Pattern.compile("(?i)([a-z][a-z0-9+.-]*://)[^/@\\s]+@");

    private SecretSanitizer() {
    }

    /**
     * Masks common secrets: values of keys whose names contain a known secret keyword (password, secret, token,
     * api-key, access-key, private-key, credential), {@code Bearer}/{@code Basic} credentials, and credentials embedded
     * in a URL's userinfo ({@code scheme://user:token@host}) anywhere in the input.
     *
     * @param input the string to mask, may be null
     * @return the masked string, or null if the input is null
     */
    static String maskKnownSecrets(String input) {
        if (input == null) {
            return null;
        }
        String result = CLI_LONG_OPT_SECRET.matcher(input).replaceAll("$1" + REDACTED);
        result = JAVA_PROP_SECRET.matcher(result).replaceAll("$1" + REDACTED);
        result = ENV_VAR_SECRET.matcher(result).replaceAll("$1" + REDACTED);
        result = BEARER_TOKEN.matcher(result).replaceAll("$1" + REDACTED);
        result = BASIC_CREDENTIALS.matcher(result).replaceAll("$1" + REDACTED);
        return URL_USERINFO_SECRET.matcher(result).replaceAll("$1" + REDACTED + "@");
    }
}
