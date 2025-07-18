/*
 * Copyright (c) 2025 Contributors to the Eclipse Foundation.
 * Copyright (c) 1997-2018 Oracle and/or its affiliates. All rights reserved.
 * Copyright 2004 The Apache Software Foundation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.catalina.startup;

import java.io.File;
import java.lang.reflect.Method;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.StringTokenizer;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.apache.catalina.LogFacade;
import org.apache.catalina.security.SecurityClassLoad;

import static org.glassfish.main.jdke.props.SystemProperties.setProperty;


/**
 * Boostrap loader for Catalina.  This application constructs a class loader
 * for use in loading the Catalina internal classes (by accumulating all of the
 * JAR files found in the "server" directory under "catalina.home"), and
 * starts the regular execution of the container.  The purpose of this
 * roundabout approach is to keep the Catalina internal classes (and any
 * other classes they depend on, such as an XML parser) out of the system
 * class path and therefore not visible to application level classes.
 *
 * @author Craig R. McClanahan
 * @author Remy Maucherat
 * @version $Revision: 1.5 $ $Date: 2006/10/03 20:19:13 $
 */

public final class Bootstrap {


    // ------------------------------------------------------------ Constants


    private static final String CATALINA_HOME_TOKEN = "${catalina.home}";
    private static final String CATALINA_BASE_TOKEN = "${catalina.base}";


    // ----------------------------------------------------- Static Variables

    private static final Logger log = LogFacade.getLogger();

    // ----------------------------------------------------------- Variables

    /**
     * Debugging detail level for processing the startup.
     */
    private int debug = 0;


    /**
     * Daemon reference.
     */
    private Object catalinaDaemon = null;


    private ClassLoader commonLoader = null;
    private ClassLoader catalinaLoader = null;
    private ClassLoader sharedLoader = null;


    // ------------------------------------------------------ Private Methods

    private void initClassLoaders() {
        try {
            ClassLoaderFactory.setDebug(debug);
            commonLoader = createClassLoader("common", null);
            if( commonLoader == null ) {
                // no config file, default to this loader
                // - we might be in a 'single' env.
                commonLoader=this.getClass().getClassLoader();
            }

            catalinaLoader = createClassLoader("server", commonLoader);
            sharedLoader = createClassLoader("shared", commonLoader);
        } catch (Throwable t) {
            log.log(Level.SEVERE, LogFacade.CLASS_LOADER_CREATION_EXCEPTION, t);
            System.exit(1);
        }
    }


    private ClassLoader createClassLoader(String name, ClassLoader parent)
        throws Exception {

        String value = CatalinaProperties.getProperty(name + ".loader");
        if ((value == null) || (value.equals(""))) {
            return parent;
        }

        ArrayList<File> unpackedList = new ArrayList<File>();
        ArrayList<File> packedList = new ArrayList<File>();
        ArrayList<URL> urlList = new ArrayList<URL>();

        StringTokenizer tokenizer = new StringTokenizer(value, ",");
        while (tokenizer.hasMoreElements()) {
            String repository = tokenizer.nextToken();
            // Check for a JAR URL repository
            try {
                urlList.add(new URL(repository));
                continue;
            } catch (MalformedURLException e) {
                // Ignore
            }
            // Local repository
            boolean packed = false;
            if (repository.startsWith(CATALINA_HOME_TOKEN)) {
                repository = getCatalinaHome()
                    + repository.substring(CATALINA_HOME_TOKEN.length());
            } else if (repository.startsWith(CATALINA_BASE_TOKEN)) {
                repository = getCatalinaBase()
                    + repository.substring(CATALINA_BASE_TOKEN.length());
            }
            if (repository.endsWith("*.jar")) {
                packed = true;
                repository = repository.substring
                    (0, repository.length() - "*.jar".length());
            }
            if (packed) {
                packedList.add(new File(repository));
            } else {
                unpackedList.add(new File(repository));
            }
        }

        File[] unpacked = unpackedList.toArray(new File[unpackedList.size()]);
        File[] packed = packedList.toArray(new File[packedList.size()]);
        URL[] urls = urlList.toArray(new URL[urlList.size()]);

        return ClassLoaderFactory.createClassLoader
            (unpacked, packed, urls, parent);

    }


    /**
     * Initialize daemon.
     */
    public void init()
        throws Exception
    {

        // Set Catalina path
        setCatalinaHome();
        setCatalinaBase();

        initClassLoaders();

        Thread.currentThread().setContextClassLoader(catalinaLoader);

        SecurityClassLoad.securityClassLoad(catalinaLoader);

        // Load our startup class and call its process() method
        if (log.isLoggable(Level.FINE)) {
            log.log(Level.FINE, "Loading startup class");
        }
        Class startupClass =
            catalinaLoader.loadClass
            ("org.apache.catalina.startup.Catalina");
        Object startupInstance = startupClass.newInstance();

        // Set the shared extensions class loader
        if (log.isLoggable(Level.FINE)) {
            log.log(Level.FINE, "Setting startup class properties");
        }
        String methodName = "setParentClassLoader";
        Class paramTypes[] = new Class[1];
        paramTypes[0] = Class.forName("java.lang.ClassLoader");
        Object paramValues[] = new Object[1];
        paramValues[0] = sharedLoader;
        Method method =
            startupInstance.getClass().getMethod(methodName, paramTypes);
        method.invoke(startupInstance, paramValues);

        catalinaDaemon = startupInstance;

    }


    /**
     * Load daemon.
     */
    private void load(String[] arguments)
        throws Exception {

        // Call the load() method
        String methodName = "load";
        Object param[];
        Class paramTypes[];
        if (arguments==null || arguments.length==0) {
            paramTypes = null;
            param = null;
        } else {
            paramTypes = new Class[1];
            paramTypes[0] = arguments.getClass();
            param = new Object[1];
            param[0] = arguments;
        }
        Method method =
            catalinaDaemon.getClass().getMethod(methodName, paramTypes);
        if (log.isLoggable(Level.FINE)) {
            log.log(Level.FINE, "Calling startup class " + method);
        }
        method.invoke(catalinaDaemon, param);

    }


    // ----------------------------------------------------------- Main Program


    /**
     * Load the Catalina daemon.
     */
    public void init(String[] arguments)
        throws Exception {

        // Read the arguments
        if (arguments != null) {
            for (int i = 0; i < arguments.length; i++) {
                if (arguments[i].equals("-debug")) {
                    debug = 1;
                }
            }
        }

        init();
        load(arguments);

    }


    /**
     * Start the Catalina daemon.
     */
    public void start()
        throws Exception {
        if( catalinaDaemon==null ) {
            init();
        }

        Method method = catalinaDaemon.getClass().getMethod("start",(Class[])null);
        method.invoke(catalinaDaemon, (Object[])null);

    }


    /**
     * Stop the Catalina Daemon.
     */
    public void stop()
        throws Exception {

        Method method = catalinaDaemon.getClass().getMethod("stop",(Class[]) null);
        method.invoke(catalinaDaemon,(Object[]) null);

    }


    /**
     * Stop the standlone server.
     */
    public void stopServer()
        throws Exception {

        Method method =
            catalinaDaemon.getClass().getMethod("stopServer",(Class[])null);
        method.invoke(catalinaDaemon,(Object[])null);

    }

    /**
     * Set flag.
     */
    public void setAwait(boolean await)
        throws Exception {

        Class paramTypes[] = new Class[1];
        paramTypes[0] = Boolean.TYPE;
        Object paramValues[] = new Object[1];
        paramValues[0] = Boolean.valueOf(await);
        Method method =
            catalinaDaemon.getClass().getMethod("setAwait", paramTypes);
        method.invoke(catalinaDaemon, paramValues);

    }

    public boolean getAwait()
        throws Exception
    {
        Class paramTypes[] = new Class[0];
        Object paramValues[] = new Object[0];
        Method method =
            catalinaDaemon.getClass().getMethod("getAwait", paramTypes);
        Boolean b=(Boolean)method.invoke(catalinaDaemon, paramValues);
        return b.booleanValue();
    }

    /**
     * Destroy the Catalina Daemon.
     */
    public void destroy() {

        // FIXME

    }

    public void setCatalinaHome(String s) {
        setProperty("catalina.home", s, true);
    }

    public void setCatalinaBase(String s) {
        setProperty("catalina.base", s, true);
    }

    /**
     * Set the <code>catalina.base</code> System property to the current
     * working directory if it has not been set.
     */
    private void setCatalinaBase() {
        if (System.getProperty("catalina.base") != null) {
            return;
        }
        if (System.getProperty("catalina.home") == null) {
            setProperty("catalina.base", System.getProperty("user.dir"), true);
        } else {
            setProperty("catalina.base", System.getProperty("catalina.home"), true);
        }
    }

    /**
     * Set the <code>catalina.home</code> System property to the current
     * working directory if it has not been set.
     */
    private void setCatalinaHome() {
        if (System.getProperty("catalina.home") != null) {
            return;
        }
        File bootstrapJar = new File(System.getProperty("user.dir"), "bootstrap.jar");
        if (bootstrapJar.exists()) {
            try {
                setProperty("catalina.home", new File(System.getProperty("user.dir"), "..").getCanonicalPath(), true);
            } catch (Exception e) {
                // Ignore exception
                setProperty("catalina.home", System.getProperty("user.dir"), true);
            }
        } else {
            setProperty("catalina.home", System.getProperty("user.dir"), true);
        }
    }

    /**
     * Get the value of the catalina.home environment variable.
     */
    public static String getCatalinaHome() {
        return System.getProperty("catalina.home", System.getProperty("user.dir"));
    }

    /**
     * Get the value of the catalina.base environment variable.
     */
    public static String getCatalinaBase() {
        return System.getProperty("catalina.base", getCatalinaHome());
    }
}
