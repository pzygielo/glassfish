/*
 * Copyright (c) 2006, 2018 Oracle and/or its affiliates. All rights reserved.
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

package org.glassfish.security.services.commands;

//import com.sun.enterprise.config.serverbeans.*;
import com.sun.enterprise.config.serverbeans.AdminService;
import com.sun.enterprise.config.serverbeans.AuthRealm;
import com.sun.enterprise.config.serverbeans.Config;
import com.sun.enterprise.config.serverbeans.ConfigBeansUtilities;
import com.sun.enterprise.config.serverbeans.Domain;
import com.sun.enterprise.config.serverbeans.SecurityService;
import com.sun.enterprise.config.serverbeans.Server;
import com.sun.enterprise.security.auth.login.LDAPLoginModule;
import com.sun.enterprise.security.auth.realm.Realm;
import com.sun.enterprise.security.auth.realm.ldap.LDAPRealm;
import com.sun.enterprise.util.StringUtils;
import com.sun.enterprise.util.SystemPropertyConstants;
import com.sun.enterprise.util.i18n.StringManager;

import jakarta.inject.Inject;

import java.beans.PropertyVetoException;
import java.util.List;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.naming.AuthenticationNotSupportedException;
import javax.naming.Context;
import javax.naming.InitialContext;

import org.glassfish.api.ActionReport;
import org.glassfish.api.Param;
import org.glassfish.api.admin.AccessRequired;
import org.glassfish.api.admin.AdminCommand;
import org.glassfish.api.admin.AdminCommandContext;
import org.glassfish.api.admin.AdminCommandSecurity;
import org.glassfish.api.admin.ExecuteOn;
import org.glassfish.api.admin.RestEndpoint;
import org.glassfish.api.admin.RestEndpoints;
import org.glassfish.api.admin.RuntimeType;
import org.glassfish.config.support.CommandTarget;
import org.glassfish.config.support.TargetType;
import org.glassfish.hk2.api.PerLookup;
import org.glassfish.internal.api.Target;
import org.glassfish.security.services.config.AuthenticationService;
import org.glassfish.security.services.config.LoginModuleConfig;
import org.glassfish.security.services.config.SecurityConfigurations;
import org.glassfish.security.services.config.SecurityProvider;
import org.glassfish.security.services.config.SecurityProviderConfig;
import org.glassfish.security.services.impl.ServiceLogging;
import org.jvnet.hk2.annotations.Service;
import org.jvnet.hk2.config.RetryableException;
import org.jvnet.hk2.config.Transaction;
import org.jvnet.hk2.config.TransactionFailure;
import org.jvnet.hk2.config.types.Property;

/**  A convenience command to configure LDAP for administration. There are several properties and attributes that
 *   user needs to remember and that's rather user unfriendly. That's why this command is being developed.
 * @author &#2325;&#2375;&#2342;&#2366;&#2352 (km@dev.java.net)
 * @since GlassFish V3
 */
@Service(name="configure-ldap-for-admin")
@PerLookup
@ExecuteOn({RuntimeType.DAS, RuntimeType.INSTANCE})
@TargetType({CommandTarget.DAS,CommandTarget.STANDALONE_INSTANCE,CommandTarget.CLUSTER, CommandTarget.CONFIG})
@RestEndpoints({
    @RestEndpoint(configBean=Domain.class,
        opType=RestEndpoint.OpType.POST,
        path="configure-ldap-for-admin",
        description="configure-ldap-for-admin")
})
public class LDAPAdminAccessConfigurator implements AdminCommand, AdminCommandSecurity.Preauthorization {

    @Param (name="basedn", shortName="b", optional=false)
    public volatile String basedn;

    @Param(name="url", optional=true)
    public volatile String url = "ldap://localhost:389"; // the default port for LDAP on localhost


    @Param(name="ldap-group", shortName="g", optional=true)
    public volatile String ldapGroupName;

    @Inject
    Target targetService;

    @Inject
    private ConfigBeansUtilities configBeansUtilities;

    //TODO: not sure what to do with --target here
    @Param(name = "target", optional = true, defaultValue =
    SystemPropertyConstants.DEFAULT_SERVER_INSTANCE_NAME)
    private String target;

    private final static String ADMIN_SERVER = "server"; //this needs to be at central place, oh well
    private static final StringManager lsm = StringManager.getManager(LDAPAdminAccessConfigurator.class);
    private static final String DIR_P    = "directory";
    private static final String BASEDN_P = "base-dn";
    private static final String JAAS_P   = "jaas-context";
    private static final String JAAS_V   = "ldapRealm";
    public static final String LDAP_SOCKET_FACTORY = "java.naming.ldap.factory.socket";
    public static final String DEFAULT_SSL_LDAP_SOCKET_FACTORY = "com.sun.enterprise.security.auth.realm.ldap.CustomSocketFactory";
    public static final String LDAPS_URL = "ldaps://";

    private static final Logger logger = Logger.getLogger(ServiceLogging.SEC_COMMANDS_LOGGER, ServiceLogging.SHARED_LOGMESSAGE_RESOURCE);

    private static final String AUTHENTICATION_SERVICE_PROVIDER_NAME = "adminAuth";
    private static final String FILE_REALM_SECURITY_PROVIDER_NAME = "adminFile";
    private static final String ADMIN_FILE_LM_NAME = "adminFileLM";

    private Config asc;

    @AccessRequired.To("update")
    private AuthRealm adminAuthRealm;

    @AccessRequired.To("update")
    private AdminService adminService;

    @AccessRequired.To("update")
    private SecurityProvider fileRealmProvider;

    @Inject
    private SecurityConfigurations securityConfigs;

    @Override
    public boolean preAuthorization(AdminCommandContext context) {
        asc = chooseConfig();
        final SecurityService ss = asc.getSecurityService();
        adminAuthRealm = getAdminRealm(ss);
        adminService = asc.getAdminService();
        final AuthenticationService adminAuthService = (AuthenticationService)
                securityConfigs.getSecurityServiceByName(AUTHENTICATION_SERVICE_PROVIDER_NAME);
        final ActionReport report = context.getActionReport();
        if (adminAuthService == null) {
            report.setMessage(lsm.getString("ldap.noExistingAtnService", AUTHENTICATION_SERVICE_PROVIDER_NAME));
            report.setActionExitCode(ActionReport.ExitCode.FAILURE);
            return false;
        }
        fileRealmProvider = adminAuthService.getSecurityProviderByName(FILE_REALM_SECURITY_PROVIDER_NAME);
        if (fileRealmProvider == null) {
            report.setMessage(lsm.getString("ldap.noExistingAtnProvider", FILE_REALM_SECURITY_PROVIDER_NAME));
            report.setActionExitCode(ActionReport.ExitCode.FAILURE);
            return false;
        }
        if ( ! "LoginModule".equals(fileRealmProvider.getType())) {
            report.setMessage(lsm.getString("ldap.fileRealmProviderNotLoginModuleType",
                    FILE_REALM_SECURITY_PROVIDER_NAME,
                    adminAuthService.getName(),
                    fileRealmProvider.getType()));
            report.setActionExitCode(ActionReport.ExitCode.FAILURE);
            return false;
        }
        return true;
    }



    /** Field denoting the name of the realm used for administration. This is fixed in entire of v3. Note that
     *  the same name is used in admin GUI's web.xml and sun-web.xml. The name of the realm is the key, the
     *  underlying backend (LDAP, File, Database) can change.
     */
    public static final String FIXED_ADMIN_REALM_NAME = "admin-realm";
    public static final String ORIG_ADMIN_REALM_NAME  = "admin-realm-original";

    @Override
    public void execute(AdminCommandContext context) {
        ActionReport rep = context.getActionReport();
        StringBuilder sb = new StringBuilder();
        if(url != null) {
            if (!url.startsWith("ldap://") && !url.startsWith("ldaps://")) {
                url = "ldap://" + url;        //it's ok to accept just host:port
            }
        }
        if (!pingLDAP(sb)) {
            rep.setMessage(sb.toString());
            rep.setActionExitCode(ActionReport.ExitCode.FAILURE);
            return;
        }
        try {
            configure(sb);
            //Realm.getInstance(FIXED_ADMIN_REALM_NAME).refresh();
            rep.setMessage(sb.toString());
            rep.setActionExitCode(ActionReport.ExitCode.SUCCESS);
        } catch(TransactionFailure tf) {
            rep.setMessage(tf.getMessage());
            rep.setActionExitCode(ActionReport.ExitCode.FAILURE);
        } catch (PropertyVetoException e) {
            rep.setMessage(e.getMessage());
            rep.setActionExitCode(ActionReport.ExitCode.FAILURE);
        } catch (RetryableException re) {
            rep.setMessage(re.getMessage());
            rep.setActionExitCode(ActionReport.ExitCode.FAILURE);
        }
/*
        catch (NoSuchRealmException e) {
            ActionReport ar = rep.addSubActionsReport();
            ar.setMessage(lsm.getString("realm.not.refreshed"));
            ar.setActionExitCode(ActionReport.ExitCode.WARNING);
        } catch (BadRealmException e) {
            ActionReport ar = rep.addSubActionsReport();
            ar.setMessage(lsm.getString("realm.not.refreshed"));
            ar.setActionExitCode(ActionReport.ExitCode.WARNING);
        }
*/
    }

    private void configure(StringBuilder sb) throws TransactionFailure, PropertyVetoException, RetryableException {

        //createBackupRealm(sb, getAdminRealm(asc.getSecurityService()), getNewRealmName(asc.getSecurityService()));

        Transaction t = new Transaction();
        final SecurityService w_asc = t.enroll(asc.getSecurityService());
        AdminService w_adminSvc = t.enroll(asc.getAdminService());
        deleteRealm(w_asc, sb);
        createRealm(w_asc, sb);
        configureAdminService(w_adminSvc);
        updateSecurityProvider(t, fileRealmProvider, sb);
        t.commit();
    }

/*    private String getNewRealmName(SecurityService ss) {
        List<AuthRealm> realms = ss.getAuthRealm();
        String pref = ORIG_ADMIN_REALM_NAME + "-";
        int index = 0;  //last one
        for (AuthRealm realm : realms) {
            if (realm.getName().indexOf(pref) >= 0) {
                index = Integer.parseInt(realm.getName().substring(pref.length()));
            }
        }
        return pref + (index+1);
    }*/

    private void updateSecurityProvider(final Transaction t, final SecurityProvider w_sp,
            final StringBuilder sb) throws TransactionFailure, PropertyVetoException {
        for (SecurityProviderConfig spc : w_sp.getSecurityProviderConfig()) {
            if ((spc instanceof LoginModuleConfig) && spc.getName().equals(ADMIN_FILE_LM_NAME)) {
                final LoginModuleConfig w_lmConfig = t.enroll((LoginModuleConfig) spc);
                w_lmConfig.setModuleClass(LDAPLoginModule.class.getName());
                sb.append(lsm.getString("ldap.authProviderConfigOK", w_sp.getName()));
                return;
            }
        }
        throw new TransactionFailure(
                lsm.getString("ldap.noAuthProviderConfig", w_sp.getName(), ADMIN_FILE_LM_NAME));
    }

    private AuthRealm getAdminRealm(SecurityService ss) {
        List<AuthRealm> realms = ss.getAuthRealm();
        for (AuthRealm realm : realms) {
            if (FIXED_ADMIN_REALM_NAME.equals(realm.getName()))
                return realm;
        }
        return null;  //unlikely - represents an assertion
    }

    private void configureAdminService(AdminService as) throws PropertyVetoException, TransactionFailure {
        as.setAuthRealmName(FIXED_ADMIN_REALM_NAME);  //just in case ...
    }

    private void createRealm(SecurityService w_ss, final StringBuilder sb) throws TransactionFailure, PropertyVetoException {
        AuthRealm ldapr = createLDAPRealm(w_ss);
        w_ss.getAuthRealm().add(ldapr);
        appendNL(sb,lsm.getString("ldap.realm.setup", FIXED_ADMIN_REALM_NAME));
    }

    // delete and create a new realm to replace it in a single transaction
    private void deleteRealm(SecurityService w_ss, final StringBuilder sb) throws TransactionFailure {


        AuthRealm oldAdminRealm = getAdminRealm(w_ss);
        w_ss.getAuthRealm().remove(oldAdminRealm);
        appendNL(sb,"...");
        //AuthRealm ldapr = createLDAPRealm(ss);
        //ss.getAuthRealm().add(ldapr);
        //appendNL(sb,lsm.getString("ldap.realm.setup", FIXED_ADMIN_REALM_NAME));
    }

    // this had been called renameRealm, but in the SecurityConfigListener, the method authRealmUpdated actually does a create...
/*    private void createBackupRealm(final StringBuilder sb, AuthRealm realm, final String to) throws PropertyVetoException, TransactionFailure {
        SingleConfigCode<AuthRealm> scc = new SingleConfigCode<AuthRealm>() {
            @Override
            public Object run(AuthRealm realm) throws PropertyVetoException, TransactionFailure {
                appendNL(sb, lsm.getString("config.to.ldap", FIXED_ADMIN_REALM_NAME, to));
                realm.setName(to);
                return realm;
            }
        };
        ConfigSupport.apply(scc, realm);
    }*/

    private AuthRealm createLDAPRealm(SecurityService ss) throws TransactionFailure, PropertyVetoException {
        AuthRealm ar = ss.createChild(AuthRealm.class);
        ar.setClassname(LDAPRealm.class.getName());
        ar.setName(FIXED_ADMIN_REALM_NAME);
        List<Property> props = ar.getProperty();

        Property p = ar.createChild(Property.class);
        p.setName(DIR_P);
        p.setValue(url);
        props.add(p);

        p = ar.createChild(Property.class);
        p.setName(BASEDN_P);
        p.setValue(basedn);
        props.add(p);

        p = ar.createChild(Property.class);
        p.setName(JAAS_P);
        p.setValue(JAAS_V);
        props.add(p);

        if (ldapGroupName!= null) {
            p = ar.createChild(Property.class);
            p.setName(Realm.PARAM_GROUP_MAPPING);
            p.setValue(ldapGroupName +"->asadmin"); //appears as gfdomain1->asadmin in domain.xml
            props.add(p);
        }

        return ar;
    }

    private boolean pingLDAP(StringBuilder sb) {
        Properties env = new Properties();
        env.put(Context.INITIAL_CONTEXT_FACTORY, "com.sun.jndi.ldap.LdapCtxFactory");
        env.put(Context.PROVIDER_URL, url);

        if (url != null && url.startsWith(LDAPS_URL)) {
            env.put(LDAP_SOCKET_FACTORY,
                    DEFAULT_SSL_LDAP_SOCKET_FACTORY);
        }
        try {
            new InitialContext(env);
            appendNL(sb,lsm.getString("ldap.ok", url));
            return true;
        } catch (AuthenticationNotSupportedException anse) {
            //CR 6944776
            //If the server throws this error, it is up
            //and is configured with Anonymous bind disabled.
            //Ignore this error while configuring ldap for admin
            appendNL(sb,lsm.getString("ldap.ok", url));
            return true;
        } catch(Exception e) {
            appendNL(sb,lsm.getString("ldap.na", url, e.getClass().getName(), e.getMessage()));
            if (logger.isLoggable(Level.FINE)) {
                logger.log(Level.FINE, StringUtils.getStackTrace(e));
            }
            return false;
        }
    }

    private static void appendNL(StringBuilder sb, String s) {
        sb.append(s).append("%%%EOL%%%");
    }

    private Config chooseConfig() {
        Server s = configBeansUtilities.getServerNamed(ADMIN_SERVER);
        String ac = s.getConfigRef();
        return targetService.getConfig(ac);
    }
}
