/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2018 Red Hat, Inc., and individual contributors
 * as indicated by the @author tags.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jboss.bacon.da;

import org.jboss.bacon.da.rest.endpoint.ListingsApi;
import org.jboss.bacon.da.rest.endpoint.LookupApi;
import org.jboss.bacon.da.rest.endpoint.ReportsApi;
import org.jboss.da.model.rest.GAV;
import org.jboss.da.model.rest.NPMPackage;
import org.jboss.pnc.bacon.common.Utils;
import org.jboss.pnc.bacon.config.Config;
import org.jboss.pnc.bacon.config.DaConfig;
import org.jboss.resteasy.client.jaxrs.ResteasyClientBuilder;
import org.jboss.resteasy.client.jaxrs.ResteasyWebTarget;
import org.jboss.resteasy.plugins.providers.RegisterBuiltin;
import org.jboss.resteasy.spi.ResteasyProviderFactory;

/**
 * Helper methods for DA stuff
 */
public class DaHelper {
    private final static String DA_PATH = "/da/rest/v-1";

    private static ResteasyClientBuilder builder;
    private static String daUrl;

    private static ResteasyWebTarget getClient() {

        if (builder == null) {
            builder = new ResteasyClientBuilder();
            ResteasyProviderFactory factory = ResteasyProviderFactory.getInstance();
            builder.providerFactory(factory);
            ResteasyProviderFactory.setRegisterBuiltinByDefault(true);
            RegisterBuiltin.register(factory);

            DaConfig daConfig = Config.instance().getActiveProfile().getDa();
            daUrl = Utils.generateUrlPath(daConfig.getUrl(), DA_PATH);
        }

        return builder.build().target(daUrl);
    }

    public static ReportsApi createReportsApi() {
        return getClient().proxy(ReportsApi.class);
    }

    public static ListingsApi createListingsApi() {
        return getClient().proxy(ListingsApi.class);
    }

    public static LookupApi createLookupApi() {
        return getClient().proxy(LookupApi.class);
    }

    /**
     * Get the appropriate mode to query DA for an artifact
     *
     * @param temporary whether the artifact is a temporary one
     * @param managedService whether the artifact is targetting a managed service
     *
     * @return appropriate mode
     */
    public static String getMode(boolean temporary, boolean managedService) {
        if (managedService) {
            if (temporary) {
                return "SERVICE_TEMPORARY";
            } else {
                return "SERVICE";
            }
        } else {
            if (temporary) {
                return "TEMPORARY";
            } else {
                return "PERSISTENT";
            }
        }
    }

    /**
     * Transforms a string in format group:artifact:version to a GAV If the string is not properly formatted, a
     * RuntimeException is thrown
     *
     * @param gav String to transform
     *
     * @return GAV object
     */
    public static GAV toGAV(String gav) {
        String[] pieces = gav.split(":");

        if (pieces.length != 3) {
            throw new RuntimeException("GAV " + gav + " cannot be parsed into groupid:artifactid:version");
        }

        return new GAV(pieces[0], pieces[1], pieces[2]);
    }

    /**
     * Transforms a string in format package:version to an NPMPackage If the string is not properly formatted, a
     * RuntimeException is thrown
     *
     * @param npmVersion String to transform
     *
     * @return NPMPackage object
     */
    public static NPMPackage toNPMPackage(String nameVersion) {
        String[] pieces = nameVersion.split(":");

        if (pieces.length != 2) {
            throw new RuntimeException("NPM " + nameVersion + " cannot be parsed into name:version");
        }

        return new NPMPackage(pieces[0], pieces[1]);
    }
}
