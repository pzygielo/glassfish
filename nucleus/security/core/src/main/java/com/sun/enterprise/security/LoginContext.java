/*
 * Copyright (c) 2025 Contributors to the Eclipse Foundation.
 * Copyright (c) 1997, 2021 Oracle and/or its affiliates. All rights reserved.
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

package com.sun.enterprise.security;

import com.sun.enterprise.security.auth.login.ClientPasswordLoginModule;
import com.sun.enterprise.security.auth.login.LoginContextDriver;
import com.sun.enterprise.security.auth.login.common.LoginException;
import com.sun.enterprise.security.common.SecurityConstants;

import javax.security.auth.callback.CallbackHandler;

import static org.glassfish.main.jdke.props.SystemProperties.setProperty;

/**
 * This class is kept for CTS. Ideally we should move away from it. The login can be done via the following call:
 *
 * <pre>
 * // Initialize the ORB.
 * try {
 *     LoginContext lc = new LoginContext();
 *     lc.login("john", "john123");
 * } catch (LoginException le) {
 *     le.printStackTrace();
 * }
 * </pre>
 *
 * Ideally the login should be done with the system property -Dj2eelogin.name and -Dj2eelogin.password
 *
 * @author Harpreet Singh (hsingh@eng.sun.com)
 */

public final class LoginContext {

    // declaring this different from the Appcontainer as
    // this will be called from standalone clients.
    private final CallbackHandler handler;

    /**
     * Creates the LoginContext with the defauly callback handler
     */
    public LoginContext() {
        this.handler = new com.sun.enterprise.security.auth.login.LoginCallbackHandler(false);
    }

    /**
     * Login method to login username and password
     * @deprecated Sets password as a system property
     */
    @Deprecated(forRemoval = true, since = "7.1.0")
    public void login(String user, String pass) throws LoginException {
        setProperty(ClientPasswordLoginModule.LOGIN_NAME, user, false);
        setProperty(ClientPasswordLoginModule.LOGIN_PASSWORD, pass, false);
        // Since this is  a private api and the user is not supposed to use
        // this. We use the default the LoginCallbackHandler.
        LoginContextDriver.doClientLogin(SecurityConstants.USERNAME_PASSWORD, handler);
    }

    /**
     * This method has been provided to satisfy the CTS Porting Package requirement for logging in a certificate
     */
    public void login(String username, byte[] authData) throws LoginException {
        // do nothing
    }
}
