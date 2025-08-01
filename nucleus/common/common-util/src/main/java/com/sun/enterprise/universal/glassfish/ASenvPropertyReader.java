/*
 * Copyright (c) 2024, 2025 Contributors to the Eclipse Foundation.
 * Copyright (c) 1997, 2018 Oracle and/or its affiliates. All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v. 2.0, which is available at
 * http://www.eclipse.org/legal/epl-2.0.
 *
 * This Source Code may also be made available under the following Secondary
 * Licenses when the conditions for such availability set forth in the
 * Eclipse Public License v. 2.0 are satisfied: GNU General Public License,
 * version 2 with the GNU Classpath Exception, which is available at
 * https://www.gnu.org/software/classpath/license.html.
 *
 * SPDX-License-Identifier: EPL-2.0 OR GPL-2.0 WITH Classpath-exception-2.0
 */

package com.sun.enterprise.universal.glassfish;

import com.sun.enterprise.universal.io.SmartFile;
import com.sun.enterprise.util.net.NetUtils;

import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import org.glassfish.main.jdke.props.EnvToPropsConverter;

import static org.glassfish.embeddable.GlassFishVariable.CONFIG_ROOT;
import static org.glassfish.embeddable.GlassFishVariable.DERBY_ROOT;
import static org.glassfish.embeddable.GlassFishVariable.DOMAINS_ROOT;
import static org.glassfish.embeddable.GlassFishVariable.HOST_NAME;
import static org.glassfish.embeddable.GlassFishVariable.IMQ_BIN;
import static org.glassfish.embeddable.GlassFishVariable.IMQ_LIB;
import static org.glassfish.embeddable.GlassFishVariable.INSTALL_ROOT;
import static org.glassfish.embeddable.GlassFishVariable.JAVA_HOME;
import static org.glassfish.embeddable.GlassFishVariable.JAVA_ROOT;
import static org.glassfish.embeddable.GlassFishVariable.NODES_ROOT;
import static org.glassfish.embeddable.GlassFishVariable.PRODUCT_ROOT;

/**
 * Class ASenvPropertyReader
 *
 * This class converts the variables stored in asenv.conf (UNIX)
 * or asenv.bat (WINDOWS) into their equivalent system properties.
 * <p>This class <strong>guarantees</strong> that no Exception will get thrown back.
 * You may however, have a bad javaRoot set even though we tried everything to find
 * one
 */
public class ASenvPropertyReader {

    private static final Map<String, String> ENV_TO_SYS_PROPERTY = Map.of(
        DERBY_ROOT.getEnvName(), DERBY_ROOT.getSystemPropertyName(),
        IMQ_LIB.getEnvName(), IMQ_LIB.getSystemPropertyName(),
        IMQ_BIN.getEnvName(), IMQ_BIN.getSystemPropertyName(),
        CONFIG_ROOT.getEnvName(), CONFIG_ROOT.getSystemPropertyName(),
        JAVA_ROOT.getEnvName(), JAVA_ROOT.getSystemPropertyName(),
        DOMAINS_ROOT.getEnvName(), DOMAINS_ROOT.getSystemPropertyName(),
        NODES_ROOT.getEnvName(), NODES_ROOT.getSystemPropertyName());


    /**
     * Typically, only one asenv file will be read, even though there may be many
     * ASenvPropertyReader objects.  So for each unique File, only one ASenvMap
     * is created, and all ASenvPropertyReader objects that reference the file
     * will share the same map. The key to the propsMap is the install dir that
     * is passed to the constructor.
     */
    private static final HashMap<File, ASenvMap> propsMap = new HashMap<>();
    private ASenvMap props;

    /**
     * Read and process the information in asenv
     * There are no arguments because the installation directory is calculated
     * relative to the jar file you are calling from.
     * Unlike V2 this class will not set any System Properties.  Instead it will
     * give you a Map<String,String> containing the properties.
     * <p>To use the class, create an instance and then call getProps().
     */
    public ASenvPropertyReader() {
        this(GFLauncherUtils.getInstallDir());
    }

    /**
     * Read and process the information in asenv.[bat|conf]
     * This constructor should normally not be called.  It is designed for
     * unit test classes that are not running from an official installation.
     * @param installDir The Glassfish installation directory
     */
    public ASenvPropertyReader(File installDir) {
        synchronized (propsMap) {
            installDir = SmartFile.sanitize(installDir);
            props = propsMap.get(installDir);
            if (props == null) {
                props = new ASenvMap(installDir);
                propsMap.put(installDir, props);
            }
        }
    }

    /**
     * Returns the properties that were processed from asenv.[bat|conf].
     * @return A Map<String,String> with all the system properties like properties.
     */
    public Map<String, String> getProps() {
        return props;
    }

    /**
     * Returns a string representation of the properties in the Map<String,String>.
     * Format:  name=value\nname2=value2\n etc.
     * @return the string representation.
     */
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        Set<String> keys = props.keySet();

        for (String key : keys) {
            sb.append(key).append("=").append(props.get(key)).append('\n');
        }
        return sb.toString();
    }


    static class ASenvMap extends HashMap<String, String> {

        ASenvMap(File installDir) {
            new EnvToPropsConverter(installDir.toPath()).convert(ENV_TO_SYS_PROPERTY).entrySet()
                .forEach(e -> this.put(e.getKey(), e.getValue().getPath()));
            String javaHome = new File(System.getProperty(JAVA_HOME.getSystemPropertyName())).toPath().toString();
            putIfAbsent(JAVA_ROOT.getPropertyName(), javaHome);
            putIfAbsent(HOST_NAME.getPropertyName(), getHostname());
            putIfAbsent(INSTALL_ROOT.getPropertyName(), installDir.toPath().toString());
            putIfAbsent(PRODUCT_ROOT.getPropertyName(), installDir.getParentFile().toPath().toString());
        }

        private static String getHostname() {
            try {
                return NetUtils.getCanonicalHostName();
            } catch (Exception ex) {
                return "localhost";
            }
        }
    }
}
