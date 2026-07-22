package org.jboss.bacon.experimental.impl.generator;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;

import org.junit.jupiter.api.Test;

public class DefaultAlignmentGeneratorTest {

    @Test
    void gradleJdk8() {
        Set<String> params = DefaultAlignmentGenerator.generateAlignmentParameters("GRADLE", "8");
        assertThat(params).containsExactlyInAnyOrder(
                "-Dorg.gradle.java.home=/usr/lib/jvm/java-1.8.0-openjdk",
                "-DRepour_Java=8");
    }

    @Test
    void gradleJdk11() {
        Set<String> params = DefaultAlignmentGenerator.generateAlignmentParameters("GRADLE", "11.0");
        assertThat(params).containsExactlyInAnyOrder(
                "-Dorg.gradle.java.home=/usr/lib/jvm/java-11-openjdk",
                "-DRepour_Java=11");
    }

    @Test
    void gradleJdk17() {
        Set<String> params = DefaultAlignmentGenerator.generateAlignmentParameters("GRADLE", "17.0");
        assertThat(params).containsExactlyInAnyOrder(
                "-Dorg.gradle.java.home=/usr/lib/jvm/java-17-openjdk",
                "-DRepour_Java=17");
    }

    @Test
    void gradleJdk21() {
        Set<String> params = DefaultAlignmentGenerator.generateAlignmentParameters("GRADLE", "21.0");
        assertThat(params).containsExactlyInAnyOrder(
                "-Dorg.gradle.java.home=/usr/lib/jvm/java-21-openjdk",
                "-DRepour_Java=21");
    }

    @Test
    void gradleJdk25() {
        Set<String> params = DefaultAlignmentGenerator.generateAlignmentParameters("GRADLE", "25.0");
        assertThat(params).containsExactlyInAnyOrder(
                "-Dorg.gradle.java.home=/usr/lib/jvm/java-25-openjdk",
                "-DRepour_Java=25");
    }

    @Test
    void mvnReturnsEmpty() {
        Set<String> params = DefaultAlignmentGenerator.generateAlignmentParameters("MVN", "17.0");
        assertThat(params).isEmpty();
    }

    @Test
    void npmReturnsEmpty() {
        Set<String> params = DefaultAlignmentGenerator.generateAlignmentParameters("NPM", "17.0");
        assertThat(params).isEmpty();
    }

    @Test
    void sbtReturnsEmpty() {
        Set<String> params = DefaultAlignmentGenerator.generateAlignmentParameters("SBT", "11.0");
        assertThat(params).isEmpty();
    }

    @Test
    void nullBuildTypeReturnsEmpty() {
        Set<String> params = DefaultAlignmentGenerator.generateAlignmentParameters(null, "17.0");
        assertThat(params).isEmpty();
    }

    @Test
    void gradleNullJdkReturnsEmpty() {
        Set<String> params = DefaultAlignmentGenerator.generateAlignmentParameters("GRADLE", null);
        assertThat(params).isEmpty();
    }

    @Test
    void gradleEmptyJdkReturnsEmpty() {
        Set<String> params = DefaultAlignmentGenerator.generateAlignmentParameters("GRADLE", "");
        assertThat(params).isEmpty();
    }

    @Test
    void gradleJdkMajorOnly() {
        Set<String> params = DefaultAlignmentGenerator.generateAlignmentParameters("GRADLE", "12");
        assertThat(params).containsExactlyInAnyOrder(
                "-Dorg.gradle.java.home=/usr/lib/jvm/java-12-openjdk",
                "-DRepour_Java=12");
    }
}
