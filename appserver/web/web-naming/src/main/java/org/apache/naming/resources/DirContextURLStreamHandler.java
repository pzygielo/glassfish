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

package org.apache.naming.resources;

import java.io.IOException;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.util.Hashtable;

import javax.naming.directory.DirContext;

import static org.glassfish.main.jdke.props.SystemProperties.setProperty;

/**
 * Stream handler to a JNDI directory context.
 *
 * @author <a href="mailto:remm@apache.org">Remy Maucherat</a>
 * @version $Revision: 1.3 $
 */
public class DirContextURLStreamHandler
    extends URLStreamHandler {


    // ----------------------------------------------------------- Constructors


    public DirContextURLStreamHandler() {
    }


    public DirContextURLStreamHandler(DirContext context) {
        this.context = context;
    }


    // -------------------------------------------------------------- Variables


    /**
     * Bindings class loader - directory context. Keyed by CL id.
     */
    private static Hashtable<ClassLoader, DirContext> clBindings =
        new Hashtable<ClassLoader, DirContext>();


    /**
     * Bindings thread - directory context. Keyed by thread id.
     */
    private static Hashtable<Thread, DirContext> threadBindings =
        new Hashtable<Thread, DirContext>();


    // ----------------------------------------------------- Instance Variables


    /**
     * Directory context.
     */
    protected DirContext context = null;


    // ------------------------------------------------------------- Properties


    // ----------------------------------------------- URLStreamHandler Methods


    /**
     * Opens a connection to the object referenced by the <code>URL</code>
     * argument.
     */
    @Override
    protected URLConnection openConnection(URL u)
        throws IOException {
        DirContext currentContext = this.context;
        if (currentContext == null) {
            currentContext = get();
        }
        return new DirContextURLConnection(currentContext, u);
    }


    // --------------------------------------------------------- Public Methods


    /**
     * Set the java.protocol.handler.pkgs system property.
     */
    public static void setProtocolHandler() {
        String value = System.getProperty(Constants.PROTOCOL_HANDLER_VARIABLE);
        if (value == null) {
            value = Constants.Package;
            setProperty(Constants.PROTOCOL_HANDLER_VARIABLE, value, true);
        } else if (value.indexOf(Constants.Package) == -1) {
            value += "|" + Constants.Package;
            setProperty(Constants.PROTOCOL_HANDLER_VARIABLE, value, true);
        }
    }


    /**
     * Returns true if the thread or the context class loader of the current
     * thread is bound.
     */
    public static boolean isBound() {
        return (clBindings.containsKey
                (Thread.currentThread().getContextClassLoader()))
            || (threadBindings.containsKey(Thread.currentThread()));
    }


    /**
     * Binds a directory context to a class loader.
     */
    public static void bind(DirContext dirContext) {
        ClassLoader currentCL = Thread.currentThread().getContextClassLoader();
        if (currentCL != null) {
            clBindings.put(currentCL, dirContext);
        }
    }


    /**
     * Unbinds a directory context to a class loader.
     */
    public static void unbind() {
        ClassLoader currentCL =
            Thread.currentThread().getContextClassLoader();
        if (currentCL != null) {
            clBindings.remove(currentCL);
        }
    }


    /**
     * Binds a directory context to a thread.
     */
    public static void bindThread(DirContext dirContext) {
        threadBindings.put(Thread.currentThread(), dirContext);
    }


    /**
     * Unbinds a directory context to a thread.
     */
    public static void unbindThread() {
        threadBindings.remove(Thread.currentThread());
    }


    /**
     * Get the bound context.
     */
    public static DirContext get() {

        DirContext result = null;

        Thread currentThread = Thread.currentThread();
        ClassLoader currentCL = currentThread.getContextClassLoader();

        // Checking CL binding
        result = clBindings.get(currentCL);
        if (result != null) {
            return result;
        }

        // Checking thread biding
        result = threadBindings.get(currentThread);

        // Checking parent CL binding
        currentCL = currentCL.getParent();
        while (currentCL != null) {
            result = clBindings.get(currentCL);
            if (result != null) {
                return result;
            }
            currentCL = currentCL.getParent();
        }

        if (result == null) {
            throw new IllegalStateException("Illegal class loader binding");
        }

        return result;

    }


    /**
     * Binds a directory context to a class loader.
     */
    public static void bind(ClassLoader cl, DirContext dirContext) {
        clBindings.put(cl, dirContext);
    }


    /**
     * Unbinds a directory context to a class loader.
     */
    public static void unbind(ClassLoader cl) {
        clBindings.remove(cl);
    }


    /**
     * Get the bound context.
     */
    public static DirContext get(ClassLoader cl) {
        return clBindings.get(cl);
    }


    /**
     * Get the bound context.
     */
    public static DirContext get(Thread thread) {
        return threadBindings.get(thread);
    }


    // START SJSAS 6318494
    /**
     * Converts a <code>URL</code> of a specific protocol to a
     * <code>String</code>.
     *
     * The impl of this method is almost identical to that of the
     * java.net.URLStreamHandler superclass, except that it omits the
     * URL's authority field from the URL's String representation.
     *
     * @param   u   the URL.
     * @return  a string representation of the <code>URL</code> argument.
     */
    @Override
    protected String toExternalForm(URL u) {

        // pre-compute length of StringBuilder
        int len = u.getProtocol().length() + 1;
        if (u.getPath() != null) {
            len += u.getPath().length();
        }
        if (u.getQuery() != null) {
            len += 1 + u.getQuery().length();
        }
        if (u.getRef() != null) {
            len += 1 + u.getRef().length();
        }

        StringBuilder result = new StringBuilder(len);
        result.append(u.getProtocol());
        result.append(":");
        if (u.getPath() != null) {
            result.append(u.getPath());
        }
        if (u.getQuery() != null) {
            result.append('?');
            result.append(u.getQuery());
        }
        if (u.getRef() != null) {
            result.append("#");
            result.append(u.getRef());
        }
        return result.toString();
    }
    // END SJSAS 6318494

}
