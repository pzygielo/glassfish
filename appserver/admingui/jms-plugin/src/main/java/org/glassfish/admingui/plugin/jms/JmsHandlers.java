/*
 * Copyright (c) 2022, 2025 Contributors to the Eclipse Foundation
 * Copyright (c) 2009, 2018 Oracle and/or its affiliates. All rights reserved.
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

package org.glassfish.admingui.plugin.jms;

import com.sun.appserv.connectors.internal.api.ConnectorRuntimeException;
import com.sun.enterprise.config.serverbeans.Cluster;
import com.sun.enterprise.config.serverbeans.Domain;
import com.sun.enterprise.config.serverbeans.Server;
import com.sun.enterprise.connectors.ConnectorRuntime;
import com.sun.enterprise.connectors.jms.system.JmsProviderLifecycle;
import com.sun.jsftemplating.annotation.Handler;
import com.sun.jsftemplating.annotation.HandlerInput;
import com.sun.jsftemplating.annotation.HandlerOutput;
import com.sun.jsftemplating.layout.descriptors.handler.HandlerContext;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.ResourceBundle;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.management.Attribute;
import javax.management.AttributeList;
import javax.management.MBeanServer;
import javax.management.MBeanServerConnection;
import javax.management.ObjectName;

import org.glassfish.admingui.common.util.GuiUtil;
import org.glassfish.api.naming.SimpleJndiName;
import org.glassfish.hk2.api.ServiceLocator;
import org.glassfish.internal.api.ServerContext;
import org.glassfish.jms.admin.cli.JMSDestination;
import org.glassfish.jms.admin.cli.MQJMXConnectorInfo;
import org.glassfish.resourcebase.resources.api.PoolInfo;

/**
 *
 * @author jasonlee
 */
public class JmsHandlers {

    protected static final String OBJECT_DEST_MGR = "com.sun.messaging.jms.server:type=DestinationManager,subtype=Config";
    protected static final String OBJECT_DEST_BASE = "com.sun.messaging.jms.server:type=Destination";
    protected static final String SUBTYPE_CONFIG = "Config";
    protected static final String SUBTYPE_MONITOR = "Monitor";
    protected static final String OP_LIST_DESTINATIONS = "getDestinations";
    protected static final String OP_CREATE = "create";
    protected static final String OP_DESTROY = "destroy";
    protected static final String OP_PURGE = "purge";
    // Config attributes
    protected static final String ATTR_CONSUMER_FLOW_LIMIT = "ConsumerFlowLimit";
    protected static final String ATTR_LIMIT_BEHAVIOR = "LimitBehavior";
    protected static final String ATTR_LOCAL_DELIVERY_PREFERRED = "LocalDeliveryPreferred";
    protected static final String ATTR_MAX_BYTES_PER_MSG = "MaxBytesPerMsg";
    protected static final String ATTR_MAX_NUM_ACTIVE_CONSUMERS = "MaxNumActiveConsumers";
    protected static final String ATTR_MAX_NUM_BACKUP_CONSUMERS = "MaxNumBackupConsumers";
    protected static final String ATTR_MAX_NUM_PRODUCERS = "MaxNumProducers";
    protected static final String ATTR_USE_DMQ = "UseDMQ";
    protected static final String ATTR_MAX_NUM_MSGS = "MaxNumMsgs";
    protected static final String ATTR_MAX_TOTAL_MSG_BYTES = "MaxTotalMsgBytes";
    protected static final String ATTR_VALIDATE_XML_SCHEMA_ENABLED = "ValidateXMLSchemaEnabled";
    protected static final String ATTR_XML_SCHEMA_URI_LIST = "XMLSchemaURIList";
    // Monitoring attributes
    protected static final String ATTR_CREATED_BY_ADMIN = "CreatedByAdmin";
    protected static final String ATTR_TEMPORARY = "Temporary";
    protected static final String ATTR_CONNECTION_ID = "ConnectionID";
    protected static final String ATTR_STATE = "State";
    protected static final String ATTR_STATE_LABEL = "StateLabel";
    protected static final String ATTR_NUM_PRODUCERS = "NumProducers";
    protected static final String ATTR_NUM_CONSUMERS = "NumConsumers";
    protected static final String ATTR_NUM_WILDCARD_PRODUCERS = "NumWildcardProducers";
    protected static final String ATTR_NUM_WILDCARD_CONSUMERS = "NumWildcardConsumers";
    protected static final String ATTR_NUM_WILDCARDS = "NumWildcards";
    protected static final String ATTR_PEAK_NUM_CONSUMERS = "PeakNumConsumers";
    protected static final String ATTR_AVG_NUM_CONSUMERS = "AvgNumConsumers";
    protected static final String ATTR_NUM_ACTIVE_CONSUMERS = "NumActiveConsumers";
    protected static final String ATTR_PEAK_NUM_ACTIVE_CONSUMERS = "PeakNumActiveConsumers";
    protected static final String ATTR_AVG_NUM_ACTIVE_CONSUMERS = "AvgNumActiveConsumers";
    protected static final String ATTR_NUM_BACKUP_CONSUMERS = "NumBackupConsumers";
    protected static final String ATTR_PEAK_NUM_BACKUP_CONSUMERS = "PeakNumBackupConsumers";
    protected static final String ATTR_AVG_NUM_BACKUP_CONSUMERS = "AvgNumBackupConsumers";
    protected static final String ATTR_NUM_MSGS = "NumMsgs";
    protected static final String ATTR_NUM_MSGS_REMOTE = "NumMsgsRemote";
    protected static final String ATTR_NUM_MSGS_PENDING_ACKS = "NumMsgsPendingAcks";
    protected static final String ATTR_NUM_MSGS_HELD_IN_TRANSACTION = "NumMsgsHeldInTransaction";
    protected static final String ATTR_NEXT_MESSAGE_ID = "NextMessageID";
    protected static final String ATTR_PEAK_NUM_MSGS = "PeakNumMsgs";
    protected static final String ATTR_AVG_NUM_MSGS = "AvgNumMsgs";
    protected static final String ATTR_NUM_MSGS_IN = "NumMsgsIn";
    protected static final String ATTR_NUM_MSGS_OUT = "NumMsgsOut";
    protected static final String ATTR_MSG_BYTES_IN = "MsgBytesIn";
    protected static final String ATTR_MSG_BYTES_OUT = "MsgBytesOut";
    protected static final String ATTR_PEAK_MSG_BYTES = "PeakMsgBytes";
    protected static final String ATTR_TOTAL_MSG_BYTES = "TotalMsgBytes";
    protected static final String ATTR_TOTAL_MSG_BYTES_REMOTE = "TotalMsgBytesRemote";
    protected static final String ATTR_TOTAL_MSG_BYTES_HELD_IN_TRANSACTION = "TotalMsgBytesHeldInTransaction";
    protected static final String ATTR_PEAK_TOTAL_MSG_BYTES = "PeakTotalMsgBytes";
    protected static final String ATTR_AVG_TOTAL_MSG_BYTES = "AvgTotalMsgBytes";
    protected static final String ATTR_DISK_RESERVED = "DiskReserved";
    protected static final String ATTR_DISK_USED = "DiskUsed";
    protected static final String ATTR_DISK_UTILIZATION_RATIO = "DiskUtilizationRatio";
    private static final String[] ATTRS_CONFIG = new String[] { ATTR_MAX_NUM_MSGS, ATTR_MAX_BYTES_PER_MSG, ATTR_MAX_TOTAL_MSG_BYTES, ATTR_LIMIT_BEHAVIOR,
            ATTR_MAX_NUM_PRODUCERS, ATTR_MAX_NUM_ACTIVE_CONSUMERS, ATTR_MAX_NUM_BACKUP_CONSUMERS, ATTR_CONSUMER_FLOW_LIMIT,
            ATTR_LOCAL_DELIVERY_PREFERRED, ATTR_USE_DMQ, ATTR_VALIDATE_XML_SCHEMA_ENABLED, ATTR_XML_SCHEMA_URI_LIST };
    private static final String[] ATTRS_MONITOR = new String[] { ATTR_CREATED_BY_ADMIN, ATTR_TEMPORARY, ATTR_CONNECTION_ID, ATTR_STATE, ATTR_STATE_LABEL,
            ATTR_NUM_PRODUCERS, ATTR_NUM_CONSUMERS, ATTR_NUM_WILDCARD_PRODUCERS, ATTR_NUM_WILDCARD_CONSUMERS, ATTR_NUM_WILDCARDS, ATTR_PEAK_NUM_CONSUMERS,
            ATTR_AVG_NUM_CONSUMERS, ATTR_NUM_ACTIVE_CONSUMERS, ATTR_PEAK_NUM_ACTIVE_CONSUMERS, ATTR_AVG_NUM_ACTIVE_CONSUMERS,
            ATTR_NUM_BACKUP_CONSUMERS, ATTR_PEAK_NUM_BACKUP_CONSUMERS, ATTR_AVG_NUM_BACKUP_CONSUMERS, ATTR_NUM_MSGS, ATTR_NUM_MSGS_REMOTE,
            ATTR_NUM_MSGS_PENDING_ACKS, ATTR_NUM_MSGS_HELD_IN_TRANSACTION, ATTR_NEXT_MESSAGE_ID, ATTR_PEAK_NUM_MSGS, ATTR_AVG_NUM_MSGS,
            ATTR_NUM_MSGS_IN, ATTR_NUM_MSGS_OUT, ATTR_MSG_BYTES_IN, ATTR_MSG_BYTES_OUT, ATTR_PEAK_MSG_BYTES, ATTR_TOTAL_MSG_BYTES, ATTR_TOTAL_MSG_BYTES_REMOTE,
            ATTR_TOTAL_MSG_BYTES_HELD_IN_TRANSACTION, ATTR_PEAK_TOTAL_MSG_BYTES, ATTR_AVG_TOTAL_MSG_BYTES, ATTR_DISK_RESERVED, ATTR_DISK_USED,
            ATTR_DISK_UTILIZATION_RATIO };
    protected static final String PROP_NAME = "name";
    protected static final String PROP_DEST_TYPE = "desttype";

    @Handler(id = "getPhysicalDestination",
    input = {
        @HandlerInput(name = "name", type = String.class, required = true),
        @HandlerInput(name = "type", type = String.class, required = true) },
    output = {
        @HandlerOutput(name = "destData", type = java.util.Map.class) })
    public static void getPhysicalDestination(HandlerContext handlerCtx) {
        String name = (String) handlerCtx.getInputValue("name");
        String type = (String) handlerCtx.getInputValue("type");
        Map valueMap = new HashMap();
        try {
            String objectName = getJmsDestinationObjectName(SUBTYPE_CONFIG, name, type);
            AttributeList attributes = JMXUtil.getMBeanServer().getAttributes(new ObjectName(objectName), ATTRS_CONFIG);
            for (Attribute attribute : attributes.asList()) {
                valueMap.put(attribute.getName(), (attribute.getValue() != null) ? attribute.getValue().toString() : null);
            }

            handlerCtx.setOutputValue("destData", valueMap);
        } catch (Exception ex) {
            GuiUtil.handleException(handlerCtx, ex);
        }

        handlerCtx.setOutputValue("destData", valueMap);
    }

    @Handler(id = "getPhysicalDestinationStats",
    input = {
        @HandlerInput(name = "name", type = String.class, required = true),
        @HandlerInput(name = "type", type = String.class, required = true),
        @HandlerInput(name = "target", type = String.class, required = true) },
    output = {
        @HandlerOutput(name = "statsData", type = java.util.List.class) })
    public static void getPhysicalDestinationStats(HandlerContext handlerCtx) {
        String name = (String) handlerCtx.getInputValue("name");
        String type = (String) handlerCtx.getInputValue("type");
        String target = (String) handlerCtx.getInputValue("target");
        List statsList = new ArrayList();
        try {
            insureJmsBrokerIsRunning();

            String objectName = getJmsDestinationObjectName(SUBTYPE_MONITOR, name, type);
            try (MQJMXConnectorInfo mqInfo = getMqJMXInfo(target)) {
                MBeanServerConnection beanServerConnection = mqInfo.getMQMBeanServerConnection();
                AttributeList attributes = beanServerConnection.getAttributes(new ObjectName(objectName), ATTRS_MONITOR);
                ResourceBundle bundle = GuiUtil.getBundle("org.glassfish.jms.admingui.Strings");
                statsList.add(createRow("Name", name, ""));
                statsList.add(createRow("Type", type.substring(0, 1).toUpperCase(GuiUtil.guiLocale) + type.substring(1), ""));
                for (Attribute attribute : attributes.asList()) {
                    statsList.add(
                            createRow(
                            GuiUtil.getMessage(bundle, "jmsPhysDestinations." + attribute.getName()),
                            attribute.getValue(),
                            GuiUtil.getMessage(bundle, "jmsPhysDestinations." + attribute.getName() + "Help")));
                }
            }
        } catch (Exception ex) {
            GuiUtil.handleException(handlerCtx, ex);
        }

        handlerCtx.setOutputValue("statsData", statsList);
    }

    @Handler(id = "getPhysicalDestinations",
    input = {
        @HandlerInput(name = "selectedRows", type = List.class) },
    output = {
        @HandlerOutput(name = "result", type = java.util.List.class) })
    public static void getPhysicalDestinations(HandlerContext handlerCtx) {
        ObjectName[] objectNames = null;
        List result = new ArrayList();
        try {
            insureJmsBrokerIsRunning();

            // com.sun.messaging.jms.server:type=Destination,subtype=Config,desttype=q,name="mq.sys.dmq"
            objectNames = (ObjectName[]) JMXUtil.invoke(OBJECT_DEST_MGR, OP_LIST_DESTINATIONS);

            if (objectNames == null) {
                handlerCtx.setOutputValue("result", result);
                return; // nothing to load..
            }
            List<Map> selectedList = (List) handlerCtx.getInputValue("selectedRows");
            boolean hasOrig = (selectedList == null || selectedList.size() == 0) ? false : true;

            for (ObjectName objectName : objectNames) {
                // getAttributes for the given objectName...
                HashMap oneRow = new HashMap();
                oneRow.put("name", objectName.getKeyProperty(PROP_NAME).replaceAll("\"", ""));
                oneRow.put("type", "t".equals(objectName.getKeyProperty(PROP_DEST_TYPE)) ? "topic" : "queue");
                oneRow.put("selected", (hasOrig) ? isSelected(objectName.getKeyProperty(PROP_NAME), selectedList) : false);
                result.add(oneRow);
            }

        } catch (Exception ex) {
            GuiUtil.handleException(handlerCtx, ex);
        }
        handlerCtx.setOutputValue("result", result);
    }

    /**
     * <p>
     * This handler creates a physical destination.
     * </p>
     *
     * @param context The HandlerContext.
     */
    @Handler(id = "createPhysicalDestination",
    input = {
        @HandlerInput(name = "name", type = String.class, required = true),
        @HandlerInput(name = "attributes", type = Map.class, required = true),
        @HandlerInput(name = "type", type = String.class) })
    public static void createPhysicalDestination(HandlerContext handlerCtx) {
        try {
            final String type = (String) handlerCtx.getInputValue("type");
            final String name = (String) handlerCtx.getInputValue("name");
            AttributeList list = new AttributeList();

            // Copy attributes to the AttributeList.
            // Make it work, then make it right. :|
            Map attrMap = (Map) handlerCtx.getInputValue("attributes");
            buildAttributeList(list, attrMap, type);

            String[] types = new String[] { "java.lang.String", "java.lang.String", "javax.management.AttributeList" };
            Object[] params = new Object[] { type, name, list };

            JMXUtil.invoke(OBJECT_DEST_MGR, OP_CREATE, params, types);
        } catch (Exception ex) {
            GuiUtil.handleException(handlerCtx, ex);
        }
    }

    /**
     * <p>
     * This handler updates a physical destination.
     * </p>
     *
     * @param context The HandlerContext.
     */
    @Handler(id = "updatePhysicalDestination",
    input = {
        @HandlerInput(name = "name", type = String.class, required = true),
        @HandlerInput(name = "attributes", type = Map.class, required = true),
        @HandlerInput(name = "type", type = String.class) })
    public static void updatePhysicalDestination(HandlerContext handlerCtx) {
        try {
            final String type = (String) handlerCtx.getInputValue("type");
            final String name = (String) handlerCtx.getInputValue("name");
            AttributeList list = new AttributeList();

            // Copy attributes to the AttributeList.
            // Make it work, then make it right. :|
            Map attrMap = (Map) handlerCtx.getInputValue("attributes");
            buildAttributeList(list, attrMap, type);

            String objectName = getJmsDestinationObjectName(SUBTYPE_CONFIG, name, type);
            JMXUtil.getMBeanServer().setAttributes(new ObjectName(objectName), list);
        } catch (Exception ex) {
            GuiUtil.handleException(handlerCtx, ex);
        }
    }

    @Handler(id = "deleteJMSDest",
    input = {
        @HandlerInput(name = "selectedRows", type = List.class, required = true) })
    public static void deleteJMSDest(HandlerContext handlerCtx) {
//        String configName = ((String) handlerCtx.getInputValue("targetName"));
        List obj = (List) handlerCtx.getInputValue("selectedRows");
        List<Map> selectedRows = obj;
        try {
            for (Map oneRow : selectedRows) {
                String name = (String) oneRow.get("name");
                String type = ((String) oneRow.get("type")).substring(0, 1).toLowerCase(GuiUtil.guiLocale);
                Object[] params = new Object[] { type, name };
                String[] types = new String[] { "java.lang.String", "java.lang.String" };
                JMXUtil.invoke(OBJECT_DEST_MGR, OP_DESTROY, params, types);
            }
        } catch (Exception ex) {
            GuiUtil.handleException(handlerCtx, ex);
        }
    }

    /**
     * <p>
     * This handler takes in selected rows, and removes selected config
     *
     * @param context The HandlerContext.
     */
    @Handler(id = "flushJMSDestination",
    input = {
        @HandlerInput(name = "selectedRows", type = List.class, required = true) })
    public static void flushJMSDestination(HandlerContext handlerCtx) {
        List<Map> selectedRows = (List) handlerCtx.getInputValue("selectedRows");
        try {
            for (Map oneRow : selectedRows) {
                String name = (String) oneRow.get("name");
                String type = ((String) oneRow.get("type"));
                JMXUtil.invoke(getJmsDestinationObjectName(SUBTYPE_CONFIG, name, type), OP_PURGE);
            }
        } catch (Exception ex) {
            GuiUtil.handleException(handlerCtx, ex);
        }
    }

    /**
     * <p>
     * This handler returns a map populated with the default values for a destination. Currently, this is all hard-coded,
     * based on data from the MQ documentation. When/if they expose an API for determining this programmatically, the
     * implementation will be updated.
     * </p>
     * If an orig map is passed in, the default value will be set to this orig map instead of creating a new one. This is
     * used for restoring back to the default value during edit.
     *
     * @param context The HandlerContext.
     */
    @Handler(id = "getDefaultPhysicalDestinationValues",
    input = {
        @HandlerInput(name = "orig", type = Map.class) },
    output = {
        @HandlerOutput(name = "map", type = Map.class) })
    public static void getDefaultPhysicalDestinationValues(HandlerContext handlerCtx) {
        Map orig = (Map) handlerCtx.getInputValue("orig");
        Map map = new HashMap();
        if (orig != null) {
            map = orig;
        }
        map.put(ATTR_MAX_NUM_MSGS, "-1");
        map.put(ATTR_MAX_BYTES_PER_MSG, "-1");
        map.put(ATTR_MAX_TOTAL_MSG_BYTES, "-1");
        map.put(ATTR_LIMIT_BEHAVIOR, "REJECT_NEWEST");
        map.put(ATTR_MAX_NUM_PRODUCERS, "100");
        map.put(ATTR_MAX_NUM_ACTIVE_CONSUMERS, "-1");
        map.put(ATTR_MAX_NUM_BACKUP_CONSUMERS, "0");
        map.put(ATTR_CONSUMER_FLOW_LIMIT, "1000");
        map.put(ATTR_LOCAL_DELIVERY_PREFERRED, "false");
        map.put(ATTR_USE_DMQ, "true");
        map.put(ATTR_VALIDATE_XML_SCHEMA_ENABLED, "false");
        map.put(ATTR_XML_SCHEMA_URI_LIST, "");

        handlerCtx.setOutputValue("map", map);
    }

    @Handler(id = "pingJms",
    input = {
        @HandlerInput(name = "poolName", type = String.class, required = true) })
    public static void pingJms(HandlerContext handlerCtx) {
        try {
            String poolName = (String) handlerCtx.getInputValue("poolName");
            ConnectorRuntime connectorRuntime = GuiUtil.getHabitat().getService(ConnectorRuntime.class);
            PoolInfo poolInfo = new PoolInfo(SimpleJndiName.of(poolName));
            connectorRuntime.pingConnectionPool(poolInfo);
            GuiUtil.prepareAlert("success", GuiUtil.getMessage("msg.PingSucceed"), null);
        } catch (Exception ex) {
            GuiUtil.prepareAlert("error", GuiUtil.getMessage("msg.Error"), ex.getMessage());
        }
    }

    public static void getDestinations(HandlerContext handlerCtx) {
        // String result = (String)JMXUtil.invoke(JMS_OBJECT_NAME, OP_LIST_DESTINATIONS, null, null);
        MBeanServer mbs = ManagementFactory.getPlatformMBeanServer();
        try {
            mbs.invoke(new ObjectName(OBJECT_DEST_MGR), OP_LIST_DESTINATIONS, new Object[] {}, new String[] {});
        } catch (Exception ex) {
            Logger.getLogger(JmsHandlers.class.getName()).log(Level.SEVERE, null, ex);
        }
    }

    public static boolean isSelected(String name, List<Map> selectedList) {
        if (selectedList == null || name == null) {
            return false;
        }
        for (Map oneRow : selectedList) {
            if (name.equals(oneRow.get("name"))) {
                return true;
            }
        }
        return false;
    }

    protected static String getJmsDestinationObjectName(String objectType, String name, String destType) {
        return OBJECT_DEST_BASE + ",subtype=" + objectType + ",desttype=" + destType.substring(0, 1).toLowerCase(GuiUtil.guiLocale) + ",name=\"" + name + "\"";
    }

    protected static void buildAttributeList(AttributeList list, Map attrMap, String type) {
        list.add(new Attribute(ATTR_MAX_NUM_MSGS, Long.parseLong((String) attrMap.get(ATTR_MAX_NUM_MSGS))));
        list.add(new Attribute(ATTR_MAX_BYTES_PER_MSG, Long.parseLong((String) attrMap.get(ATTR_MAX_BYTES_PER_MSG))));
        list.add(new Attribute(ATTR_MAX_TOTAL_MSG_BYTES, Long.parseLong((String) attrMap.get(ATTR_MAX_TOTAL_MSG_BYTES))));
        list.add(new Attribute(ATTR_LIMIT_BEHAVIOR, attrMap.get(ATTR_LIMIT_BEHAVIOR)));
        list.add(new Attribute(ATTR_MAX_NUM_PRODUCERS, Integer.parseInt((String) attrMap.get(ATTR_MAX_NUM_PRODUCERS))));
        if ("queue".equals(type)) {
            list.add(new Attribute(ATTR_MAX_NUM_ACTIVE_CONSUMERS, Integer.parseInt((String) attrMap.get(ATTR_MAX_NUM_ACTIVE_CONSUMERS))));
            list.add(new Attribute(ATTR_MAX_NUM_BACKUP_CONSUMERS, Integer.parseInt((String) attrMap.get(ATTR_MAX_NUM_BACKUP_CONSUMERS))));
            list.add(new Attribute(ATTR_LOCAL_DELIVERY_PREFERRED, Boolean.valueOf((String) attrMap.get(ATTR_LOCAL_DELIVERY_PREFERRED))));
        }
        list.add(new Attribute(ATTR_CONSUMER_FLOW_LIMIT, Long.parseLong((String) attrMap.get(ATTR_CONSUMER_FLOW_LIMIT))));
        list.add(new Attribute(ATTR_USE_DMQ, Boolean.valueOf((String) attrMap.get(ATTR_USE_DMQ))));
        list.add(new Attribute(ATTR_VALIDATE_XML_SCHEMA_ENABLED, Boolean.valueOf((String) attrMap.get(ATTR_VALIDATE_XML_SCHEMA_ENABLED))));
        list.add(new Attribute(ATTR_XML_SCHEMA_URI_LIST, attrMap.get(ATTR_XML_SCHEMA_URI_LIST)));
    }

    protected static void insureJmsBrokerIsRunning() throws ConnectorRuntimeException {
        // FIXME: This @Service needs to be wrapped in an MBean so that we can have the console out of process
        JmsProviderLifecycle jpl = GuiUtil.getHabitat().getService(JmsProviderLifecycle.class);
        jpl.initializeBroker();
    }

    private static Map createRow(String label, Object value, String helpText) {
        Map map = new HashMap();
        map.put("label", label);
        map.put("value", (value != null) ? value.toString() : null);
        map.put("help", helpText);

        return map;
    }

    private static MQJMXConnectorInfo getMqJMXInfo(String target) throws ConnectorRuntimeException, Exception {
        ServiceLocator habitat = GuiUtil.getHabitat();
        Domain domain = habitat.getService(Domain.class);
        Cluster cluster = domain.getClusterNamed(target);
        String configRef = null;
        if (cluster == null) {
            Server server = domain.getServerNamed(target);
            configRef = server.getConfigRef();
        } else {
            configRef = cluster.getConfigRef();
        }

        PhysicalDestinations pd = new PhysicalDestinations();
        return pd.createConnectorInfo(target, configRef, habitat, domain);
    }

    private static class PhysicalDestinations extends JMSDestination {
        public MQJMXConnectorInfo createConnectorInfo(String target, String configName, ServiceLocator habitat, Domain domain) throws Exception {
            return createMQJMXConnectorInfo(target, domain.getConfigNamed(configName), habitat.<ServerContext>getService(ServerContext.class),
                    domain, habitat.<ConnectorRuntime>getService(ConnectorRuntime.class));
        }
    }
}
