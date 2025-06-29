/*
 * Copyright (c) 2022, 2023 Contributors to Eclipse Foundation. All rights reserved.
 * Copyright (c) 1997, 2020 Oracle and/or its affiliates. All rights reserved.
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

package com.sun.enterprise.connectors;

import com.sun.appserv.connectors.internal.api.ConnectorConstants;
import com.sun.appserv.connectors.internal.api.ConnectorRuntimeException;
import com.sun.appserv.connectors.internal.api.ConnectorsClassLoaderUtil;
import com.sun.appserv.connectors.internal.api.ConnectorsUtil;
import com.sun.appserv.connectors.internal.api.WorkContextHandler;
import com.sun.appserv.connectors.internal.api.WorkManagerFactory;
import com.sun.appserv.connectors.internal.spi.ConnectorNamingEventListener;
import com.sun.enterprise.config.serverbeans.Application;
import com.sun.enterprise.config.serverbeans.Applications;
import com.sun.enterprise.config.serverbeans.Domain;
import com.sun.enterprise.config.serverbeans.Module;
import com.sun.enterprise.config.serverbeans.Resource;
import com.sun.enterprise.config.serverbeans.ResourcePool;
import com.sun.enterprise.config.serverbeans.Resources;
import com.sun.enterprise.connectors.authentication.AuthenticationService;
import com.sun.enterprise.connectors.connector.module.RarType;
import com.sun.enterprise.connectors.deployment.util.ConnectorArchivist;
import com.sun.enterprise.connectors.module.ConnectorApplication;
import com.sun.enterprise.connectors.service.ConnectorAdminObjectAdminServiceImpl;
import com.sun.enterprise.connectors.service.ConnectorAdminServicesFactory;
import com.sun.enterprise.connectors.service.ConnectorConfigurationParserServiceImpl;
import com.sun.enterprise.connectors.service.ConnectorConnectionPoolAdminServiceImpl;
import com.sun.enterprise.connectors.service.ConnectorResourceAdminServiceImpl;
import com.sun.enterprise.connectors.service.ConnectorSecurityAdminServiceImpl;
import com.sun.enterprise.connectors.service.ConnectorService;
import com.sun.enterprise.connectors.service.ResourceAdapterAdminServiceImpl;
import com.sun.enterprise.connectors.util.ConnectorConfigParser;
import com.sun.enterprise.connectors.util.ConnectorConfigParserFactory;
import com.sun.enterprise.connectors.util.ConnectorJavaBeanValidator;
import com.sun.enterprise.connectors.util.ConnectorTimerProxy;
import com.sun.enterprise.connectors.util.DriverLoader;
import com.sun.enterprise.connectors.util.RAWriterAdapter;
import com.sun.enterprise.connectors.util.ResourcesUtil;
import com.sun.enterprise.container.common.spi.util.ComponentEnvManager;
import com.sun.enterprise.deploy.shared.FileArchive;
import com.sun.enterprise.deployment.ConnectorDescriptor;
import com.sun.enterprise.deployment.JndiNameEnvironment;
import com.sun.enterprise.deployment.ResourceReferenceDescriptor;
import com.sun.enterprise.deployment.archivist.ApplicationArchivist;
import com.sun.enterprise.deployment.archivist.ArchivistFactory;
import com.sun.enterprise.resource.pool.PoolManager;
import com.sun.enterprise.resource.pool.monitor.ConnectionPoolProbeProviderUtil;
import com.sun.enterprise.resource.pool.monitor.PoolMonitoringLevelListener;
import com.sun.enterprise.security.SecurityServicesUtil;
import com.sun.enterprise.security.ee.jmac.callback.ContainerCallbackHandler;
import com.sun.enterprise.transaction.api.JavaEETransactionManager;
import com.sun.logging.LogDomains;

import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.inject.Provider;
import jakarta.inject.Singleton;
import jakarta.resource.ResourceException;
import jakarta.resource.spi.ConnectionManager;
import jakarta.resource.spi.ManagedConnectionFactory;
import jakarta.resource.spi.ResourceAdapterAssociation;
import jakarta.resource.spi.XATerminator;
import jakarta.resource.spi.work.WorkManager;
import jakarta.transaction.SystemException;
import jakarta.transaction.Transaction;

import java.io.PrintWriter;
import java.net.URI;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.Timer;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.naming.NamingException;
import javax.security.auth.callback.CallbackHandler;

import org.glassfish.admin.monitor.MonitoringBootstrap;
import org.glassfish.api.admin.ProcessEnvironment;
import org.glassfish.api.admin.ServerEnvironment;
import org.glassfish.api.invocation.InvocationManager;
import org.glassfish.api.naming.GlassfishNamingManager;
import org.glassfish.api.naming.SimpleJndiName;
import org.glassfish.connectors.config.ResourceAdapterConfig;
import org.glassfish.connectors.config.SecurityMap;
import org.glassfish.connectors.config.WorkSecurityMap;
import org.glassfish.deployment.common.SecurityRoleMapperFactory;
import org.glassfish.hk2.api.PostConstruct;
import org.glassfish.hk2.api.PreDestroy;
import org.glassfish.hk2.api.ServiceLocator;
import org.glassfish.internal.api.ClassLoaderHierarchy;
import org.glassfish.internal.api.ConnectorClassLoaderService;
import org.glassfish.internal.api.DelegatingClassLoader;
import org.glassfish.internal.data.ApplicationRegistry;
import org.glassfish.resourcebase.resources.api.PoolInfo;
import org.glassfish.resourcebase.resources.api.ResourceDeployer;
import org.glassfish.resourcebase.resources.api.ResourceInfo;
import org.glassfish.resourcebase.resources.listener.ResourceManager;
import org.glassfish.resourcebase.resources.naming.ResourceNamingService;
import org.glassfish.resourcebase.resources.util.ResourceManagerFactory;
import org.glassfish.resources.api.ResourcesRegistry;
import org.glassfish.server.ServerEnvironmentImpl;
import org.jvnet.hk2.annotations.Service;
import org.jvnet.hk2.config.types.Property;

import static com.sun.appserv.connectors.internal.api.ConnectorsUtil.getApplicationNameOfEmbeddedRar;
import static com.sun.appserv.connectors.internal.api.ConnectorsUtil.getRarNameFromApplication;
import static com.sun.appserv.connectors.internal.api.ConnectorsUtil.isApplicationScopedResource;
import static com.sun.appserv.connectors.internal.api.ConnectorsUtil.isModuleScopedResource;
import static com.sun.appserv.connectors.internal.api.ConnectorsUtil.isStandAloneRA;
import static com.sun.logging.LogDomains.RSR_LOGGER;

/**
 * This class is the entry point to connector backend module. It exposes different API's called by external entities
 * like JPA, admin to perform various connector backend related operations. It delegates calls to various connetcor
 * admin services and other connector services which actually implement the functionality. This is a delegating class.
 *
 * @author Binod P.G, Srikanth P, Aditya Gore, Jagadish Ramu
 */
@Service
@Singleton
public class ConnectorRuntime implements com.sun.appserv.connectors.internal.api.ConnectorRuntime, PostConstruct, PreDestroy {

    private static ConnectorRuntime _runtime;
    private final Logger _logger = LogDomains.getLogger(ConnectorRuntime.class, RSR_LOGGER);
    private ConnectorConnectionPoolAdminServiceImpl ccPoolAdmService;
    private ConnectorResourceAdminServiceImpl connectorResourceAdmService;
    private ConnectorService connectorService;
    private ConnectorConfigurationParserServiceImpl configParserAdmService;
    private ResourceAdapterAdminServiceImpl resourceAdapterAdmService;
    private ConnectorSecurityAdminServiceImpl connectorSecurityAdmService;
    private ConnectorAdminObjectAdminServiceImpl adminObjectAdminService;
    private final ConnectorRegistry connectorRegistry = ConnectorRegistry.getInstance();
    private PoolMonitoringLevelListener poolMonitoringLevelListener;

    @Inject
    private GlassfishNamingManager namingManager;

    @Inject
    private PoolManager poolManager;

    @Inject
    private InvocationManager invocationManager;

    @Inject
    private ComponentEnvManager componentEnvManager;

    @Inject
    private Provider<JavaEETransactionManager> javaEETransactionManagerProvider;

    @Inject
    private Provider<WorkManagerFactory> workManagerFactoryProvider;

    @Inject
    private Provider<ResourceManagerFactory> resourceManagerFactoryProvider;

    @Inject
    private Provider<ApplicationRegistry> applicationRegistryProvider;

    @Inject
    private Provider<ApplicationArchivist> applicationArchivistProvider;

    @Inject
    private Provider<FileArchive> fileArchiveProvider;

    @Inject
    private Provider<SecurityRoleMapperFactory> securityRoleMapperFactoryProvider;

    @Inject
    private Provider<SecurityServicesUtil> securityServicesUtilProvider;

    @Inject
    private Provider<ContainerCallbackHandler> containerCallbackHandlerProvider;

    @Inject
    private Provider<ArchivistFactory> archivistFactoryProvider;

    @Inject
    private Provider<WorkContextHandler> workContextHandlerProvider;

    @Inject
    private Provider<com.sun.enterprise.resource.deployer.MailSessionDeployer> mailSessionDeployerProvider;

    @Inject
    @Named(ServerEnvironment.DEFAULT_INSTANCE_NAME)
    private Provider<org.glassfish.connectors.config.ConnectorService> connectorServiceProvider;

    @Inject
    private Provider<Applications> applicationsProvider;

    @Inject
    private ClassLoaderHierarchy classLoaderHierarchy;

    @Inject
    private ConnectorsClassLoaderUtil connectorsClassLoaderUtil;

    @Inject
    private ActiveRAFactory activeRAFactory;

    @Inject
    private Provider<ConnectionPoolProbeProviderUtil> connectionPoolProbeProviderUtilProvider;

    @Inject
    private Provider<MonitoringBootstrap> monitoringBootstrapProvider;

    @Inject
    private Provider<PoolMonitoringLevelListener> poolMonitoringLevelListenerProvider;

    @Inject
    private Provider<Domain> domainProvider;

    @Inject
    private Provider<ResourceManager> resourceManagerProvider;

    @Inject
    private ProcessEnvironment processEnvironment;

    @Inject
    private DriverLoader driverLoader;

    @Inject
    private ConnectorJavaBeanValidator connectorBeanValidator;

    @Inject
    private ConnectorClassLoaderService connectorClassLoaderService;

    @Inject
    private ServerEnvironmentImpl serverEnvironmentImpl;

    @Inject
    private ResourceNamingService resourceNamingService;

    @Inject
    private RarType archiveType;

    @Inject
    private ServiceLocator serviceLocator;

    private Resources globalResources;
    private ResourceManager resourceManager;

    // performance improvement, cache the lookup of transaction manager.
    private JavaEETransactionManager transactionManager;
    private ProcessEnvironment.ProcessType processType;


    /**
     * Returns the ConnectorRuntime instance. It follows singleton pattern and only one instance exists at any point of
     * time. External entities need to call this method to get ConnectorRuntime instance
     *
     * @return ConnectorRuntime instance
     */
    public static ConnectorRuntime getRuntime() {
        if (_runtime == null) {
            throw new RuntimeException("Connector Runtime not initialized");
        }

        return _runtime;
    }

    /**
     * Private constructor. It is private as it follows singleton pattern.
     */
    public ConnectorRuntime() {
        _runtime = this;
    }

    public ConnectionPoolProbeProviderUtil getProbeProviderUtil() {
        return connectionPoolProbeProviderUtilProvider.get();
    }

    public ResourceNamingService getResourceNamingService() {
        return resourceNamingService;
    }

    /**
     * Returns the execution environment.
     *
     * @return ConnectorConstants.SERVER if execution environment is appserv runtime else it returns
     * ConnectorConstants.CLIENT
     */
    @Override
    public ProcessEnvironment.ProcessType getEnvironment() {
        return processType;
    }

    public MonitoringBootstrap getMonitoringBootstrap() {
        return monitoringBootstrapProvider.get();
    }

    /**
     * Returns the generated default connection poolName for a connection definition.
     *
     * @param moduleName rar module name
     * @param connectionDefName connection definition name
     * @return generated connection poolname
     */
    public SimpleJndiName getDefaultPoolName(String moduleName, String connectionDefName) {
        return connectorService.getDefaultPoolName(moduleName, connectionDefName);
    }

    /**
     * Deletes connector Connection pool
     *
     * @param poolInfo Name of the pool to delete
     * @throws ConnectorRuntimeException if pool deletion operation fails
     */
    public void deleteConnectorConnectionPool(PoolInfo poolInfo) throws ConnectorRuntimeException {
        ccPoolAdmService.deleteConnectorConnectionPool(poolInfo);
    }

    /**
     * Creates connector connection pool in the connector container.
     *
     * @param connectorPoolObj ConnectorConnectionPool instance to be bound to JNDI. This object contains the pool
     * properties.
     * @throws ConnectorRuntimeException When creation of pool fails.
     */
    public void createConnectorConnectionPool(ConnectorConnectionPool connectorPoolObj) throws ConnectorRuntimeException {
        ccPoolAdmService.createConnectorConnectionPool(connectorPoolObj);
    }

    /**
     * Creates the connector resource on a given connection pool
     *
     * @param resourceInfo JNDI name of the resource to be created
     * @param poolInfo to which the connector resource belongs.
     * @param resourceType Unused.
     * @throws ConnectorRuntimeException If the resouce creation fails.
     */
    public void createConnectorResource(ResourceInfo resourceInfo, PoolInfo poolInfo, String resourceType) throws ConnectorRuntimeException {
        connectorResourceAdmService.createConnectorResource(resourceInfo, poolInfo, resourceType);
    }

    /**
     * Returns the generated default connector resource for a connection definition.
     *
     * @param moduleName rar module name
     * @param connectionDefName connection definition name
     * @return generated default connector resource name
     */
    public SimpleJndiName getDefaultResourceName(String moduleName, String connectionDefName) {
        return connectorService.getDefaultResourceName(moduleName, connectionDefName);
    }

    /**
     * Provides resource adapter log writer to be given to MCF of a resource-adapter
     *
     * @return PrintWriter
     */
    public PrintWriter getResourceAdapterLogWriter() {
        Logger logger = LogDomains.getLogger(ConnectorRuntime.class, LogDomains.RSR_LOGGER);

        return new PrintWriter(new RAWriterAdapter(logger));
    }

    /**
     * Deletes the connector resource.
     *
     * @param resourceInfo JNDI name of the resource to delete.
     * @throws ConnectorRuntimeException if connector resource deletion fails.
     */
    public void deleteConnectorResource(ResourceInfo resourceInfo) throws ConnectorRuntimeException {
        connectorResourceAdmService.deleteConnectorResource(resourceInfo);
    }

    /**
     * Obtains the connector Descriptor pertaining to rar. If ConnectorDescriptor is present in registry, it is obtained
     * from registry and returned. Else it is explicitly read from directory where rar is exploded.
     *
     * @param rarName Name of the rar
     * @return ConnectorDescriptor pertaining to rar.
     * @throws ConnectorRuntimeException when unable to get descriptor
     */
    @Override
    public ConnectorDescriptor getConnectorDescriptor(String rarName) throws ConnectorRuntimeException {
        return connectorService.getConnectorDescriptor(rarName);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void createActiveResourceAdapter(String moduleDir, String moduleName, ClassLoader loader) throws ConnectorRuntimeException {
        resourceAdapterAdmService.createActiveResourceAdapter(moduleDir, moduleName, loader);
    }

    /**
     * {@inheritDoc}
     */
    public void createActiveResourceAdapter(ConnectorDescriptor connectorDescriptor, String moduleName, String moduleDir, ClassLoader loader) throws ConnectorRuntimeException {
        resourceAdapterAdmService.createActiveResourceAdapter(connectorDescriptor, moduleName, moduleDir, loader);
    }

    /**
     * Creates Active resource Adapter which abstracts the rar module. During the creation of ActiveResourceAdapter, default
     * pools and resources also are created.
     *
     * @param connectorDescriptor object which abstracts the connector deployment descriptor i.e rar.xml and sun-ra.xml.
     * @param moduleName Name of the module
     * @param moduleDir Directory where rar module is exploded.
     * @throws ConnectorRuntimeException if creation fails.
     */
    public void createActiveResourceAdapter(ConnectorDescriptor connectorDescriptor, String moduleName, String moduleDir) throws ConnectorRuntimeException {
        resourceAdapterAdmService.createActiveResourceAdapter(connectorDescriptor, moduleName, moduleDir, null);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void destroyActiveResourceAdapter(String moduleName) throws ConnectorRuntimeException {
        resourceAdapterAdmService.stopActiveResourceAdapter(moduleName);
    }

    /**
     * Returns the MCF instance. If the MCF is already created and present in connectorRegistry that instance is returned.
     * Otherwise it is created explicitly and added to ConnectorRegistry.
     *
     * @param poolInfo Name of the pool.MCF pertaining to this pool is created/returned.
     * @return created/already present MCF instance
     * @throws ConnectorRuntimeException if creation/retrieval of MCF fails
     */
    @Override
    public ManagedConnectionFactory obtainManagedConnectionFactory(PoolInfo poolInfo) throws ConnectorRuntimeException {
        return ccPoolAdmService.obtainManagedConnectionFactory(poolInfo);
    }

    /**
     * Returns the MCF instance. If the MCF is already created and present in connectorRegistry that instance is returned.
     * Otherwise it is created explicitly and added to ConnectorRegistry.
     *
     * @param poolInfo Name of the pool.MCF pertaining to this pool is created/returned.
     * @return created/already present MCF instance
     * @throws ConnectorRuntimeException if creation/retrieval of MCF fails
     */
    @Override
    public ManagedConnectionFactory obtainManagedConnectionFactory(PoolInfo poolInfo, Hashtable env) throws ConnectorRuntimeException {
        return ccPoolAdmService.obtainManagedConnectionFactory(poolInfo, env);
    }

    /**
     * Returns the MCF instances in scenarios where a pool has to return multiple mcfs. Should be used only during JMS RA
     * recovery.
     *
     * @param poolInfo Name of the pool.MCFs pertaining to this pool is created/returned.
     * @return created MCF instances
     * @throws ConnectorRuntimeException if creation/retrieval of MCFs fails
     */
    public ManagedConnectionFactory[] obtainManagedConnectionFactories(PoolInfo poolInfo) throws ConnectorRuntimeException {
        return ccPoolAdmService.obtainManagedConnectionFactories(poolInfo);
    }

    /**
     * Provides connection manager for a pool
     *
     * @param poolInfo pool name
     * @param forceNoLazyAssoc when set to true, lazy association feature will be turned off (even if it is ON via pool
     * attribute)
     * @return ConnectionManager for the pool
     * @throws ConnectorRuntimeException when unable to provide a connection manager
     */
    public ConnectionManager obtainConnectionManager(PoolInfo poolInfo, boolean forceNoLazyAssoc, ResourceInfo resourceInfo) throws ConnectorRuntimeException {
        return ConnectionManagerFactory.getAvailableConnectionManager(poolInfo, forceNoLazyAssoc, resourceInfo);
    }

    @Override
    public <T> T lookupPMResource(ResourceInfo resourceInfo, boolean force) throws NamingException{
        final ResourceInfo ri;
        if (resourceInfo.getName().hasSuffix(PM_JNDI_SUFFIX)) {
            ri = resourceInfo;
        } else {
            SimpleJndiName jndiName = SimpleJndiName.of(resourceInfo.getName() + PM_JNDI_SUFFIX);
            ri = new ResourceInfo(jndiName, resourceInfo.getApplicationName(), resourceInfo.getModuleName());
        }
        try {
            return connectorResourceAdmService.lookup(ri);
        } catch (NamingException ne) {
            if (force && isDAS()) {
                _logger.log(Level.FINE, "jdbc.unable_to_lookup_resource", ri);
                try {
                    return lookupDataSourceInDAS(ri);
                } catch (ConnectorRuntimeException ex) {
                    ne.addSuppressed(ex);
                    throw ne;
                }
            }
            throw ne;
        }
    }

    @Override
    public <T> T lookupPMResource(SimpleJndiName jndiName, boolean force) throws NamingException {
        ResourceInfo resourceInfo = new ResourceInfo(jndiName);
        return lookupPMResource(resourceInfo, force);
    }

    @Override
    public <T> T lookupNonTxResource(SimpleJndiName jndiName, boolean force) throws NamingException {
        ResourceInfo resourceInfo = new ResourceInfo(jndiName);
        return lookupNonTxResource(resourceInfo, force);
    }

    @Override
    public <T> T lookupNonTxResource(ResourceInfo resourceInfo, boolean force) throws NamingException {
        final ResourceInfo ri;
        if (resourceInfo.getName().hasSuffix(NON_TX_JNDI_SUFFIX)) {
            ri = resourceInfo;
        } else {
            SimpleJndiName jndiName = SimpleJndiName.of(resourceInfo.getName() + NON_TX_JNDI_SUFFIX);
            ri = new ResourceInfo(jndiName, resourceInfo.getApplicationName(), resourceInfo.getModuleName());
        }
        try {
            return connectorResourceAdmService.lookup(ri);
        } catch (NamingException ne) {
            if (force && isDAS()) {
                _logger.log(Level.FINE, "jdbc.unable_to_lookup_resource", ri);
                try {
                    return lookupDataSourceInDAS(ri);
                } catch (ConnectorRuntimeException ex) {
                    ne.addSuppressed(ex);
                    throw ne;
                }
            }
            throw ne;
        }
    }

    private boolean isDAS() {
        return serverEnvironmentImpl.isDas();
    }

    /**
     * Get a wrapper datasource specified by the jdbcjndi name This API is intended to be used in the DAS. The motivation
     * for having this API is to provide the CMP backend/ JPA-Java2DB a means of acquiring a connection during the codegen
     * phase. If a user is trying to deploy an JPA-Java2DB app on a remote server, without this API, a resource reference
     * has to be present both in the DAS and the server instance. This makes the deployment more complex for the user since
     * a resource needs to be forcibly created in the DAS Too. This API will mitigate this need.
     *
     * @param resourceInfo jndi name of the resource
     * @return DataSource representing the resource.
     */
    private <T> T lookupDataSourceInDAS(ResourceInfo resourceInfo) throws ConnectorRuntimeException {
            Collection<ConnectorRuntimeExtension> extensions = serviceLocator
                .getAllServices(ConnectorRuntimeExtension.class);
            for (ConnectorRuntimeExtension extension : extensions) {
                return extension.lookupDataSourceInDAS(resourceInfo);
            }
            return null;
    }

    /**
     * Get an sql connection from the DataSource specified by the jdbcJndiName. This API is intended to be used in the DAS.
     * The motivation for having this API is to provide the CMP backend a means of acquiring a connection during the codegen
     * phase. If a user is trying to deploy an app on a remote server, without this API, a resource reference has to be
     * present both in the DAS and the server instance. This makes the deployment more complex for the user since a resource
     * needs to be forcibly created in the DAS Too. This API will mitigate this need.
     *
     * @param resourceInfo the jndi name of the resource being used to get Connection from This resource can either be a pmf
     * resource or a jdbc resource
     * @param user the user used to authenticate this request
     * @param password the password used to authenticate this request
     *
     * @return a java.sql.Connection
     * @throws java.sql.SQLException in case of errors
     */
    public Connection getConnection(ResourceInfo resourceInfo, String user, String password) throws SQLException {
        return ccPoolAdmService.getConnection(resourceInfo, user, password);
    }

    /**
     * Get an sql connection from the DataSource specified by the jdbcJndiName. This API is intended to be used in the DAS.
     * The motivation for having this API is to provide the CMP backend a means of acquiring a connection during the codegen
     * phase. If a user is trying to deploy an app on a remote server, without this API, a resource reference has to be
     * present both in the DAS and the server instance. This makes the deployment more complex for the user since a resource
     * needs to be forcibly created in the DAS Too. This API will mitigate this need.
     *
     * @param resourceInfo the jndi name of the resource being used to get Connection from This resource can either be a pmf
     * resource or a jdbc resource
     *
     * @return a java.sql.Connection
     * @throws java.sql.SQLException in case of errors
     */
    public Connection getConnection(ResourceInfo resourceInfo) throws SQLException {
        return ccPoolAdmService.getConnection(resourceInfo);
    }

    /**
     * Gets the properties of the Java bean connection definition class that have setter methods defined and the default
     * values as provided by the Connection Definition java bean developer. This method is used to get properties of
     * jdbc-data-source<br>
     * To get Connection definition properties for Connector Connection Pool, use
     * ConnectorRuntime.getMCFConfigProperties()<br>
     * When the connection definition class is not found, standard JDBC properties (of JDBC 3.0 Specification) will be
     * returned.<br>
     *
     * @param connectionDefinitionClassName The Connection Definition Java bean class for which overrideable properties are
     * required.
     * @return Map<String, Object> String represents property name and Object is the defaultValue that is a primitive type
     * or String.
     */
    @Override
    public Map<String, Object> getConnectionDefinitionPropertiesAndDefaults(String connectionDefinitionClassName, String resType) {
        return ConnectorConnectionPoolAdminServiceImpl.getConnectionDefinitionPropertiesAndDefaults(connectionDefinitionClassName, resType);
    }

    /**
     * Provides the list of built in custom resources by resource-type and factory-class-name pair.
     *
     * @return map of resource-type & factory-class-name
     */
    @Override
    public Map<String, String> getBuiltInCustomResources() {
        return ConnectorsUtil.getBuiltInCustomResources();
    }

    @Override
    public String[] getSystemConnectorsAllowingPoolCreation() {
        Collection<String> validSystemRarsAllowingPoolCreation = new ArrayList<>();
        Collection<String> validSystemRars = ConnectorsUtil.getSystemRARs();

        for (String systemRarName : systemRarsAllowingPoolCreation) {
            if (validSystemRars.contains(systemRarName)) {
                validSystemRarsAllowingPoolCreation.add(systemRarName);
            }
        }

        return validSystemRarsAllowingPoolCreation.toArray(new String[validSystemRarsAllowingPoolCreation.size()]);
    }

    @Override
    public String[] getConnectionDefinitionNames(String rarName) throws ConnectorRuntimeException {
        return configParserAdmService.getConnectionDefinitionNames(rarName);
    }

    @Override
    public String getSecurityPermissionSpec(String moduleName) throws ConnectorRuntimeException {
        return configParserAdmService.getSecurityPermissionSpec(moduleName);
    }

    @Override
    public String[] getAdminObjectInterfaceNames(String rarName) throws ConnectorRuntimeException {
        return configParserAdmService.getAdminObjectInterfaceNames(rarName);
    }

    @Override
    public String[] getAdminObjectClassNames(String rarName, String intfName) throws ConnectorRuntimeException {
        return configParserAdmService.getAdminObjectClassNames(rarName, intfName);
    }

    @Override
    public boolean hasAdminObject(String rarName, String intfName, String className) throws ConnectorRuntimeException {
        return configParserAdmService.hasAdminObject(rarName, intfName, className);
    }

    @Override
    public Map<String, String> getResourceAdapterConfigProps(String rarName) throws ConnectorRuntimeException {
        Properties properties = configParserAdmService.getResourceAdapterConfigProps(rarName);
        return ConnectorsUtil.convertPropertiesToMap(properties);
    }

    @Override
    public Map<String, String> getMCFConfigProps(String rarName, String connectionDefName) throws ConnectorRuntimeException {
        Properties properties = configParserAdmService.getMCFConfigProps(rarName, connectionDefName);

        return ConnectorsUtil.convertPropertiesToMap(properties);
    }

    @Override
    public Map<String, String> getAdminObjectConfigProps(String rarName, String adminObjectIntf) throws ConnectorRuntimeException {
        Properties properties = configParserAdmService.getAdminObjectConfigProps(rarName, adminObjectIntf);

        return ConnectorsUtil.convertPropertiesToMap(properties);
    }

    @Override
    public Map<String, String> getAdminObjectConfigProps(String rarName, String adminObjectIntf, String adminObjectClass) throws ConnectorRuntimeException {
        Properties properties = configParserAdmService.getAdminObjectConfigProps(rarName, adminObjectIntf, adminObjectClass);

        return ConnectorsUtil.convertPropertiesToMap(properties);
    }

    @Override
    public Map<String, String> getConnectorConfigJavaBeans(String rarName, String connectionDefName, String type) throws ConnectorRuntimeException {
        Properties properties = configParserAdmService.getConnectorConfigJavaBeans(rarName, connectionDefName, type);

        return ConnectorsUtil.convertPropertiesToMap(properties);
    }

    @Override
    public String getActivationSpecClass(String rarName, String messageListenerType) throws ConnectorRuntimeException {
        return configParserAdmService.getActivationSpecClass(rarName, messageListenerType);
    }

    @Override
    public String[] getMessageListenerTypes(String rarName) throws ConnectorRuntimeException {
        return configParserAdmService.getMessageListenerTypes(rarName);
    }

    @Override
    public Map<String, String> getMessageListenerConfigProps(String rarName, String messageListenerType) throws ConnectorRuntimeException {
        Properties properties = configParserAdmService.getMessageListenerConfigProps(rarName, messageListenerType);

        return ConnectorsUtil.convertPropertiesToMap(properties);
    }

    @Override
    public Map<String, String> getMessageListenerConfigPropTypes(String rarName, String messageListenerType) throws ConnectorRuntimeException {
        Properties properties = configParserAdmService.getMessageListenerConfigPropTypes(rarName, messageListenerType);

        return ConnectorsUtil.convertPropertiesToMap(properties);
    }

    /**
     * Causes pool to switch on the matching of connections. It can be either directly on the pool or on the
     * ConnectorConnectionPool object that is bound in JNDI.
     *
     * @param rarName Name of Resource Adpater.
     * @param poolInfo Name of the pool.
     */
    public void switchOnMatching(String rarName, PoolInfo poolInfo) {
        connectorService.switchOnMatching(rarName, poolInfo);
    }

    /**
     * Causes matching to be switched on the ConnectorConnectionPool bound in JNDI
     *
     * @param poolInfo Name of the pool
     * @throws ConnectorRuntimeException when unable to set matching via jndi object
     */
    public void switchOnMatchingInJndi(PoolInfo poolInfo) throws ConnectorRuntimeException {
        ccPoolAdmService.switchOnMatching(poolInfo);
    }

    public GlassfishNamingManager getNamingManager() {
        return namingManager;
    }

    /**
     * The component has been injected with any dependency and will be placed into commission by the subsystem.
     */
    @Override
    public void postConstruct() {
        ccPoolAdmService = (ConnectorConnectionPoolAdminServiceImpl) ConnectorAdminServicesFactory.getService(ConnectorConstants.CCP);
        connectorResourceAdmService = (ConnectorResourceAdminServiceImpl) ConnectorAdminServicesFactory.getService(ConnectorConstants.CR);
        connectorService = new ConnectorService();
        resourceAdapterAdmService = (ResourceAdapterAdminServiceImpl) ConnectorAdminServicesFactory.getService(ConnectorConstants.RA);
        connectorSecurityAdmService = (ConnectorSecurityAdminServiceImpl) ConnectorAdminServicesFactory.getService(ConnectorConstants.SEC);
        adminObjectAdminService = (ConnectorAdminObjectAdminServiceImpl) ConnectorAdminServicesFactory.getService(ConnectorConstants.AOR);
        configParserAdmService = new ConnectorConfigurationParserServiceImpl();

        initializeEnvironment(processEnvironment);
        if (isServer()) {
            getProbeProviderUtil().registerProbeProvider();
        }

        if (isServer() || isEmbedded()) {
            poolMonitoringLevelListener = poolMonitoringLevelListenerProvider.get();

            // Force initialization of the ResourceManager
            getResourceManager();
        }

    }

    /**
     * initializes the connector runtime mode to be SERVER or CLIENT
     *
     * @param processEnvironment ProcessEnvironment
     */
    private void initializeEnvironment(ProcessEnvironment processEnvironment) {
        processType = processEnvironment.getProcessType();
    }

    /**
     * Checks if a connector connection pool has been deployed to this server instance
     *
     * @param poolInfo connection pool name
     * @return boolean indicating whether the resource is deployed or not
     */
    public boolean isConnectorConnectionPoolDeployed(PoolInfo poolInfo) {
        return ccPoolAdmService.isConnectorConnectionPoolDeployed(poolInfo);
    }

    /**
     * Reconfigure a connection pool. This method compares the passed connector connection pool with the one in memory. If
     * the pools are unequal and the MCF properties are changed a pool recreate is required. However if the pools are
     * unequal and the MCF properties are not changed a recreate is not required
     *
     * @param ccp - the Updated connector connection pool object that admin hands over
     * @param excludedProps - A set of excluded property names that we want to be excluded in the comparison check while
     * comparing MCF properties
     * @return true - if a pool restart is required, false otherwise
     * @throws ConnectorRuntimeException when unable to reconfigure ccp
     */
    public boolean reconfigureConnectorConnectionPool(ConnectorConnectionPool ccp, Set excludedProps) throws ConnectorRuntimeException {
        return ccPoolAdmService.reconfigureConnectorConnectionPool(ccp, excludedProps);
    }

    /**
     * Recreate a connector connection pool. This method essentially does the following things: 1. Delete the said connector
     * connection pool<br>
     * 2. Bind the pool to JNDI<br>
     * 3. Create an MCF for this pool and register with the connector registry<br>
     *
     * @param ccp - the ConnectorConnectionPool to publish
     * @throws ConnectorRuntimeException when unable to recreate ccp
     */
    public void recreateConnectorConnectionPool(ConnectorConnectionPool ccp) throws ConnectorRuntimeException {
        ccPoolAdmService.recreateConnectorConnectionPool(ccp);
    }

    /**
     * Creates connector connection pool in the connector container.
     *
     * @param ccp ConnectorConnectionPool instance to be bound to JNDI. This object contains the pool properties.
     * @param connectionDefinitionName Connection definition name against which connection pool is being created
     * @param rarName Name of the resource adapter
     * @param props Properties of MCF which are present in domain.xml These properties override the ones present in ra.xml
     * @param securityMaps List with security maps.
     * @throws ConnectorRuntimeException When creation of pool fails.
     */
    public void createConnectorConnectionPool(ConnectorConnectionPool ccp, String connectionDefinitionName, String rarName,
            List<Property> props, List<SecurityMap> securityMaps) throws ConnectorRuntimeException {
        ccPoolAdmService.createConnectorConnectionPool(ccp, connectionDefinitionName, rarName, props, securityMaps);
    }

    private synchronized org.glassfish.resourcebase.resources.listener.ResourceManager getResourceManager() {
        if (resourceManager == null) {
            try {
                resourceManager = resourceManagerProvider.get();
            } catch (Exception e) {
                return null;
            }
        }

        return resourceManager;
    }

    @Override
    public void cleanUpResourcesAndShutdownAllActiveRAs() {
        Domain domain = domainProvider.get();
        if (domain != null) {
            org.glassfish.resourcebase.resources.listener.ResourceManager rm = getResourceManager();
            if (rm != null) {
                Collection<Resource> resources = ConnectorsUtil.getAllSystemRAResourcesAndPools(domain.getResources());
                resourceManager.undeployResources(resources);

                resources = null;
                Collection<ConnectorRuntimeExtension> extensions = serviceLocator.getAllServices(ConnectorRuntimeExtension.class);
                for (ConnectorRuntimeExtension extension : extensions) {
                    resources = extension.getAllSystemRAResourcesAndPools();
                    resourceManager.undeployResources(resources);
                }
            }
        }

        poolManager.killFreeConnectionsInPools();
        resourceAdapterAdmService.stopAllActiveResourceAdapters();
    }

    @Override
    public void shutdownAllActiveResourceAdapters() {
        resourceAdapterAdmService.stopAllActiveResourceAdapters();
    }

    public PoolManager getPoolManager() {
        return poolManager;
    }

    /**
     * provides the invocationManager
     *
     * @return InvocationManager
     */
    @Override
    public InvocationManager getInvocationManager() {
        return invocationManager;
    }

    public Timer getTimer() {
        return ConnectorTimerProxy.getProxy();
    }

    /**
     * get resource reference descriptors from current component's jndi environment
     *
     * @return set of resource-refs
     */
    @Override
    public Set<ResourceReferenceDescriptor> getResourceReferenceDescriptor() {
        JndiNameEnvironment jndiEnv = componentEnvManager.getCurrentJndiNameEnvironment();
        return jndiEnv == null ? null : jndiEnv.getResourceReferenceDescriptors();
    }

    /**
     * The component is about to be removed from commission
     */
    @Override
    public void preDestroy() {
    }

    /**
     * Obtain the authentication service associated with rar module. Currently only the BasicPassword authentication is
     * supported.
     *
     * @param rarName Rar module Name
     * @param poolInfo Name of the pool. Used for creation of BasicPasswordAuthenticationService
     * @return AuthenticationService connector runtime's authentication service
     */
    public AuthenticationService getAuthenticationService(String rarName, PoolInfo poolInfo) {
        return connectorSecurityAdmService.getAuthenticationService(rarName, poolInfo);
    }

    /**
     * Checks whether the executing environment is embedded
     *
     * @return true if execution environment is embedded
     */
    @Override
    public boolean isEmbedded() {
        return ProcessEnvironment.ProcessType.Embedded.equals(processType);
    }

    /**
     * Checks whether the executing environment is non-acc (standalone)
     *
     * @return true if execution environment is non-acc (standalone)
     */
    public boolean isNonACCRuntime() {
        return ProcessEnvironment.ProcessType.Other.equals(processType);
    }

    /**
     * Checks whether the executing environment is application server
     *
     * @return true if execution environment is server false if it is client
     */
    @Override
    public boolean isServer() {
        return ProcessEnvironment.ProcessType.Server.equals(processType);
    }

    /**
     * Checks whether the executing environment is appclient container runtime
     *
     * @return true if execution environment is appclient container false if it is not ACC
     */
    public boolean isACCRuntime() {
        return ProcessEnvironment.ProcessType.ACC.equals(processType);
    }

    /**
     * provides the current transaction
     *
     * @return Transaction
     * @throws SystemException when unable to get the transaction
     */
    public Transaction getTransaction() throws SystemException {
        return getTransactionManager().getTransaction();
    }

    /**
     * provides the transactionManager
     *
     * @return TransactionManager
     */
    @Override
    public JavaEETransactionManager getTransactionManager() {
        if (transactionManager == null) {
            transactionManager = javaEETransactionManagerProvider.get();
        }
        return transactionManager;
    }

    /**
     * register the connector naming event listener
     *
     * @param listener connector-naming-event-listener
     */
    @Override
    public void registerConnectorNamingEventListener(ConnectorNamingEventListener listener) {
        connectorResourceAdmService.getResourceRebindEventNotifier().addListener(listener);
    }

    /**
     * unregister the connector naming event listener
     *
     * @param listener connector-naming-event-listener
     */
    @Override
    public void unregisterConnectorNamingEventListener(ConnectorNamingEventListener listener) {
        connectorResourceAdmService.getResourceRebindEventNotifier().removeListener(listener);
    }

    @Override
    public ResourcePool getConnectionPoolConfig(PoolInfo poolInfo) {
        _logger.log(Level.FINEST, "getConnectionPoolConfig(poolInfo={0})", poolInfo);
        ResourcePool pool = ResourcesUtil.createInstance().getPoolConfig(poolInfo);
        if (pool != null) {
            return pool;
        }
        // It is possible that the application scoped resources is being deployed
        if (isApplicationScopedResource(poolInfo) || isModuleScopedResource(poolInfo)) {
            Resources asc = ResourcesRegistry.getResources(poolInfo.getApplicationName(), poolInfo.getModuleName());
            pool = ConnectorsUtil.getConnectionPoolConfig(poolInfo, asc);
            if (pool != null) {
                return pool;
            }
        }
        return null;
    }

    @Override
    public boolean pingConnectionPool(PoolInfo poolInfo) throws ResourceException {
        return ccPoolAdmService.testConnectionPool(poolInfo);
    }

    public PoolType getPoolType(PoolInfo poolInfo) throws ConnectorRuntimeException {
        return ccPoolAdmService.getPoolType(poolInfo);
    }

    /**
     * provides work manager proxy that is Serializable
     *
     * @param poolId ThreadPoolId
     * @param moduleName resource-adapter name
     * @param rarCL classloader of the resource-adapter
     * @return WorkManager
     * @throws ConnectorRuntimeException when unable to get work manager
     */
    public WorkManager getWorkManagerProxy(String poolId, String moduleName, ClassLoader rarCL) throws ConnectorRuntimeException {
        return workManagerFactoryProvider.get().getWorkManagerProxy(poolId, moduleName, rarCL);
    }

    /**
     * provides XATerminator proxy that is Serializable
     *
     * @param moduleName resource-adapter name
     * @return XATerminator
     */
    public XATerminator getXATerminatorProxy(String moduleName) {
        XATerminator xat = getTransactionManager().getXATerminator();
        return new XATerminatorProxy(xat);
    }

    public void removeWorkManagerProxy(String moduleName) {
        workManagerFactoryProvider.get().removeWorkManager(moduleName);
    }

    public void addAdminObject(String appName, String connectorName, ResourceInfo resourceInfo, String adminObjectType, String adminObjectClassName, Properties props) throws ConnectorRuntimeException {
        adminObjectAdminService.addAdminObject(appName, connectorName, resourceInfo, adminObjectType, adminObjectClassName, props);
    }

    public void deleteAdminObject(ResourceInfo resourceInfo) throws ConnectorRuntimeException {
        adminObjectAdminService.deleteAdminObject(resourceInfo);
    }

    public ClassLoader getSystemRARClassLoader(String rarName) throws ConnectorRuntimeException {
        return connectorsClassLoaderUtil.getSystemRARClassLoader(rarName);
    }

    /**
     * Given the module directory, creates a connector-class-finder (class-loader) for the module
     *
     * @param moduleDirectory rar module directory for which classloader is needed
     * @param parent parent classloader<br>
     * For standalone rars, pass null, as the parent should be common-class-loader that will be automatically taken care by
     * ConnectorClassLoaderService.<br>
     * For embedded rars, parent is necessary<br>
     * @return classloader created for the module
     */
    @Override
    public ClassLoader createConnectorClassLoader(String moduleDirectory, ClassLoader parent, String rarModuleName) throws ConnectorRuntimeException {
        List<URI> libraries = ConnectorsUtil.getInstalledLibrariesFromManifest(moduleDirectory, serverEnvironmentImpl);
        return connectorsClassLoaderUtil.createRARClassLoader(moduleDirectory, parent, rarModuleName, libraries);
    }

    public ResourceDeployer getResourceDeployer(Object resource) {
        return resourceManagerFactoryProvider.get().getResourceDeployer(resource);
    }

    /**
     * Add the resource adapter configuration to the connector registry
     *
     * @param rarName rarmodule
     * @param raConfig Resource Adapter configuration object
     * @throws ConnectorRuntimeException if the addition fails.
     */

    public void addResourceAdapterConfig(String rarName, ResourceAdapterConfig raConfig) throws ConnectorRuntimeException {
        resourceAdapterAdmService.addResourceAdapterConfig(rarName, raConfig);
    }

    /**
     * Delete the resource adapter configuration to the connector registry
     *
     * @param rarName rarmodule
     */

    public void deleteResourceAdapterConfig(String rarName) throws ConnectorRuntimeException {
        resourceAdapterAdmService.deleteResourceAdapterConfig(rarName);
    }

    /**
     * register the connector application with registry
     *
     * @param rarModule resource-adapter module
     */
    public void registerConnectorApplication(ConnectorApplication rarModule) {
        connectorRegistry.addConnectorApplication(rarModule);
    }

    /**
     * unregister the connector application from registry
     *
     * @param rarName resource-adapter name
     */
    public void unregisterConnectorApplication(String rarName) {
        connectorRegistry.removeConnectorApplication(rarName);
    }

    /**
     * undeploy resources of the module
     *
     * @param rarName resource-adapter name
     */
    public void undeployResourcesOfModule(String rarName) {
        ConnectorApplication app = connectorRegistry.getConnectorApplication(rarName);
        app.undeployResources();
    }

    /**
     * deploy resources of the module
     *
     * @param rarName resource-adapter name
     */
    public void deployResourcesOfModule(String rarName) {
        ConnectorApplication app = connectorRegistry.getConnectorApplication(rarName);
        app.deployResources();
    }

    public ActiveRAFactory getActiveRAFactory() {
        return activeRAFactory;
    }

    public Applications getApplications() {
        return applicationsProvider.get();
    }

    public ApplicationRegistry getAppRegistry() {
        return applicationRegistryProvider.get();
    }

    public ApplicationArchivist getApplicationArchivist() {
        return applicationArchivistProvider.get();
    }

    public FileArchive getFileArchive() {
        return fileArchiveProvider.get();
    }

    public Domain getDomain() {
        return domainProvider.get();
    }

    public ServerEnvironment getServerEnvironment() {
        return serverEnvironmentImpl;
    }

    @Override
    public void createActiveResourceAdapterForEmbeddedRar(String rarModuleName) throws ConnectorRuntimeException {
        connectorService.createActiveResourceAdapterForEmbeddedRar(rarModuleName);
    }

    /**
     * Check whether ClassLoader is permitted to access this resource adapter. If the RAR is deployed and is not a
     * standalone RAR, then only the ClassLoader that loaded the archive (any of its child) should be able to access it.
     * Otherwise everybody can access the RAR.
     *
     * @param rarName Resource adapter module name.
     * @param loader <code>ClassLoader</code> to verify.
     */
    public boolean checkAccessibility(String rarName, ClassLoader loader) {
        return connectorService.checkAccessibility(rarName, loader);
    }

    public void loadDeferredResourceAdapter(String rarName) throws ConnectorRuntimeException {
        connectorService.loadDeferredResourceAdapter(rarName);
    }

    public SecurityRoleMapperFactory getSecurityRoleMapperFactory() {
        return securityRoleMapperFactoryProvider.get();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public CallbackHandler getCallbackHandler() {
        // TODO V3 hack to make sure that SecurityServicesUtil is initialized before ContainerCallbackHander
        securityServicesUtilProvider.get();
        return containerCallbackHandlerProvider.get();
    }

    public ConnectorArchivist getConnectorArchvist() throws ConnectorRuntimeException {
        ArchivistFactory archivistFactory = archivistFactoryProvider.get();
        return archivistFactory.getArchivist(archiveType);
    }

    public WorkContextHandler getWorkContextHandler() {
        return workContextHandlerProvider.get();
    }

    public ComponentEnvManager getComponentEnvManager() {
        return componentEnvManager;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public DelegatingClassLoader getConnectorClassLoader() {
        return classLoaderHierarchy.getConnectorClassLoader(null);
    }

    public ClassLoaderHierarchy getClassLoaderHierarchy() {
        return classLoaderHierarchy;
    }

    @Override
    public void registerDataSourceDefinitions(com.sun.enterprise.deployment.Application application) {
        for (ConnectorRuntimeExtension extension : serviceLocator.getAllServices(ConnectorRuntimeExtension.class)) {
            extension.registerDataSourceDefinitions(application);
        }
    }

    @Override
    public void unRegisterDataSourceDefinitions(com.sun.enterprise.deployment.Application application) {
        for (ConnectorRuntimeExtension extension : serviceLocator.getAllServices(ConnectorRuntimeExtension.class)) {
            extension.unRegisterDataSourceDefinitions(application);
        }
    }

    public void registerMailSessions(com.sun.enterprise.deployment.Application application) {
        mailSessionDeployerProvider.get().registerMailSessions(application);
    }

    public void unRegisterMailSessions(com.sun.enterprise.deployment.Application application) {
        mailSessionDeployerProvider.get().unRegisterMailSessions(application);
    }

    @Override
    public List<WorkSecurityMap> getWorkSecurityMap(String raName) {
        List<WorkSecurityMap> workSecurityMap = ConnectorsUtil.getWorkSecurityMaps(raName, getResources());
        List<WorkSecurityMap> appScopedMap = null;
        String appName = raName;

        if (!isStandAloneRA(raName)) {
            appName = getApplicationNameOfEmbeddedRar(raName);
            Application application = getApplications().getApplication(appName);
            if (application != null) {
                // embedded RAR
                String resourceAdapterName = getRarNameFromApplication(raName);
                Module module = application.getModule(resourceAdapterName);
                if (module != null) {
                    Resources msr = module.getResources();
                    if (msr != null) {
                        appScopedMap = ConnectorsUtil.getWorkSecurityMaps(raName, msr);
                    }
                }
            }
        } else {
            Application app = getApplications().getApplication(appName);
            if (app != null) {
                Resources asc = app.getResources();
                if (asc != null) {
                    appScopedMap = ConnectorsUtil.getWorkSecurityMaps(raName, asc);
                }
            }
        }

        if (appScopedMap != null) {
            workSecurityMap.addAll(appScopedMap);
        }

        return workSecurityMap;
    }

    public Resources getResources(PoolInfo poolInfo) {
        if (isModuleScopedResource(poolInfo)) {
            Application application = getApplications().getApplication(poolInfo.getApplicationName());
            if (application == null) {
                return null;
            }

            return application.getModule(poolInfo.getModuleName()).getResources();

        }

        if (isApplicationScopedResource(poolInfo)) {
            Application application = getApplications().getApplication(poolInfo.getApplicationName());
            if (application == null) {
                return null;
            }

            return application.getResources();
        }

        return getResources();
    }

    public Resources getResources(ResourceInfo resourceInfo) {
        if (isModuleScopedResource(resourceInfo)) {
            return
                getApplications().getApplication(resourceInfo.getApplicationName())
                                 .getModule(resourceInfo.getModuleName())
                                 .getResources();
        }

        if (isApplicationScopedResource(resourceInfo)) {
            return
                getApplications().getApplication(resourceInfo.getApplicationName())
                                 .getResources();
        }

        return getResources();
    }

    public Resources getResources() {
        if (globalResources == null) {
            globalResources = domainProvider.get().getResources();
        }

        return globalResources;
    }

    @Override
    public long getShutdownTimeout() {
        return ConnectorsUtil.getShutdownTimeout(connectorServiceProvider.get());
    }

    /**
     * Flush Connection pool by reinitializing the connections established in the pool.
     *
     * @param poolInfo
     * @throws com.sun.appserv.connectors.internal.api.ConnectorRuntimeException
     */
    @Override
    public boolean flushConnectionPool(PoolInfo poolInfo) throws ConnectorRuntimeException {
        return ccPoolAdmService.flushConnectionPool(poolInfo);
    }

    /**
     * Get jdbc driver implementation class names list for the dbVendor and resource type supplied.
     *
     * @param dbVendor
     * @param resType
     * @return all jdbc driver implementation class names
     */
    @Override
    public Set<String> getJdbcDriverClassNames(String dbVendor, String resType) {
        return driverLoader.getJdbcDriverClassNames(dbVendor, resType);
    }

    /**
     * Get jdbc driver implementation class names list for the dbVendor and resource type supplied. If introspect is true,
     * classnames are got from introspection of the jdbc driver jar. Else a pre-defined list is used to retrieve the class
     * names.
     *
     * @param dbVendor
     * @param resType
     * @param introspect
     * @return all jdbc driver implementation class names
     */
    @Override
    public Set<String> getJdbcDriverClassNames(String dbVendor, String resType, boolean introspect) {
        return driverLoader.getJdbcDriverClassNames(dbVendor, resType, introspect);
    }

    /**
     * returns the bean validator that can be used to validate various connector bean artifacts (ResourceAdapter,
     * ManagedConnetionFactory, AdministeredObject, ActivationSpec)
     *
     * @return ConnectorBeanValidator
     */
    public ConnectorJavaBeanValidator getConnectorBeanValidator() {
        return connectorBeanValidator;
    }

    /**
     * Check if ping is on during pool creation.
     *
     * @param poolInfo
     * @return
     */
    @Override
    public boolean getPingDuringPoolCreation(PoolInfo poolInfo) {
        return ConnectorsUtil.getPingDuringPoolCreation(poolInfo, getResources(poolInfo));
    }

    /**
     * Get jdbc database vendor names list.
     *
     * @return set of common database vendor names
     */
    @Override
    public Set<String> getDatabaseVendorNames() {
        return driverLoader.getDatabaseVendorNames();
    }

    public boolean isJdbcPoolMonitoringEnabled() {
        boolean enabled = false;
        if (poolMonitoringLevelListener != null) {
            enabled = poolMonitoringLevelListener.getJdbcPoolMonitoringEnabled();
        }

        return enabled;
    }

    public boolean isConnectorPoolMonitoringEnabled() {
        boolean enabled = false;
        if (poolMonitoringLevelListener != null) {
            enabled = poolMonitoringLevelListener.getConnectorPoolMonitoringEnabled();
        }

        return enabled;
    }

    @Override
    public void associateResourceAdapter(String rarName, ResourceAdapterAssociation raa) throws ResourceException {
        resourceAdapterAdmService.associateResourceAdapter(rarName, raa);
    }

    public org.glassfish.resourcebase.resources.listener.ResourceManager getGlobalResourceManager() {
        return getResourceManager();
    }

    @Override
    public List<String> getConfidentialProperties(String rarName, String type, String... keyFields) throws ConnectorRuntimeException {
        ConnectorConfigParser configParser = ConnectorConfigParserFactory.getParser(type);
        if (configParser == null) {
            throw new ConnectorRuntimeException("Invalid type : " + type);
        }

        return configParser.getConfidentialProperties(getConnectorDescriptor(rarName), rarName, keyFields);
    }
}
