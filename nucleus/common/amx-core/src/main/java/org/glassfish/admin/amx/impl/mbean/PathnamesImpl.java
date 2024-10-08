/*
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

package org.glassfish.admin.amx.impl.mbean;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.logging.Level;

import javax.management.InstanceNotFoundException;
import javax.management.MBeanInfo;
import javax.management.MBeanServer;
import javax.management.ObjectName;

import org.glassfish.admin.amx.base.DomainRoot;
import org.glassfish.admin.amx.base.Pathnames;
import org.glassfish.admin.amx.core.AMXProxy;
import org.glassfish.admin.amx.core.PathnameParser;
import org.glassfish.admin.amx.core.Util;
import org.glassfish.admin.amx.core.proxy.AMXProxyHandler;
import org.glassfish.admin.amx.util.AMXLoggerInfo;
import org.glassfish.admin.amx.util.CollectionUtil;
import org.glassfish.admin.amx.util.ExceptionUtil;
import org.glassfish.admin.amx.util.ListUtil;
import org.glassfish.admin.amx.util.jmx.JMXUtil;
import org.glassfish.admin.amx.util.stringifier.SmartStringifier;

import static org.glassfish.external.amx.AMX.ATTR_PARENT;
import static org.glassfish.external.amx.AMX.PARENT_PATH_KEY;

/**
GlassFish V3 dotted names implementation (MBean).
 */
public final class PathnamesImpl extends AMXImplBase // implements Pathnames  (can't directly implement the interface)
{

    private static final int MAX_CACHE_SIZE = 1024;
    private final ConcurrentMap<String, ObjectName> mPathnameCache = new ConcurrentHashMap<String, ObjectName>();

    public PathnamesImpl(final ObjectName parentObjectName) {
        super(parentObjectName, Pathnames.class);
    }

    public ObjectName resolvePath(final String path) {
        ObjectName result = mPathnameCache.get(path);
        if (result != null) {
            return result;
        }

        if (path.equals(DomainRoot.PATH)) {
            return getDomainRoot();
        }

        final PathnameParser parser = new PathnameParser(path);

        final String parentPath = parser.parentPath();

        //cdebug( "resolvePath: " + parser.toString() + ", parentPath = " + parentPath );

        // fixed query based on the path, which will find all MBeans with that parent path
        final String props = Util.makeProp(PARENT_PATH_KEY, Util.quoteIfNeeded(parentPath));
        final ObjectName pattern = JMXUtil.newObjectNamePattern(getObjectName().getDomain(), props);
        final Set<ObjectName> s = getMBeanServer().queryNames(pattern, null);

        //cdebug( "resolvePath: " + path + " = query for parent path: " + pattern + " yields children: " + s.size() );

        ObjectName objectName = null;
        final String type = parser.type();
        final String name = parser.name();
        // find the matching child
        for (final ObjectName child : s) {
            if (type.equals(Util.getTypeProp(child))) {
                final String nameProp = Util.getNameProp(child);

                //cdebug( "type match for " + path + ", objectName = " + child);

                if (nameProp == null) {
                    if (name == null) {
                        // no name, we matched on type alone
                        objectName = child;
                        break;
                    }
                    // badly formed: a name is specified, but none is present for this type
                    //cdebug( "A name is specified in path, but the type has none: path = " + path + ", objectName = " + child);
                    continue;
                }

                // there is a name in the ObjectName (nameProp)
                // the nameProp could exist, but the item might be a singleton
                if (name != null && name.equals(nameProp)) {
                    objectName = child;
                    break;
                }

                // careful: name should be used only if it's not a singleton
                final MBeanInfo mbeanInfo = getProxyFactory().getMBeanInfo(child);
                if (mbeanInfo == null) {
                    continue;
                }

                final boolean singleton = AMXProxyHandler.singleton(mbeanInfo);
                // match only if it's a singleton with no name; the check above handled the other cases
                if (singleton && name == null) {
                    objectName = child;
                    break;
                }
            } else {
                //cdebug( "No match on type: " + type + " != " + Util.getTypeProp(child) );
            }
        }

        // limit the memory use; non-existent paths could otherwise build up
        if (mPathnameCache.keySet().size() > MAX_CACHE_SIZE) {
            // clears out old stuff we might not need anyway
            mPathnameCache.clear();
        }

        if (objectName != null) {
            //cdebug( "Matched " + path + " to " + objectName);
            mPathnameCache.put(path, objectName);
        }

        return objectName;
    }

    private AMXProxy resolveToProxy(final String path) {
        final ObjectName objectName = resolvePath(path);
        if (objectName == null) {
            return null;
        }

        return getProxyFactory().getProxy(objectName, AMXProxy.class);
    }

    public ObjectName[] resolvePaths(final String[] paths) {
        final ObjectName[] objectNames = new ObjectName[paths.length];

        int i = 0;
        for (final String path : paths) {
            try {
                objectNames[i] = resolvePath(path);
            } catch (final Exception e) {
                objectNames[i] = null;
            }
            ++i;
        }
        return objectNames;
    }

    public ObjectName[] ancestors(final String path) {
        final ObjectName objectName = resolvePath(path);
        if (objectName == null) {
            return null;
        }
        return ancestors(objectName);
    }

    public ObjectName[] ancestors(final ObjectName mbean) {
        final List<ObjectName> objectNames = new ArrayList<ObjectName>();
        final MBeanServer server = getMBeanServer();

        ObjectName cur = mbean;
        while (cur != null) {
            objectNames.add(cur);
            cur = (ObjectName) JMXUtil.getAttribute(server, cur, ATTR_PARENT);
        }

        final List<ObjectName> reversed = ListUtil.reverse(objectNames);
        final ObjectName[] ancestors = new ObjectName[reversed.size()];
        reversed.toArray(ancestors);
        return ancestors;
    }

    private boolean isInstanceNotFound(final Throwable t) {
        return ExceptionUtil.getRootCause(t) instanceof InstanceNotFoundException;
    }

    private void listChildren(final AMXProxy top, final List<AMXProxy> list, boolean recursive) {
        Set<AMXProxy> children = null;
        try {
            children = top.childrenSet();
            if (children == null) {
                return;
            }
        } catch (final Exception e) {
            if (!isInstanceNotFound(e)) {
                AMXLoggerInfo.getLogger().log(Level.WARNING,
                        AMXLoggerInfo.cantGetChildren,
                        new Object[] {top.objectName(), ExceptionUtil.getRootCause(e).getLocalizedMessage()});
                // just return, nothing we can do.  Typically it could be InstanceNotFoundException
            }
            return;
        }

        for (final AMXProxy child : children) {
            try {
                list.add(child);
                if (recursive) {
                    listChildren(child, list, true);
                }
            } catch (final Exception e) {
                if (!isInstanceNotFound(e)) {
                    AMXLoggerInfo.getLogger().log(Level.WARNING, AMXLoggerInfo.problemWithMbean,
                            new Object[] { child.objectName(), ExceptionUtil.getRootCause(e).getLocalizedMessage()});
                    // nothing we can do.
                }
            }
        }
    }

    private List<AMXProxy> listChildren(final String path, boolean recursive) {
        final AMXProxy topProxy = resolveToProxy(path);
        if (topProxy == null) {
            return null;
        }

        final List<AMXProxy> list = new ArrayList<AMXProxy>();
        listChildren(topProxy, list, recursive);

        return list;
    }

    public String[] getAllPathnames() {
        try {
            final String[] allButRoot = listPaths(DomainRoot.PATH, true);

            final String[] all = new String[allButRoot.length + 1];
            all[0] = DomainRoot.PATH;
            System.arraycopy(allButRoot, 0, all, 1, allButRoot.length);

            return all;
        } catch (final Throwable t) {
            AMXLoggerInfo.getLogger().log(Level.WARNING, AMXLoggerInfo.unexpectedThrowable,
                    ExceptionUtil.getRootCause(t).getLocalizedMessage());
        }
        return new String[]{DomainRoot.PATH};
    }

    public ObjectName[] listObjectNames(final String path, final boolean recursive) {
        final List<AMXProxy> list = listChildren(path, recursive);
        final List<ObjectName> objectNames = Util.toObjectNameList(list);
        return CollectionUtil.toArray(objectNames, ObjectName.class);
    }

    public String[] listPaths(final String path, final boolean recursive) {
        final List<AMXProxy> list = listChildren(path, recursive);

        final List<String> paths = new ArrayList<String>();
        for (final AMXProxy amx : list) {
            try {
                paths.add(amx.path());
            } catch (final Exception e) {
                AMXLoggerInfo.getLogger().log(Level.WARNING, AMXLoggerInfo.cantGetPath,
                        new Object[]{amx.objectName(), e.getLocalizedMessage()});
            }
        }

        return CollectionUtil.toArray(paths, String.class);
    }

    public String dump(final String path) {
        final ObjectName top = resolvePath(path);
        if (top == null) {
            return null;
        }

        final AMXProxy topProxy = getProxyFactory().getProxy(top, AMXProxy.class);
        final List<AMXProxy> list = new ArrayList<AMXProxy>();
        list.add(topProxy);
        listChildren(topProxy, list, true);

        final String NL = "\n";
        final StringBuffer buf = new StringBuffer();
        for (final AMXProxy amx : list) {
            final String p = amx.path();
            buf.append(p);
            buf.append(NL);

            final Map<String, Object> attributesMap = amx.attributesMap();
            for (final Map.Entry<String,Object> e : attributesMap.entrySet()) {
                buf.append("\t");
                buf.append(e.getKey());
                buf.append(" = ");
                buf.append("").append(SmartStringifier.toString(e.getValue()));
                buf.append(NL);
            }
            buf.append(NL);
        }
        return buf.toString();
    }
}

















