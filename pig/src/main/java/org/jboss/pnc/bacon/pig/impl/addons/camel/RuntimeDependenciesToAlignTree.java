/*
 * JBoss, Home of Professional Open Source. Copyright 2017 Red Hat, Inc., and individual
 * contributors as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */

package org.jboss.pnc.bacon.pig.impl.addons.camel;

import org.jboss.pnc.bacon.pig.impl.addons.AddOn;
import org.jboss.pnc.bacon.pig.impl.config.PigConfiguration;
import org.jboss.pnc.bacon.pig.impl.pnc.PncBuild;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileNotFoundException;
import java.io.PrintWriter;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * @author Tom Cunningham, tcunning@redhat.com <br>
 * @author Paul Gallagher, pgallagh@redhat.com <br>
 *         Date: 2018-11-20
 *
 *         If you run your builds with 'dependency:tree' then you can use this addon to give you the list of compile
 *         scope dependencies that are not redhat builds
 */
public class RuntimeDependenciesToAlignTree extends AddOn {

    private static final Logger log = LoggerFactory.getLogger(RuntimeDependenciesToAlignTree.class);

    public RuntimeDependenciesToAlignTree(
            PigConfiguration pigConfiguration,
            Map<String, PncBuild> builds,
            String releasePath,
            String extrasPath) {
        super(pigConfiguration, builds, releasePath, extrasPath);
    }

    @Override
    protected String getName() {
        return "runtimeDependenciesToAlignTree";
    }

    private String parseDependency(String line) {
        String dependency = line.replace(":runtime", "").replace(":compile", "");
        dependency = dependency.replace("[INFO] ", "");
        dependency = dependency.replaceFirst("([+-|\\s]+\\s+)", "");
        return dependency;
    }

    @Override
    public void trigger() {
        String filename = extrasPath + "CamelRuntimeDependenciesToAlignTree.txt";
        log.info("Running RuntimeDependenciesToAlignTree - report is {}", filename);
        try (PrintWriter file = new PrintWriter(filename, StandardCharsets.UTF_8.name())) {
            for (PncBuild build : builds.values()) {
                // Make a unique list so we don't get multiples from
                // sub-module's dependency tree list
                List<String> bcLog = build.getBuildLog().stream().distinct().collect(Collectors.toList());

                file.print("-------- [" + build.getId() + "] " + build.getName() + " --------\n");

                List<String> runtimeDeps = new ArrayList<String>();
                // Do build-from-source counts
                int allDependencyCount = 0;
                for (String line : bcLog) {
                    line.trim();
                    if ((line.endsWith(":compile")) || (line.endsWith(":runtime"))) {
                        allDependencyCount++;
                        runtimeDeps.add(parseDependency(line));
                    }
                }

                // Get a distinct list of deps
                List<String> distinctDeps = runtimeDeps.stream().distinct().collect(Collectors.toList());

                // Get the productized dependency count
                int productizedCount = 0;
                for (String line : bcLog) {
                    line.trim();
                    if ((line.contains("redhat-")) && ((line.endsWith(":compile")) || (line.endsWith(":runtime")))) {
                        productizedCount++;
                    }
                }

                // Print out the productization stats
                file.print(
                        "Found " + allDependencyCount + " dependencies, with " + productizedCount
                                + " productized dependencies\n");
                float pct = (float) productizedCount / allDependencyCount;
                file.print("Build from source percentage : " + String.format("%.2f", pct * 100) + "%\n");

                // Build a list of parentage
                String currentParent = null;
                HashMap<String, String> parentage = new HashMap<String, String>();
                List<String> parentLog = build.getBuildLog().stream().collect(Collectors.toList());
                for (String parentLine : parentLog) {
                    if ((parentLine.startsWith("[INFO] --- maven-dependency-plugin:")
                            && (parentLine.contains(":tree")))) {
                        // We have a parent line
                        int startIndex = parentLine.indexOf("@");
                        currentParent = parentLine.substring(startIndex + 2, (parentLine.length() - 4));
                    }

                    if ((currentParent != null)
                            && ((parentLine.endsWith(":compile")) || (parentLine.endsWith(":runtime")))) {
                        String dep = parseDependency(parentLine);
                        if (parentage.containsKey(dep)) {
                            String curValue = parentage.get(dep);
                            parentage.replace(dep, curValue + "," + currentParent);
                        } else {
                            parentage.put(dep, currentParent);
                        }
                    }

                    if (parentLine.stripTrailing().equals("[INFO] ")) {
                        currentParent = null;
                    }
                }

                for (String bcLine : distinctDeps) {
                    if (!bcLine.contains("org.apache.camel") && !bcLine.contains("redhat-")) {
                        file.print(bcLine + ", parent=" + parentage.get(bcLine) + "\n");
                    }
                }
                file.print("\n");
            }
        } catch (FileNotFoundException | UnsupportedEncodingException e) {
            log.error("Error while creating RuntimeDependenciesToAlignTree report", e);
        }
    }
}
