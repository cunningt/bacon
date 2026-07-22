package org.jboss.bacon.experimental.impl.generator;

import java.util.Set;
import java.util.TreeSet;

public class DefaultAlignmentGenerator {

    private DefaultAlignmentGenerator() {
    }

    public static Set<String> generateAlignmentParameters(String buildType, String jdkVersion) {
        if (!"GRADLE".equals(buildType) || jdkVersion == null || jdkVersion.isEmpty()) {
            return Set.of();
        }

        String major = extractMajorVersion(jdkVersion);
        String javaHome = "8".equals(major) ? "1.8.0" : major;

        Set<String> params = new TreeSet<>();
        params.add("-Dorg.gradle.java.home=/usr/lib/jvm/java-" + javaHome + "-openjdk");
        params.add("-DRepour_Java=" + major);
        return params;
    }

    private static String extractMajorVersion(String jdkVersion) {
        int dot = jdkVersion.indexOf('.');
        if (dot > 0) {
            return jdkVersion.substring(0, dot);
        }
        return jdkVersion;
    }
}
