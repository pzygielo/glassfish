/*
 * Copyright (c) 2022, 2025 Contributors to the Eclipse Foundation
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

package com.sun.ejb.codegen;

import com.sun.enterprise.config.serverbeans.JavaConfig;
import com.sun.enterprise.deployment.Application;
import com.sun.enterprise.deployment.EjbBundleDescriptor;
import com.sun.enterprise.deployment.EjbDescriptor;
import com.sun.enterprise.deployment.util.TypeUtil;
import com.sun.enterprise.util.i18n.StringManager;
import com.sun.logging.LogDomains;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.StringTokenizer;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.glassfish.api.admin.ServerEnvironment;
import org.glassfish.api.deployment.DeploymentContext;
import org.glassfish.deployment.common.ClientArtifactsManager;
import org.glassfish.ejb.spi.CMPDeployer;
import org.glassfish.hk2.api.ServiceLocator;

import static org.glassfish.embeddable.GlassFishVariable.JAVA_HOME;

/**
 * This class is used to generate the RMI-IIOP version of a
 * remote business interface.
 */

public class StaticRmiStubGenerator {

    private static final StringManager localStrings = StringManager.getManager(StaticRmiStubGenerator.class);

     private static final Logger _logger =
                LogDomains.getLogger(StaticRmiStubGenerator.class, LogDomains.EJB_LOGGER);

     private static final String ORG_OMG_STUB_PREFIX  = "org.omg.stub.";

     private final String toolsJarPath;

     private List<String> rmicOptionsList;

    /**
     * This class is only instantiated internally.
     */
    public StaticRmiStubGenerator(ServiceLocator services) {
        // Find java path and tools.jar

        //Try this jre's parent
        String javaHome = System.getProperty(JAVA_HOME.getSystemPropertyName());
        File jdkDir;
        if (javaHome != null) {
            jdkDir = getValidDirectory(new File(javaHome));
        } else {
            jdkDir = null;
        }

        if (jdkDir == null) {
            // Check for "JAVA_HOME" -- which is set via Server.xml during initialization
            // of the Server that is calling us.
            String jh = System.getenv(JAVA_HOME.getEnvName());
            if (jh != null) {
                jdkDir = getValidDirectory(new File(jh));
            }
        }

        if (jdkDir == null) {
            _logger.warning("Cannot identify JDK location.");
            toolsJarPath = null;
        } else {
            File toolsJar = new File(jdkDir + "/lib/tools.jar" );
            if (toolsJar.exists()) {
                toolsJarPath = toolsJar.getPath();
            } else {
                toolsJarPath = null;
            }
        }

        JavaConfig jc = services.getService(JavaConfig.class, ServerEnvironment.DEFAULT_INSTANCE_NAME);
        String rmicOptions = jc.getRmicOptions();

        rmicOptionsList = new ArrayList<>();
        StringTokenizer st = new StringTokenizer(rmicOptions, " ");
        while (st.hasMoreTokens()) {
            String op = st.nextToken();
            rmicOptionsList.add(op);
            _logger.log(Level.INFO, "Detected Rmic option: " + op);
        }
    }

    /**
     * Generates and compiles the necessary impl classes, stubs and skels.
     *
     * <pre>
     *
     * This method makes the following assumptions:
     *    - the deployment descriptor xmls are registered with Config
     *    - the class paths are registered with Config
     *
     * @@@
     * In case of re-deployment, the following steps should happen before:
     *    - rename the src dir from previous deployment (ex. /app/pet-old)
     *    - rename the stubs dir from previous deployment (ex. /stub/pet-old)
     *    - explode the ear file (ex. /app/petstore)
     *    - register the deployment descriptor xml with config
     *    - register the class path with config
     *
     * After successful completion of this method, the old src and sutbs
     * directories may be deleted.
     *
     * </pre>
     *
     * @param    deploymentCtx
     *
     * @return   array of the client stubs files as zip items or empty array
     *
     */
    public void ejbc(DeploymentContext deploymentCtx) throws Exception {

        // stubs dir for the current deployment
        File stubsDir = deploymentCtx.getScratchDir("ejb");
        String explodedDir = deploymentCtx.getSource().getURI().getSchemeSpecificPart();

        // deployment descriptor object representation
        EjbBundleDescriptor ejbBundle  = deploymentCtx.getModuleMetaData(EjbBundleDescriptor.class);

        long startTime = now();

        // class path to be used for this application during javac & rmic
        String classPath =  deploymentCtx.getTransientAppMetaData(CMPDeployer.MODULE_CLASSPATH, String.class);

        // Warning: A class loader is passed in while constructing the
        //          application object
        final ClassLoader jcl = ejbBundle.getClassLoader();

        // stubs dir is used as repository for code generator
        final String gnrtrTMP = stubsDir.getCanonicalPath();

        // ---- EJB DEPLOYMENT DESCRIPTORS -------------------------------

        // The main use-case we want to support is the one where existing
        // stand-alone java clients that access ejbs hosted in our appserver
        // directly through CosNaming need the generated stubs.  We don't want to
        // force them to run rmic themselves so it's better for them
        // just to tell us during the deployment of an ejb client app
        // or ejb app that we should run rmic and put the stubs in the
        // client.jar.  Turning on the deployment-time rmic flag ONLY
        // controls the generation of rmic stubs.  Dynamic stubs will be used
        // in the server, in the Application Client container, and in
        // stand-alone clients that instantiate our naming provider.

        progress(localStrings.getStringWithDefault
                 ("generator.processing_beans", "Processing beans..."));


        // ---- END OF EJB DEPLOYMENT DESCRIPTORS --------------------------

        // ---- RMIC ALL STUB CLASSES --------------------------------------

        Set<String> allStubClasses = new HashSet<>();

        // stubs classes for ejbs within this app that need rmic
        Set<String> ejbStubClasses = getStubClasses(jcl, ejbBundle);
        allStubClasses.addAll(ejbStubClasses);

        // Compile and RMIC all Stubs
        rmic(classPath, allStubClasses, stubsDir, gnrtrTMP, explodedDir);

        _logger.log(Level.INFO,  "[RMIC] RMIC execution time: " + (now() - startTime) + " msec");

        // ---- END OF RMIC ALL STUB CLASSES -------------------------------

        // Create list of all server files and client files
        List<String> allClientFiles = new ArrayList<>();

        // assemble the client files
        addGeneratedFiles(allStubClasses, allClientFiles, stubsDir);

        ClientArtifactsManager cArtifactsManager =  ClientArtifactsManager.get(deploymentCtx);
        for (String file : allClientFiles) {
            cArtifactsManager.add(stubsDir, new File(file));
        }

        _logger.log(Level.INFO, "ejbc.end", deploymentCtx.getModuleMetaData(Application.class).getRegistrationName());
        _logger.log(Level.INFO,  "[RMIC] Total time: " + (now() - startTime) + " msec");

    }

    /**
     * Compile all the generated .java files, run rmic on them.
     *
     * @param    classPath         class path for javac & rmic
     * @param    stubClasses  additional classes to be compilled with
     *                             the other files
     * @param    destDir           destination directory for javac & rmic
     * @param    repository        repository for code generator
     * @param    explodedDir  exploded directory for .class files
     *
     * @exception    GeneratorException  if an error during code generation
     * @exception    IOException         if an i/o error
     */
    private void rmic(String classPath, Set<String> stubClasses, File destDir,
                                String repository, String explodedDir)
        throws GeneratorException, IOException
    {

        if( stubClasses.size() == 0 ) {
            _logger.log(Level.INFO,  "[RMIC] No code generation required");
            return;
        }

        progress(localStrings.getStringWithDefault(
                                         "generator.compiling_rmi_iiop",
                                         "Compiling RMI-IIOP code."));

        List<String> cmds = new ArrayList<>();
        cmds.addAll(rmicOptionsList);
        cmds.add("-classpath");

        StringBuilder sb = new StringBuilder().append(System.getProperty("java.class.path"));
        if (toolsJarPath != null) {
             sb.append(File.pathSeparator).append(toolsJarPath);
        }
        sb.append(File.pathSeparator).append(explodedDir)
          .append(File.pathSeparator).append(repository)
          .append(File.pathSeparator).append(classPath);

        cmds.add(sb.toString());
        cmds.add("-d");
        cmds.add(destDir.toString());
        cmds.addAll(stubClasses);

        _logger.info("[RMIC] options: " + cmds);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();

        org.glassfish.rmic.Main compiler = new org.glassfish.rmic.Main(baos, "rmic");
        boolean success = compiler.compile(cmds.toArray(new String[cmds.size()]));
        //success = true;  // it ALWAYS returns an "error" if -Xnocompile is used!!

        String output = baos.toString();
        if (!success) {
             _logger.warning("[RMIC] Errors: " + output);
            throw new GeneratorException(
                    localStrings.getString("generator.rmic_compilation_failed_see_log"));
        }
    }

    /**
     * Assembles the name of the client jar files into the given vector.
     *
     * @param    stubClasses  classes that required rmic
     * @param    allClientFiles    List that contains all client jar files
     * @param    stubsDir          current stubsnskells dir for the app
     */
    private void addGeneratedFiles(Set<String> stubClasses,
                                   List<String> allClientFiles, File stubsDir)
    {
        for (String stubClass : stubClasses) {
            String stubFile = stubsDir.toString() + File.separator +
                                getStubName(stubClass).replace('.',
                                File.separatorChar) + ".class";
            allClientFiles.add(stubFile);
        }

        _logger.log(Level.INFO,
                    "[RMIC] Generated client files: " + allClientFiles);
    }


    private String getStubName(String fullName) {

        String className = fullName;
        String packageName = "";

        int lastDot = fullName.lastIndexOf('.');
        if (lastDot != -1) {
            className   = fullName.substring(lastDot+1, fullName.length());
            packageName = fullName.substring(0, lastDot+1);
        }

        String stubName = packageName + "_" + className + "_Stub";

        if(isSpecialPackage(fullName)) {
            stubName = ORG_OMG_STUB_PREFIX + stubName;
        }

        return stubName;
    }

    private boolean isSpecialPackage(String name)
    {
        // these package names are magic.  RMIC puts any home/remote stubs
        // into a different directory in these cases.
        // 4845896  bnevins, April 2003

        // this is really an error.  But we have enough errors. Let's be forgiving
        // and not allow a NPE out of here...
        if(name == null) {
            return false;
        }

        // Licensee bug 4959550
        // if(name.startsWith("com.sun.") || name.startsWith("javax."))
        if(name.startsWith("javax.")) {
            return true;
        }

        return false;
    }

    private Set getRemoteSuperInterfaces(ClassLoader jcl,
                                         String homeRemoteIntf)
        throws ClassNotFoundException {

        // all super interfaces of home or remote that need to be
        // processed for stubs.
        Set allSuperInterfaces =
            TypeUtil.getSuperInterfaces(jcl, homeRemoteIntf,"java.rmi.Remote");

        Set remoteSuperInterfaces = new HashSet();

        Iterator iter = allSuperInterfaces.iterator();
        while (iter.hasNext()) {
            String intfName = (String) iter.next();
            Class  intfClass = jcl.loadClass(intfName);
            if ( java.rmi.Remote.class.isAssignableFrom(intfClass) &&
                 !(intfName.equals("jakarta.ejb.EJBHome")) &&
                 !(intfName.equals("jakarta.ejb.EJBObject")) ) {
                remoteSuperInterfaces.add(intfName);
            }
        }

        return remoteSuperInterfaces;
    }


    private Set<String> getStubClasses(ClassLoader jcl,
                              EjbBundleDescriptor ejbBundle)
            throws IOException, ClassNotFoundException
    {

        Set<String> stubClasses     = new HashSet<>();

        for (EjbDescriptor desc : ejbBundle.getEjbs()) {
            if( desc.isRemoteInterfacesSupported() ) {

                String home   = desc.getHomeClassName();
                String remote = desc.getRemoteClassName();

                stubClasses.add(home);
                Set homeSuperIntfs = getRemoteSuperInterfaces(jcl, home);
                stubClasses.addAll(homeSuperIntfs);


                stubClasses.add(remote);
                Set remoteSuperIntfs = getRemoteSuperInterfaces(jcl, remote);
                stubClasses.addAll(remoteSuperIntfs);

            }

        }

        return stubClasses;
    }

    private long now()
    {
        return System.currentTimeMillis();
    }

    private void progress(String message) {
            try {
                _logger.log(Level.INFO, message);
            } catch(Throwable t) {
                _logger.log(Level.FINER,"Cannot set status message",t);
            }
    }


    private File getValidDirectory(File f) {
        try {
            if(f != null && f.isDirectory()) {
                return f.getCanonicalFile();
            }
        } catch (IOException e) {
            _logger.log(Level.INFO, e.getMessage(), e);
        }
        return null;
    }

}

