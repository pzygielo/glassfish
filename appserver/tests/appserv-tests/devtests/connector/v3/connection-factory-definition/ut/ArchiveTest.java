/*
 * Copyright (c) 2025 Contributors to the Eclipse Foundation
 * Copyright (c) 2002, 2018 Oracle and/or its affiliates. All rights reserved.
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

package com.sun.s1asdev.cfd;

import com.sun.ejte.ccl.reporter.SimpleReporterAdapter;
import com.sun.enterprise.deployment.Application;
import com.sun.enterprise.deployment.ConnectionFactoryDefinitionDescriptor;
import com.sun.enterprise.deployment.ResourceDescriptor;
import com.sun.enterprise.deployment.WebBundleDescriptor;
import com.sun.enterprise.deployment.archivist.ApplicationArchivist;
import com.sun.enterprise.loader.ASURLClassLoader;
import junit.framework.TestCase;
import org.glassfish.deployment.common.Descriptor;
import org.glassfish.deployment.common.JavaEEResourceType;
import org.glassfish.ejb.deployment.archivist.EjbArchivist;
import org.glassfish.ejb.deployment.descriptor.EjbBundleDescriptorImpl;
import org.glassfish.ejb.deployment.descriptor.EjbDescriptor;
import org.glassfish.web.deployment.archivist.WebArchivist;

import java.io.File;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
public class ArchiveTest extends TestCase {
    String archiveDir = null;
    private static SimpleReporterAdapter stat =  new SimpleReporterAdapter("appserv-tests");

    protected void setUp() throws Exception {
        super.setUp();
        TestUtil.setupHK2();
        archiveDir = System.getProperty("ArchiveDir");
    }

    protected void tearDown() throws Exception {
        super.tearDown();
    }

    public void testApplicationArchive() throws Exception{
        String tcName = "connection-factory-definition-application-archive-test";

        try{
            doTestApplicationArchive();
            stat.addStatus(tcName, stat.PASS);
        }catch(Exception e){
            stat.addStatus(tcName, stat.FAIL);
            throw e;
        }
    }
    private void doTestApplicationArchive() throws Exception{
        String appArchiveName = "connection-factory-definitionApp-UT";
        File archive = new File(archiveDir, appArchiveName);
        assertTrue("Do not fing the archive "+archive.getAbsolutePath(), archive.exists());

        ApplicationArchivist reader = (ApplicationArchivist) TestUtil.getByType(ApplicationArchivist.class);
        reader.setAnnotationProcessingRequested(true);
        ASURLClassLoader classLoader = new ASURLClassLoader(appArchiveName, this.getClass().getClassLoader());
        classLoader.addURL(archive.toURL());
        reader.setClassLoader(classLoader);

        Application applicationDesc = reader.open(archive);
//        System.out.println("--------Connector resoruce in application.xml----------");
//        for( ConnectionFactoryDefinitionDescriptor cfdd: applicationDesc.getConnectionFactoryDefinitionDescriptors()){
//            System.out.println(cfdd.getDescription());
//            System.out.println(cfdd.getName());
//            for(Object key: cfdd.getProperties().keySet()){
//                System.out.println("  "+key+"="+cfdd.getProperties().get(key));
//            }
//            System.out.println("");
//        }

        Map<String,ConnectionFactoryDefinitionDescriptor> expectedCFDDs =
                new HashMap<String,ConnectionFactoryDefinitionDescriptor>();
        ConnectionFactoryDefinitionDescriptor desc;

        desc = new ConnectionFactoryDefinitionDescriptor();
        desc.setDescription("global-scope resource defined in application DD");
        desc.setName("java:global/env/ConnectionFactory");
        desc.setInterfaceName("jakarta.resource.cci.ConnectionFactory");
        desc.setResourceAdapter("RaApplicationName");
        desc.setTransactionSupport("LocalTransaction");
        desc.setMaxPoolSize(16);
        desc.setMinPoolSize(4);
        desc.addProperty("testName", "foo");
        expectedCFDDs.put(desc.getName(), desc);

        desc = new ConnectionFactoryDefinitionDescriptor();
        desc.setDescription("application-scope resource defined in application DD");
        desc.setName("java:app/env/ConnectionFactory");
        desc.setInterfaceName("jakarta.resource.cci.ConnectionFactory");
        desc.setResourceAdapter("RaApplicationName");
        desc.addProperty("testName", "foo");
        expectedCFDDs.put(desc.getName(), desc);

        TestUtil.compareCFDD(expectedCFDDs, applicationDesc.getResourceDescriptors(JavaEEResourceType.CFD));

    }

    public void testWebArchive() throws Exception{
        String tcName = "connection-factory-definition-web-archive-test";

        try{
            doTestWebArchive();
            stat.addStatus(tcName, stat.PASS);
        }catch(Exception e){
            stat.addStatus(tcName, stat.FAIL);
            throw e;
        }
    }

    private void doTestWebArchive() throws Exception{
        String appArchiveName = "connection-factory-definition-web";
        File archive = new File(archiveDir, appArchiveName);
        assertTrue("Do not fing the archive "+archive.getAbsolutePath(), archive.exists());

        ASURLClassLoader classLoader = new ASURLClassLoader(appArchiveName, this.getClass().getClassLoader());
        classLoader.addURL(archive.toURL());

        WebArchivist reader = (WebArchivist) TestUtil.getByType(WebArchivist.class);
        reader.setAnnotationProcessingRequested(true);
        reader.setClassLoader(classLoader);
        assertTrue("Archivist should handle annotations.", reader.isAnnotationProcessingRequested());

        WebBundleDescriptor webDesc = reader.open(archive);

        Map<String,ConnectionFactoryDefinitionDescriptor> expectedCFDDs =
                new HashMap<String,ConnectionFactoryDefinitionDescriptor>();
        ConnectionFactoryDefinitionDescriptor desc;

        desc = new ConnectionFactoryDefinitionDescriptor();
        desc.setDescription("global-scope resource to be modified by DD");
        desc.setName("java:global/env/Servlet_ModByDD_ConnectionFactory");
        desc.setInterfaceName("jakarta.resource.cci.ConnectionFactory");
        desc.setResourceAdapter("cfd-ra");
        desc.addProperty("testName", "foo");
        expectedCFDDs.put(desc.getName(), desc);

        desc = new ConnectionFactoryDefinitionDescriptor();
        desc.setDescription("global-scope resource defined by @ConnectionFactoryDefinition");
        desc.setName("java:global/env/Servlet_ConnectionFactory");
        desc.setInterfaceName("jakarta.resource.cci.ConnectionFactory");
        desc.setResourceAdapter("cfd-ra");
        desc.setTransactionSupport("LocalTransaction");
        desc.setMaxPoolSize(16);
        desc.setMinPoolSize(4);
        desc.addProperty("testName", "foo");
        expectedCFDDs.put(desc.getName(), desc);

        desc = new ConnectionFactoryDefinitionDescriptor();
        desc.setDescription("application-scope resource defined by @ConnectionFactoryDefinition");
        desc.setName("java:app/env/Servlet_ConnectionFactory");
        desc.setInterfaceName("jakarta.resource.cci.ConnectionFactory");
        desc.setResourceAdapter("cfd-ra");
        desc.setTransactionSupport("XATransaction");
        desc.setMaxPoolSize(16);
        desc.setMinPoolSize(4);
        desc.addProperty("testName", "foo");
        expectedCFDDs.put(desc.getName(), desc);

        desc = new ConnectionFactoryDefinitionDescriptor();
        desc.setDescription("module-scope resource defined by @ConnectionFactoryDefinition");
        desc.setName("java:module/env/Servlet_ConnectionFactory");
        desc.setInterfaceName("jakarta.resource.cci.ConnectionFactory");
        desc.setResourceAdapter("cfd-ra");
        desc.addProperty("testName", "foo");
        expectedCFDDs.put(desc.getName(), desc);

        desc = new ConnectionFactoryDefinitionDescriptor();
        desc.setDescription("component-scope resource defined by @ConnectionFactoryDefinition");
        desc.setName("java:comp/env/Servlet_ConnectionFactory");
        desc.setInterfaceName("jakarta.resource.cci.ConnectionFactory");
        desc.setResourceAdapter("cfd-ra");
        desc.addProperty("testName", "foo");
        expectedCFDDs.put(desc.getName(), desc);

        desc = new ConnectionFactoryDefinitionDescriptor();
        desc.setDescription("global-scope resource defined in Web DD");
        desc.setName("java:global/env/Web_DD_ConnectionFactory");
        desc.setInterfaceName("jakarta.resource.cci.ConnectionFactory");
        desc.setResourceAdapter("cfd-ra");
        desc.setTransactionSupport("LocalTransaction");
        desc.setMaxPoolSize(16);
        desc.setMinPoolSize(4);
        desc.addProperty("testName", "foo");
        expectedCFDDs.put(desc.getName(), desc);

        desc = new ConnectionFactoryDefinitionDescriptor();
        desc.setDescription("application-scope resource defined in Web DD");
        desc.setName("java:app/env/Web_DD_ConnectionFactory");
        desc.setInterfaceName("jakarta.resource.cci.ConnectionFactory");
        desc.setResourceAdapter("cfd-ra");
        desc.setTransactionSupport("XATransaction");
        desc.setMaxPoolSize(16);
        desc.setMinPoolSize(4);
        desc.addProperty("testName", "foo");
        expectedCFDDs.put(desc.getName(), desc);

        desc = new ConnectionFactoryDefinitionDescriptor();
        desc.setDescription("module-scope resource defined in Web DD");
        desc.setName("java:module/env/Web_DD_ConnectionFactory");
        desc.setInterfaceName("jakarta.resource.cci.ConnectionFactory");
        desc.setResourceAdapter("cfd-ra");
        desc.addProperty("testName", "foo");
        expectedCFDDs.put(desc.getName(), desc);

        TestUtil.compareCFDD(expectedCFDDs, webDesc.getResourceDescriptors(JavaEEResourceType.CFD));
    }

    public void testEJBArchive() throws Exception{
        String tcName = "connection-factory-definition-EJB-archive-test";

        try{
            doTestEJBArchive();
            stat.addStatus(tcName, stat.PASS);
        }catch(Exception e){
            stat.addStatus(tcName, stat.FAIL);
            throw e;
        }
    }
    private void doTestEJBArchive() throws Exception{
        String appArchiveName = "connection-factory-definition-ejb";
        File archive = new File(archiveDir, appArchiveName);
        assertTrue("Do not fing the archive "+archive.getAbsolutePath(), archive.exists());

        ASURLClassLoader classLoader = new ASURLClassLoader(appArchiveName, this.getClass().getClassLoader());
        classLoader.addURL(archive.toURL());

        EjbArchivist reader = (EjbArchivist) TestUtil.getByType(EjbArchivist.class);
        reader.setClassLoader(classLoader);
        reader.setAnnotationProcessingRequested(true);
        assertTrue("Archivist should handle annotations.", reader.isAnnotationProcessingRequested());

        EjbBundleDescriptorImpl ejbBundleDesc = reader.open(archive);
        Set<ResourceDescriptor> acturalCFDDs = new HashSet<ResourceDescriptor>();
        for( EjbDescriptor ejbDesc: ejbBundleDesc.getEjbs()){
            acturalCFDDs.addAll(ejbDesc.getResourceDescriptors(JavaEEResourceType.CFD));
        }

        Map<String,ConnectionFactoryDefinitionDescriptor> expectedCFDDs =
                new HashMap<String,ConnectionFactoryDefinitionDescriptor>();
        ConnectionFactoryDefinitionDescriptor desc;


        desc = new ConnectionFactoryDefinitionDescriptor();
        desc.setDescription("global-scope resource to be modified by DD");
        desc.setName("java:global/env/HelloStatefulEJB_ModByDD_ConnectionFactory");
        desc.setInterfaceName("jakarta.resource.cci.ConnectionFactory");
        desc.setResourceAdapter("cfd-ra");
        desc.addProperty("testName", "foo");
        expectedCFDDs.put(desc.getName(), desc);

        desc.setDescription("global-scope resource to be modified by DD");
        desc.setName("java:global/env/HelloEJB_ModByDD_ConnectionFactory");
        desc.setInterfaceName("jakarta.resource.cci.ConnectionFactory");
        desc.setResourceAdapter("cfd-ra");
        desc.addProperty("testName", "foo");
        expectedCFDDs.put(desc.getName(), desc);

        // connection-factory in DD for stateful EJB
        {
            desc = new ConnectionFactoryDefinitionDescriptor();
            desc.setDescription("global-scope resource defined in EJB DD");
            desc.setName("java:global/env/HelloStatefulEJB_DD_ConnectionFactory");
            desc.setInterfaceName("jakarta.resource.cci.ConnectionFactory");
            desc.setResourceAdapter("cfd-ra");
            desc.setTransactionSupport("LocalTransaction");
            desc.setMaxPoolSize(16);
            desc.setMinPoolSize(4);
            desc.addProperty("testName", "foo");
            expectedCFDDs.put(desc.getName(), desc);

            desc = new ConnectionFactoryDefinitionDescriptor();
            desc.setDescription("application-scope resource defined in EJB DD");
            desc.setName("java:app/env/HelloStatefulEJB_DD_ConnectionFactory");
            desc.setInterfaceName("jakarta.resource.cci.ConnectionFactory");
            desc.setResourceAdapter("cfd-ra");
            desc.setTransactionSupport("XATransaction");
            desc.setMaxPoolSize(16);
            desc.setMinPoolSize(4);
            desc.addProperty("testName", "foo");
            expectedCFDDs.put(desc.getName(), desc);

            desc = new ConnectionFactoryDefinitionDescriptor();
            desc.setDescription("module-scope resource defined in EJB DD");
            desc.setName("java:module/env/HelloStatefulEJB_DD_ConnectionFactory");
            desc.setInterfaceName("jakarta.resource.cci.ConnectionFactory");
            desc.setResourceAdapter("cfd-ra");
            desc.addProperty("testName", "foo");
            expectedCFDDs.put(desc.getName(), desc);

            desc = new ConnectionFactoryDefinitionDescriptor();
            desc.setDescription("component-scope resource defined in EJB DD");
            desc.setName("java:comp/env/HelloStatefulEJB_DD_ConnectionFactory");
            desc.setInterfaceName("jakarta.resource.cci.ConnectionFactory");
            desc.setResourceAdapter("cfd-ra");
            desc.addProperty("testName", "foo");
            expectedCFDDs.put(desc.getName(), desc);
        }
        // connection-factory in DD for stateless EJB
        {
            desc = new ConnectionFactoryDefinitionDescriptor();
            desc.setDescription("global-scope resource defined in EJB DD");
            desc.setName("java:global/env/HelloEJB_DD_ConnectionFactory");
            desc.setInterfaceName("jakarta.resource.cci.ConnectionFactory");
            desc.setResourceAdapter("cfd-ra");
            desc.setTransactionSupport("LocalTransaction");
            desc.setMaxPoolSize(16);
            desc.setMinPoolSize(4);
            desc.addProperty("testName", "foo");
            expectedCFDDs.put(desc.getName(), desc);

            desc = new ConnectionFactoryDefinitionDescriptor();
            desc.setDescription("application-scope resource defined in EJB DD");
            desc.setName("java:app/env/HelloEJB_DD_ConnectionFactory");
            desc.setInterfaceName("jakarta.resource.cci.ConnectionFactory");
            desc.setResourceAdapter("cfd-ra");
            desc.setTransactionSupport("XATransaction");
            desc.setMaxPoolSize(16);
            desc.setMinPoolSize(4);
            desc.addProperty("testName", "foo");
            expectedCFDDs.put(desc.getName(), desc);

            desc = new ConnectionFactoryDefinitionDescriptor();
            desc.setDescription("module-scope resource defined in EJB DD");
            desc.setName("java:module/env/HelloEJB_DD_ConnectionFactory");
            desc.setInterfaceName("jakarta.resource.cci.ConnectionFactory");
            desc.setResourceAdapter("cfd-ra");
            desc.addProperty("testName", "foo");
            expectedCFDDs.put(desc.getName(), desc);

            desc = new ConnectionFactoryDefinitionDescriptor();
            desc.setDescription("component-scope resource defined in EJB DD");
            desc.setName("java:comp/env/HelloEJB_DD_ConnectionFactory");
            desc.setInterfaceName("jakarta.resource.cci.ConnectionFactory");
            desc.setResourceAdapter("cfd-ra");
            desc.addProperty("testName", "foo");
            expectedCFDDs.put(desc.getName(), desc);
        }

        // connection-factory in annotation for stateful EJB
        {
            desc = new ConnectionFactoryDefinitionDescriptor();
            desc.setDescription("global-scope resource defined by @ConnectionFactoryDefinition");
            desc.setName("java:global/env/HelloStatefulEJB_Annotation_ConnectionFactory");
            desc.setInterfaceName("jakarta.resource.cci.ConnectionFactory");
            desc.setResourceAdapter("cfd-ra");
            desc.setTransactionSupport("LocalTransaction");
            desc.setMaxPoolSize(16);
            desc.setMinPoolSize(4);
            desc.addProperty("testName", "foo");
            expectedCFDDs.put(desc.getName(), desc);

            desc = new ConnectionFactoryDefinitionDescriptor();
            desc.setDescription("application-scope resource defined by @ConnectionFactoryDefinition");
            desc.setName("java:app/env/HelloStatefulEJB_Annotation_ConnectionFactory");
            desc.setInterfaceName("jakarta.resource.cci.ConnectionFactory");
            desc.setResourceAdapter("cfd-ra");
            desc.setTransactionSupport("XATransaction");
            desc.setMaxPoolSize(16);
            desc.setMinPoolSize(4);
            desc.addProperty("testName", "foo");
            expectedCFDDs.put(desc.getName(), desc);

            desc = new ConnectionFactoryDefinitionDescriptor();
            desc.setDescription("module-scope resource defined by @ConnectionFactoryDefinition");
            desc.setName("java:module/env/HelloStatefulEJB_Annotation_ConnectionFactory");
            desc.setInterfaceName("jakarta.resource.cci.ConnectionFactory");
            desc.setResourceAdapter("cfd-ra");
            desc.addProperty("testName", "foo");
            expectedCFDDs.put(desc.getName(), desc);

            desc = new ConnectionFactoryDefinitionDescriptor();
            desc.setDescription("component-scope resource defined by @ConnectionFactoryDefinition");
            desc.setName("java:comp/env/HelloStatefulEJB_Annotation_ConnectionFactory");
            desc.setInterfaceName("jakarta.resource.cci.ConnectionFactory");
            desc.setResourceAdapter("cfd-ra");
            desc.addProperty("testName", "foo");
            expectedCFDDs.put(desc.getName(), desc);
        }

        // connection-factory in annotation for stateless EJB
        {
            desc = new ConnectionFactoryDefinitionDescriptor();
            desc.setDescription("global-scope resource defined by @ConnectionFactoryDefinition");
            desc.setName("java:global/env/HelloEJB_Annotation_ConnectionFactory");
            desc.setInterfaceName("jakarta.resource.cci.ConnectionFactory");
            desc.setResourceAdapter("cfd-ra");
            desc.setTransactionSupport("LocalTransaction");
            desc.setMaxPoolSize(16);
            desc.setMinPoolSize(4);
            desc.addProperty("testName", "foo");
            expectedCFDDs.put(desc.getName(), desc);

            desc = new ConnectionFactoryDefinitionDescriptor();
            desc.setDescription("application-scope resource defined by @ConnectionFactoryDefinition");
            desc.setName("java:app/env/HelloEJB_Annotation_ConnectionFactory");
            desc.setInterfaceName("jakarta.resource.cci.ConnectionFactory");
            desc.setResourceAdapter("cfd-ra");
            desc.setTransactionSupport("XATransaction");
            desc.setMaxPoolSize(16);
            desc.setMinPoolSize(4);
            desc.addProperty("testName", "foo");
            expectedCFDDs.put(desc.getName(), desc);

            desc = new ConnectionFactoryDefinitionDescriptor();
            desc.setDescription("module-scope resource defined by @ConnectionFactoryDefinition");
            desc.setName("java:module/env/HelloEJB_Annotation_ConnectionFactory");
            desc.setInterfaceName("jakarta.resource.cci.ConnectionFactory");
            desc.setResourceAdapter("cfd-ra");
            desc.addProperty("testName", "foo");
            expectedCFDDs.put(desc.getName(), desc);

            desc = new ConnectionFactoryDefinitionDescriptor();
            desc.setDescription("component-scope resource defined by @ConnectionFactoryDefinition");
            desc.setName("java:comp/env/HelloEJB_Annotation_ConnectionFactory");
            desc.setInterfaceName("jakarta.resource.cci.ConnectionFactory");
            desc.setResourceAdapter("cfd-ra");
            desc.addProperty("testName", "foo");
            expectedCFDDs.put(desc.getName(), desc);
        }

        TestUtil.compareCFDD(expectedCFDDs, acturalCFDDs);

    }

}
