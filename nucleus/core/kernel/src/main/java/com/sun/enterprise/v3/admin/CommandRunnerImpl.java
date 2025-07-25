/*
 * Copyright (c) 2021, 2025 Contributors to the Eclipse Foundation.
 * Copyright (c) 2008, 2020 Oracle and/or its affiliates. All rights reserved.
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

package com.sun.enterprise.v3.admin;

import com.sun.enterprise.admin.util.CachedCommandModel;
import com.sun.enterprise.admin.util.ClusterOperationUtil;
import com.sun.enterprise.admin.util.CommandSecurityChecker;
import com.sun.enterprise.admin.util.InstanceStateService;
import com.sun.enterprise.config.serverbeans.Cluster;
import com.sun.enterprise.config.serverbeans.Domain;
import com.sun.enterprise.universal.collections.ManifestUtils;
import com.sun.enterprise.universal.glassfish.AdminCommandResponse;
import com.sun.enterprise.util.LocalStringManagerImpl;
import com.sun.enterprise.util.StringUtils;
import com.sun.enterprise.v3.common.XMLContentActionReporter;

import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.inject.Scope;
import jakarta.inject.Singleton;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorContext;
import jakarta.validation.ValidatorFactory;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.StringReader;
import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Field;
import java.lang.reflect.Type;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.text.MessageFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.locks.Lock;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.security.auth.Subject;

import org.glassfish.api.ActionReport;
import org.glassfish.api.Param;
import org.glassfish.api.admin.AdminCommand;
import org.glassfish.api.admin.AdminCommandContext;
import org.glassfish.api.admin.AdminCommandContextImpl;
import org.glassfish.api.admin.AdminCommandLock;
import org.glassfish.api.admin.AdminCommandLockException;
import org.glassfish.api.admin.AdminCommandLockTimeoutException;
import org.glassfish.api.admin.AdminCommandState;
import org.glassfish.api.admin.ClusterExecutor;
import org.glassfish.api.admin.CommandAspect;
import org.glassfish.api.admin.CommandAspectFacade;
import org.glassfish.api.admin.CommandAspectImpl;
import org.glassfish.api.admin.CommandInvocation;
import org.glassfish.api.admin.CommandModel;
import org.glassfish.api.admin.CommandModelProvider;
import org.glassfish.api.admin.CommandParameters;
import org.glassfish.api.admin.CommandRunner;
import org.glassfish.api.admin.FailurePolicy;
import org.glassfish.api.admin.Job;
import org.glassfish.api.admin.JobManager;
import org.glassfish.api.admin.ParameterMap;
import org.glassfish.api.admin.ProcessEnvironment;
import org.glassfish.api.admin.RuntimeType;
import org.glassfish.api.admin.ServerEnvironment;
import org.glassfish.api.admin.Supplemental;
import org.glassfish.api.admin.SupplementalCommandExecutor;
import org.glassfish.api.admin.SupplementalCommandExecutor.SupplementalCommand;
import org.glassfish.api.admin.WrappedAdminCommand;
import org.glassfish.api.logging.LogHelper;
import org.glassfish.common.util.admin.CommandModelImpl;
import org.glassfish.common.util.admin.ManPageFinder;
import org.glassfish.common.util.admin.MapInjectionResolver;
import org.glassfish.common.util.admin.UnacceptableValueException;
import org.glassfish.config.support.CommandTarget;
import org.glassfish.config.support.GenericCrudCommand;
import org.glassfish.config.support.TargetType;
import org.glassfish.hk2.api.MultiException;
import org.glassfish.hk2.api.ServiceLocator;
import org.glassfish.internal.api.ServerContext;
import org.glassfish.internal.api.UndoableCommand;
import org.glassfish.internal.api.events.CommandInvokedEvent;
import org.glassfish.internal.api.events.InvokeEventService;
import org.glassfish.internal.deployment.DeploymentTargetResolver;
import org.glassfish.kernel.KernelLoggerInfo;
import org.jvnet.hk2.annotations.Service;
import org.jvnet.hk2.component.MultiMap;
import org.jvnet.hk2.config.InjectionManager;
import org.jvnet.hk2.config.InjectionResolver;
import org.jvnet.hk2.config.MessageInterpolatorImpl;
import org.jvnet.hk2.config.UnsatisfiedDependencyException;

import static com.sun.enterprise.util.Utility.isEmpty;
import static java.util.logging.Level.SEVERE;
import static org.glassfish.common.util.Constants.PASSWORD_ATTRIBUTE_NAMES;

/**
 * Encapsulates the logic needed to execute a server-side command (for example,
 * a descendant of AdminCommand) including injection of argument values into the
 * command.
 *
 * @author dochez
 * @author tjquinn
 * @author Bill Shannon
 */
@Service
public class CommandRunnerImpl implements CommandRunner<AdminCommandJob> {

    private static final Logger logger = KernelLoggerInfo.getLogger();

    private static final LocalStringManagerImpl adminStrings = new LocalStringManagerImpl(CommandRunnerImpl.class);

    // FIXME: global variable initialized from instance method!!!
    private static volatile Validator beanValidator;

    // This is used only for backword compatibility with old behavior
    private static final String OLD_PASSWORD_PARAM_PREFIX = "AS_ADMIN_";

    private static final InjectionManager injectionMgr = new InjectionManager();

    @Inject
    private ServiceLocator serviceLocator;

    @Inject
    private ServerContext serverContext;

    @Inject
    private Domain domain;

    @Inject
    private ServerEnvironment serverEnvironment;

    @Inject
    private ProcessEnvironment processEnvironment;

    @Inject
    private InstanceStateService instanceStateService;

    @Inject
    private AdminCommandLock adminLock;

    @Inject
    @Named("SupplementalCommandExecutorImpl")
    private SupplementalCommandExecutor supplementalExecutor;

    @Inject
    private CommandSecurityChecker commandSecurityChecker;

    @Inject
    private InvokeEventService eventService;

    @Inject
    private JobManager<AdminCommandJob> jobManager;

    /**
     * Returns an initialized ActionReport instance for the passed type or
     * null if it cannot be found.
     *
     * @param name action report type name
     * @return uninitialized action report or null
     */
    @Override
    public ActionReport getActionReport(String name) {
        return serviceLocator.getService(ActionReport.class, name);
    }

    /**
     * Returns the command model for a command name.
     *
     * @param commandName command name
     * @return model for this command (list of parameters,etc...),
     *          or null if command is not found
     */
    @Override
    public CommandModel getModel(String commandName) {
        return getModel(null, commandName);
    }

    /**
     * Returns the command model for a command name.
     *
     * @param commandName command name
     * @return model for this command (list of parameters,etc...),
     *          or null if command is not found
     */
    @Override
    public CommandModel getModel(String scope, String commandName) {
        AdminCommand command;
        try {
            String commandServiceName = (scope != null) ? scope + commandName : commandName;
            command = serviceLocator.getService(AdminCommand.class, commandServiceName);
        } catch (MultiException e) {
            LogHelper.log(logger, SEVERE, KernelLoggerInfo.cantInstantiateCommand, e, commandName);
            return null;
        }

        return command == null ? null : getModel(command);
    }

    @Override
    public boolean validateCommandModelETag(AdminCommand command, String eTag) {
        if (command == null) {
            return true; // Everything is ok for non-existing command
        }

        if (isEmpty(eTag)) {
            return false;
        }

        return validateCommandModelETag(getModel(command), eTag);
    }

    @Override
    public boolean validateCommandModelETag(CommandModel model, String eTag) {
        if (model == null) {
            return true; // Non-existing model => it is ok (but weird in fact)
        }

        if (isEmpty(eTag)) {
            return false;
        }

        return eTag.equals(CachedCommandModel.computeETag(model));
    }

    /**
     * Obtain and return the command implementation defined by
     * the passed commandName for the null scope.
     *
     * @param commandName command name as typed by users
     * @param report report used to communicate command status back to the user
     * @return command registered under commandName or null if not found
     */
    @Override
    public AdminCommand getCommand(String commandName, ActionReport report) {
        return getCommand(null, commandName, report);
    }


    private static Class<? extends Annotation> getScope(Class<?> onMe) {
        for (Annotation annotation : onMe.getAnnotations()) {
            if (annotation.annotationType().isAnnotationPresent(Scope.class)) {
                return annotation.annotationType();
            }
        }

        return null;
    }

    /**
     * Obtain and return the command implementation defined by
     * the passed commandName.
     *
     * @param commandName command name as typed by users
     * @param report report used to communicate command status back to the user
     * @return command registered under commandName or null if not found
     */
    @Override
    public AdminCommand getCommand(String scope, String commandName, ActionReport report) {
        AdminCommand command = null;
        String commandServiceName = scope == null ? commandName : scope + commandName;
        try {
            command = serviceLocator.getService(AdminCommand.class, commandServiceName);
        } catch (MultiException e) {
            report.setFailureCause(e);
        }

        if (command == null) {
            String msg;

            if (!ok(commandName)) {
                msg = adminStrings.getLocalString("adapter.command.nocommand",
                        "No command was specified.");
            } else {
                // this means either a non-existent command or
                // an ill-formed command
                if (serviceLocator.getServiceHandle(AdminCommand.class, commandServiceName) == null) {
                    // somehow it's in habitat
                    msg = adminStrings.getLocalString("adapter.command.notfound", "Command {0} not found", commandName);
                } else {
                    msg = adminStrings.getLocalString("adapter.command.notcreated",
                            "Implementation for the command {0} exists in "
                            + "the system, but it has some errors, "
                            + "check server.log for details", commandName);
                }
            }
            report.setMessage(msg);
            report.setActionExitCode(ActionReport.ExitCode.FAILURE);
            KernelLoggerInfo.getLogger().fine(msg);
            return null;
        }

        Class<? extends Annotation> myScope = getScope(command.getClass());
        if (myScope == null) {
            String msg = adminStrings.getLocalString("adapter.command.noscope",
                    "Implementation for the command {0} exists in the "
                    + "system,\nbut it has no @Scoped annotation", commandName);
            report.setMessage(msg);
            report.setActionExitCode(ActionReport.ExitCode.FAILURE);
            KernelLoggerInfo.getLogger().fine(msg);
            command = null;
        } else if (Singleton.class.equals(myScope)) {
            // check that there are no parameters for this command
            CommandModel model = getModel(command);
            if (!model.getParameters().isEmpty()) {
                String msg =
                        adminStrings.getLocalString("adapter.command.hasparams",
                        "Implementation for the command {0} exists in the "
                        + "system,\nbut it's a singleton that also has "
                        + "parameters", commandName);
                report.setMessage(msg);
                report.setActionExitCode(ActionReport.ExitCode.FAILURE);
                KernelLoggerInfo.getLogger().fine(msg);
                command = null;
            }
        }

        return command;
    }

    /**
     * Obtain a new command invocation object.
     * Command invocations can be configured and used
     * to trigger a command execution.
     *
     * @param scope the scope (or name space) for the command
     * @param name name of the requested command to invoke
     * @param report where to place the status of the command execution
     * @param subject the Subject under which to execute the command
     * @param notify  Should notification be enabled
     * @return a new command invocation for that command name
     */
    @Override
    public CommandInvocation<AdminCommandJob> getCommandInvocation(String scope, String name, ActionReport report,
        Subject subject, boolean notify, boolean detach) {
        return new CommandRunnerExecutionContext(scope, name, report, subject, notify, detach, this);
    }

    JobManager<AdminCommandJob> getJobManager() {
        return jobManager;
    }

    private static boolean injectParameters(final CommandModel model, final Object injectionTarget,
        final InjectionResolver<Param> injector, final ActionReport report) {
        if (injectionTarget instanceof GenericCrudCommand) {
            GenericCrudCommand c = GenericCrudCommand.class.cast(injectionTarget);
            c.setInjectionResolver(injector);
        }

        try {
            injectionMgr.inject(injectionTarget, injector);
        } catch (UnsatisfiedDependencyException e) {
            Param param = e.getAnnotation(Param.class);
            CommandModel.ParamModel paramModel = null;
            for (CommandModel.ParamModel pModel : model.getParameters()) {
                if (pModel.getParam().equals(param)) {
                    paramModel = pModel;
                    break;
                }
            }

            String errorMsg;
            final String usage = getUsageText(model);
            if (paramModel != null) {
                String paramName = paramModel.getName();
                String paramDesc = paramModel.getLocalizedDescription();

                if (param.primary()) {
                    errorMsg = adminStrings.getLocalString("commandrunner.operand.required",
                            "Operand required.");
                } else if (param.password()) {
                    errorMsg = adminStrings.getLocalString("adapter.param.missing.passwordfile",
                            "{0} command requires the passwordfile "
                            + "parameter containing {1} entry.",
                            model.getCommandName(), paramName);
                } else if (paramDesc != null) {
                    errorMsg = adminStrings.getLocalString("admin.param.missing",
                            "{0} command requires the {1} parameter ({2})",
                            model.getCommandName(), paramName, paramDesc);

                } else {
                    errorMsg = adminStrings.getLocalString("admin.param.missing.nodesc",
                            "{0} command requires the {1} parameter",
                            model.getCommandName(), paramName);
                }
            } else {
                errorMsg = adminStrings.getLocalString("admin.param.missing.nofound",
                        "Cannot find {1} in {0} command model, file a bug",
                        model.getCommandName(), e.getUnsatisfiedName());
            }
            report.setActionExitCode(ActionReport.ExitCode.FAILURE);
            report.setMessage(errorMsg);
            report.setFailureCause(e);
            ActionReport.MessagePart childPart = report.getTopMessagePart().addChild();
            childPart.setMessage(usage);
            return false;
        }
        catch (MultiException e) {
            // If the cause is UnacceptableValueException -- we want the message
            // from it.  It is wrapped with a less useful Exception.

            Exception exception = null;
            for (Throwable th : e.getErrors()) {
                Throwable cause = th;
                while (cause != null) {
                    if ((cause instanceof UnacceptableValueException) ||
                            (cause instanceof IllegalArgumentException)) {
                        exception = (Exception) th;
                        break;
                    }

                    cause = cause.getCause();
                }
            }

            if (exception == null) {
                // Not an UnacceptableValueException or IllegalArgumentException
                exception = e;
            }

            logger.log(SEVERE, KernelLoggerInfo.invocationException, exception);
            report.setActionExitCode(ActionReport.ExitCode.FAILURE);
            report.setMessage(exception.getMessage());
            report.setFailureCause(exception);
            ActionReport.MessagePart childPart = report.getTopMessagePart().addChild();
            childPart.setMessage(getUsageText(model));
            return false;
        }

        checkAgainstBeanConstraints(injectionTarget, model.getCommandName());
        return true;
    }

    private static synchronized void initBeanValidator() {
        if (beanValidator != null) {
            return;
        }

        ClassLoader cl = System.getSecurityManager() == null ? Thread.currentThread().getContextClassLoader()
            : AccessController.doPrivileged(new PrivilegedAction<ClassLoader>() {

                    @Override
                    public ClassLoader run() {
                        return Thread.currentThread().getContextClassLoader();
                    }
                });
        try {
            Thread.currentThread().setContextClassLoader(org.hibernate.validator.HibernateValidator.class.getClassLoader());
            ValidatorFactory validatorFactory = Validation.buildDefaultValidatorFactory();
            ValidatorContext validatorContext = validatorFactory.usingContext();
            validatorContext.messageInterpolator(new MessageInterpolatorImpl());
            beanValidator = validatorContext.getValidator();
        } finally {
            Thread.currentThread().setContextClassLoader(cl);
        }
    }

    private static void checkAgainstBeanConstraints(Object component, String cname) {
        initBeanValidator();

        Set<ConstraintViolation<Object>> constraintViolations = beanValidator.validate(component);
        if (constraintViolations == null || constraintViolations.isEmpty()) {
            return;
        }
        StringBuilder msg = new StringBuilder(adminStrings.getLocalString("commandrunner.unacceptableBV",
                "Parameters for command {0} violate the following constraints: ",
                cname));
        boolean addc = false;
        String violationMsg = adminStrings.getLocalString("commandrunner.unacceptableBV.reason",
                "on parameter [ {1} ] violation reason [ {0} ]");

        for (ConstraintViolation constraintViolation : constraintViolations) {
            if (addc) {
                msg.append(", ");
            }

            msg.append(MessageFormat.format(violationMsg, constraintViolation.getMessage(), constraintViolation.getPropertyPath()));
            addc = true;
        }

        throw new UnacceptableValueException(msg.toString());
    }

    /**
     * Executes the provided command object.
     *
     * @param model model of the command (used for logging and reporting)
     * @param command the command service to execute
     * @param context the AdminCommandcontext that has the payload and report
     */
    private void doCommand(final CommandModel model, final AdminCommand command,
        final AdminCommandContext context, final CommandRunnerProgressHelper progressHelper) {
        ActionReport report = context.getActionReport();
        report.setActionDescription(model.getCommandName() + " AdminCommand");

        // We need to set context CL to common CL before executing
        // the command. See issue #5596
        final Thread thread = Thread.currentThread();
        final ClassLoader origCL = thread.getContextClassLoader();
        final ClassLoader ccl = serverContext.getCommonClassLoader();

        AdminCommand wrappedCommand = new WrappedAdminCommand(command) {

            @Override
            public void execute(final AdminCommandContext context) {
                try {
                    if (origCL != ccl) {
                        thread.setContextClassLoader(ccl);
                    }
                    /*
                     * Execute the command in the security context of the
                     * previously-authenticated subject.
                     */
                    Subject.doAs(context.getSubject(), new PrivilegedAction<Void>() {

                        @Override
                        public Void run() {
                            command.execute(context);
                            return null;
                        }

                    });
                } finally {
                    if (origCL != ccl) {
                        thread.setContextClassLoader(origCL);
                    }
                }
            }
        };

        // look for other wrappers using CommandAspect annotation
        final AdminCommand otherWrappedCommand = createWrappers(serviceLocator, model, wrappedCommand, report);

        try {
            Subject.doAs(context.getSubject(), new PrivilegedAction<Void>() {

                @Override
                public Void run() {
                    try {
                        if (origCL != ccl) {
                            thread.setContextClassLoader(ccl);
                        }
                        otherWrappedCommand.execute(progressHelper.wrapContext4MainCommand(context));
                        return null;
                    } finally {
                        if (origCL != ccl) {
                            thread.setContextClassLoader(origCL);
                        }
                    }
                }

            });

        } catch (Throwable e) {
            logger.log(SEVERE, KernelLoggerInfo.invocationException, e);
            report.setMessage(e.toString());
            report.setActionExitCode(ActionReport.ExitCode.FAILURE);
            report.setFailureCause(e);
        }
    }

    /**
     * Get the usage-text of the command.
     * Check if <command-name>.usagetext is defined in LocalString.properties.
     * If defined, then use the usagetext from LocalString.properties else
     * generate the usagetext from Param annotations in the command class.
     *
     * @param model command model
     * @return usagetext
     */
    static String getUsageText(CommandModel model) {
        String usage = model.getUsageText();
        if (ok(usage)) {
            StringBuilder usageText = new StringBuilder();
            usageText.append(adminStrings.getLocalString("adapter.usage", "Usage: "));
            usageText.append(usage);
            return usageText.toString();
        }
        return generateUsageText(model);
    }

    /**
     * Generate the usage-text from the annotated Param in the command class.
     *
     * @param model command model
     * @return generated usagetext
     */
    private static String generateUsageText(CommandModel model) {
        StringBuilder usageText = new StringBuilder();
        usageText.append(adminStrings.getLocalString("adapter.usage", "Usage: "));
        usageText.append(model.getCommandName());
        usageText.append(" ");
        StringBuilder operand = new StringBuilder();
        for (CommandModel.ParamModel pModel : model.getParameters()) {
            final Param param = pModel.getParam();
            final String paramName = pModel.getName().toLowerCase(Locale.ENGLISH);
            // skip "hidden" options

            // do not want to display password as an option
            // do not want to display obsolete options
            if (paramName.startsWith("_") || param.password() || param.obsolete()) {
                continue;
            }
            final boolean optional = param.optional();
            final Class<?> ftype = pModel.getType();
            Object fvalue = null;
            String fvalueString = null;
            try {
                fvalue = param.defaultValue();
                if (fvalue != null) {
                    fvalueString = fvalue.toString();
                }
            } catch (Exception e) {
                // just leave it as null...
            }
            // this is a param.
            if (param.primary()) {
                if (optional) {
                    operand.append("[").append(paramName).append("] ");
                } else {
                    operand.append(paramName).append(" ");
                }
                continue;
            }

            if (optional) {
                usageText.append("[");
            }

            usageText.append("--").append(paramName);
            if (ok(param.defaultValue())) {
                usageText.append("=").append(param.defaultValue());
            } else if (ftype.isAssignableFrom(String.class)) {
                // check if there is a default value assigned
                if (ok(fvalueString)) {
                    usageText.append("=").append(fvalueString);
                } else {
                    usageText.append("=").append(paramName);
                }
            } else if (ftype.isAssignableFrom(Boolean.class)) {
                // note: There is no defaultValue for this param.  It might
                // hava  value -- but we don't care -- it isn't an official
                // default value.
                usageText.append("=").append("true|false");
            } else {
                usageText.append("=").append(paramName);
            }

            if (optional) {
                usageText.append("] ");
            } else {
                usageText.append(" ");
            }
        }
        usageText.append(operand);
        return usageText.toString();
    }

    @Override
    public BufferedReader getHelp(CommandModel model) throws CommandNotFoundException {
        BufferedReader manPage = getManPage(model.getCommandName(), model);
        if (manPage != null) {
            return manPage;
        }

        StringBuilder hlp = new StringBuilder(256);
        StringBuilder part = new StringBuilder(64);
        hlp.append("NAME").append(ManifestUtils.EOL);
        part.append(model.getCommandName());
        String description = model.getLocalizedDescription();
        if (ok(description)) {
            part.append(" - ").append(model.getLocalizedDescription());
        }

        hlp.append(formatGeneratedManPagePart(part.toString(), 5, 65)).append(ManifestUtils.EOL);

        // Usage
        hlp.append(ManifestUtils.EOL).append("SYNOPSIS").append(ManifestUtils.EOL);
        hlp.append(formatGeneratedManPagePart(getUsageText(model), 5, 65));

        // Options
        hlp.append(ManifestUtils.EOL).append(ManifestUtils.EOL);
        hlp.append("OPTIONS").append(ManifestUtils.EOL);

        CommandModel.ParamModel operand = null;
        for (CommandModel.ParamModel paramModel : model.getParameters()) {
            Param param = paramModel.getParam();
            if (param == null || paramModel.getName().startsWith("_") ||
                    param.password() || param.obsolete()) {
                continue;
            }
            if (param.primary()) {
                operand = paramModel;
                continue;
            }
            hlp.append("     --").append(paramModel.getName().toLowerCase(Locale.ENGLISH));
            hlp.append(ManifestUtils.EOL);
            if (ok(param.shortName())) {
                hlp.append("      -").append(param.shortName().toLowerCase(Locale.ENGLISH));
                hlp.append(ManifestUtils.EOL);
            }
            String descr = paramModel.getLocalizedDescription();
            if (ok(descr)) {
                hlp.append(formatGeneratedManPagePart(descr, 9, 65));
            }
            hlp.append(ManifestUtils.EOL);
        }

        // Operand
        if (operand != null) {
            hlp.append("OPERANDS").append(ManifestUtils.EOL);
            hlp.append("     ").append(operand.getName().toLowerCase(Locale.ENGLISH));
            hlp.append(ManifestUtils.EOL);
            String descr = operand.getLocalizedDescription();
            if (ok(descr)) {
                hlp.append(formatGeneratedManPagePart(descr, 9, 65));
            }
        }

        return new BufferedReader(new StringReader(hlp.toString()));

    }

    private String formatGeneratedManPagePart(String part, int prefix, int lineLength) {
        if (part == null) {
            return null;
        }

        if (prefix < 0) {
            prefix = 0;
        }

        //Prepare prefix
        StringBuilder sb = new StringBuilder(prefix);
        for (int i = 0; i < prefix; i++) {
            sb.append(' ');
        }
        String prfx = sb.toString();
        StringBuilder result = new StringBuilder(part.length() + prefix + 16);
        boolean newLine = true;
        boolean lastWasCR = false;
        int counter = 0;
        for (int i = 0; i < part.length(); i++) {
            boolean addPrefix = newLine;
            char ch = part.charAt(i);
            switch (ch) {
                case '\n':
                    if (!lastWasCR) {
                        newLine = true;
                    } else {
                        lastWasCR = false;
                    }
                    counter = 0;
                    break;
                case '\r':
                    newLine = true;
                    lastWasCR = true;
                    counter = 0;
                    break;
                default:
                    newLine = false;
                    lastWasCR = false;
                    counter++;
            }
            if (addPrefix && !newLine) {
                result.append(prfx);
                counter += prefix;
            }
            result.append(ch);
            if (lineLength > 0 && counter >= lineLength && !newLine) {
                newLine = true;
                result.append(ManifestUtils.EOL);
                counter = 0;
            }
        }

        return result.toString();
    }

    public void getHelp(AdminCommand command, ActionReport report) {
        CommandModel model = getModel(command);
        report.setActionDescription(model.getCommandName() + " help");

        // XXX - this is a hack for now.  if the request mapped to an
        // XMLContentActionReporter, that means we want the command metadata.
        if (report instanceof XMLContentActionReporter) {
            getMetadata(command, model, report);
        } else {
            report.setMessage(model.getCommandName() + " - " + model.getLocalizedDescription());
            report.getTopMessagePart().addProperty("SYNOPSIS",
                    encodeManPage(new BufferedReader(new StringReader(
                    getUsageText(model)))));
            for (CommandModel.ParamModel param : model.getParameters()) {
                addParamUsage(report, param);
            }
            report.setActionExitCode(ActionReport.ExitCode.SUCCESS);
        }
    }

    /**
     * Return the metadata for the command.  We translate the parameter
     * and operand information to parts and properties of the ActionReport,
     * which will be translated to XML elements and attributes by the
     * XMLContentActionReporter.
     *
     * @param command the command
     * @param model the CommandModel describing the command
     * @param report        the (assumed to be) XMLContentActionReporter
     */
    private void getMetadata(AdminCommand command, CommandModel model, ActionReport report) {
        ActionReport.MessagePart top = report.getTopMessagePart();
        ActionReport.MessagePart cmd = top.addChild();

        // <command name="name">
        cmd.setChildrenType("command");
        cmd.addProperty("name", model.getCommandName());
        if (model.unknownOptionsAreOperands()) {
            cmd.addProperty("unknown-options-are-operands", "true");
        }
        String usage = model.getUsageText();
        if (ok(usage)) {
            cmd.addProperty("usage", usage);
        }
        CommandModel.ParamModel primary = null;

        // for each parameter add
        // <option name="name" type="type" short="s" default="default"
        //   acceptable-values="list"/>
        for (CommandModel.ParamModel p : model.getParameters()) {
            Param param = p.getParam();
            if (param.primary()) {
                primary = p;
                continue;
            }
            ActionReport.MessagePart ppart = cmd.addChild();
            ppart.setChildrenType("option");
            ppart.addProperty("name", p.getName());
            ppart.addProperty("type", typeOf(p));
            ppart.addProperty("optional", Boolean.toString(param.optional()));
            if (param.obsolete()) {
                // don't include it if it's false
                ppart.addProperty("obsolete", "true");
            }
            String paramDesc = p.getLocalizedDescription();
            if (ok(paramDesc)) {
                ppart.addProperty("description", paramDesc);
            }
            if (ok(param.shortName())) {
                ppart.addProperty("short", param.shortName());
            }
            if (ok(param.defaultValue())) {
                ppart.addProperty("default", param.defaultValue());
            }
            if (ok(param.acceptableValues())) {
                ppart.addProperty("acceptable-values", param.acceptableValues());
            }
            if (ok(param.alias())) {
                ppart.addProperty("alias", param.alias());
            }
        }

        // are operands allowed?
        if (primary != null) {
            // for the operand(s), add
            // <operand type="type" min="0/1" max="1"/>
            ActionReport.MessagePart primpart = cmd.addChild();
            primpart.setChildrenType("operand");
            primpart.addProperty("name", primary.getName());
            primpart.addProperty("type", typeOf(primary));
            primpart.addProperty("min", primary.getParam().optional() ? "0" : "1");
            primpart.addProperty("max", primary.getParam().multiple() ? "unlimited" : "1");
            String desc = primary.getLocalizedDescription();
            if (ok(desc)) {
                primpart.addProperty("description", desc);
            }
        }
    }

    /**
     * Map a Java type to one of the types supported by the asadmin client.
     * Currently supported types are BOOLEAN, FILE, PROPERTIES, PASSWORD, and
     * STRING.  (All of which should be defined constants on some class.)
     *
     * @param p the Java type
     * @return        the string representation of the asadmin type
     */
    private static String typeOf(CommandModel.ParamModel p) {
        Class<?> t = p.getType();
        if (t == Boolean.class || t == boolean.class) {
            return "BOOLEAN";
        } else if (t == File.class || t == File[].class) {
            return "FILE";
        } else if (t == Properties.class) { // XXX - allow subclass?
            return "PROPERTIES";
        } else if (p.getParam().password()) {
            return "PASSWORD";
        } else {
            return "STRING";
        }
    }

    /**
     * Return an InputStream for the man page for the named command.
     */
    private static BufferedReader getManPage(String commandName, CommandModel model) {
        Class<?> clazz = model.getCommandClass();
        if (clazz == null) {
            return null;
        }
        return ManPageFinder.getCommandManPage(commandName, clazz.getName(),
                Locale.getDefault(), clazz.getClassLoader(), logger);
    }

    private void addParamUsage(ActionReport report, CommandModel.ParamModel model) {
        Param param = model.getParam();
        if (param != null) {
            // this is a param.
            String paramName = model.getName().toLowerCase(Locale.ENGLISH);
            // skip "hidden" options

            // do not want to display password in the usage
            // do not want to display obsolete options
            if (paramName.startsWith("_") || param.password() || param.obsolete()) {
                return;
            }
            if (param.primary()) {
                // if primary then it's an operand
                report.getTopMessagePart().addProperty(paramName + "_operand",
                        model.getLocalizedDescription());
            } else {
                report.getTopMessagePart().addProperty(paramName,
                        model.getLocalizedDescription());
            }
        }
    }

    private static boolean ok(String s) {
        return s != null && s.length() > 0;
    }

    /**
     * Validate the parameters with the Param annotation.  If parameter is
     * not defined as a Param annotation then it's an invalid option.
     * If parameter's key is "DEFAULT" then it's a operand.
     *
     * @param model command model
     * @param parameters parameters from URL
     *
     */
    static void validateParameters(final CommandModel model, final ParameterMap parameters) throws MultiException {

        ParameterMap adds = null; // renamed password parameters

        // loop through parameters and make sure they are
        // part of the Param declared field
        for (Map.Entry<String, List<String>> entry : parameters.entrySet()) {
            String key = entry.getKey();

            // to do, we should validate meta-options differently.
            // help and Xhelp are meta-options that are handled specially
            if (key.equals("DEFAULT") || key.equals("help") || key.equals("Xhelp")
                || key.equals("notify") || key.equals("detach")) {
                continue;
            }

            if (key.startsWith(OLD_PASSWORD_PARAM_PREFIX)) {
                // This is an old prefixed password parameter being passed in.
                // Strip the prefix and lowercase the name
                key = key.substring(OLD_PASSWORD_PARAM_PREFIX.length()).toLowerCase(Locale.ENGLISH);
                if (adds == null) {
                    adds = new ParameterMap();
                }
                adds.add(key, entry.getValue().get(0));
            }

            // check if key is a valid Param Field
            boolean validOption = false;
            // loop through the Param field in the command class
            // if either field name or the param name is equal to
            // key then it's a valid option
            for (CommandModel.ParamModel pModel : model.getParameters()) {
                validOption = pModel.isParamId(key);
                if (validOption) {
                    break;
                }
            }

            if (!validOption) {
                throw new MultiException(new IllegalArgumentException(" Invalid option: " + key));
            }
        }

        parameters.mergeAll(adds);
    }

    /**
     * Check if the variable, "skipParamValidation" is defined in the command
     * class.  If defined and set to true, then parameter validation will be
     * skipped from that command.
     * This is used mostly for command referencing.  For example the
     * list-applications command references list-components command and you
     * don't want to define the same params from the class that implements
     * list-components.
     *
     * @param command - AdminCommand class
     * @return true if to skip param validation, else return false.
     */
    static boolean skipValidation(AdminCommand command) {
        try {
            final Field f =
                    command.getClass().getDeclaredField("skipParamValidation");
            AccessController.doPrivileged(new PrivilegedAction<Object>() {

                @Override
                public Object run() {
                    f.setAccessible(true);
                    return null;
                }
            });
            if (f.getType().isAssignableFrom(boolean.class)) {
                return f.getBoolean(command);
            }
        } catch (NoSuchFieldException e) {
            return false;
        } catch (IllegalAccessException e) {
            return false;
        }
        //all else return false
        return false;
    }

    private static String encodeManPage(BufferedReader br) {
        if (br == null) {
            return null;
        }

        try {
            String line;
            StringBuilder sb = new StringBuilder();

            while ((line = br.readLine()) != null) {
                sb.append(line);
                sb.append(ManifestUtils.EOL_TOKEN);
            }
            return sb.toString();
        } catch (Exception ex) {
            return null;
        } finally {
            try {
                br.close();
            } catch (IOException ioex) {
            }
        }
    }

    private static CommandModel getModel(AdminCommand command) {

        if (command instanceof CommandModelProvider) {
            return ((CommandModelProvider) command).getModel();
        }
        return new CommandModelImpl(command.getClass());
    }

    /**
     * Called from ExecutionContext.execute.
     */
    @Override
    public void doCommand(CommandInvocation<AdminCommandJob> ctx, AdminCommand command, final Subject subject,
        final AdminCommandJob job) {
        publishCommandInvokedEvent(ctx, subject);

        boolean fromCheckpoint = job != null &&
                (job.getState() == AdminCommandState.State.REVERTING ||
                job.getState() == AdminCommandState.State.FAILED_RETRYABLE);
        CommandModel model;
        try {
            CommandModelProvider c = CommandModelProvider.class.cast(command);
            model = c.getModel();
        } catch (ClassCastException e) {
            model = new CommandModelImpl(command.getClass());
        }
        UploadedFilesManager ufm = null;
        ActionReport report = ctx.report();
        if (!fromCheckpoint) {
            report.setActionDescription(model.getCommandName() + " command");
            report.setActionExitCode(ActionReport.ExitCode.SUCCESS);
        }
        ParameterMap parameters;
        final AdminCommandContext context = new AdminCommandContextImpl(
                logger, report, ctx.inboundPayload(), ctx.outboundPayload(),
                job.getEventBroker(),
                job.getId());
        context.setSubject(subject);
        List<RuntimeType> runtimeTypes = new ArrayList<>();
        FailurePolicy fp = null;
        Set<CommandTarget> targetTypesAllowed = new HashSet<>();
        ActionReport.ExitCode preSupplementalReturn = ActionReport.ExitCode.SUCCESS;
        ActionReport.ExitCode postSupplementalReturn = ActionReport.ExitCode.SUCCESS;
        CommandRunnerProgressHelper progressHelper =
            new CommandRunnerProgressHelper(command, model.getCommandName(), job, ctx.progressStatus());

        // If this glassfish installation does not have stand alone instances / clusters at all, then
        // lets not even look Supplemental command and such. A small optimization
        boolean doReplication = false;
        if (domain.getServers().getServer().size() > 1 || !domain.getClusters().getCluster().isEmpty()) {
            doReplication = true;
        } else {
            logger.fine(adminStrings.getLocalString("dynamicreconfiguration.diagnostics.devmode",
                    "The GlassFish environment does not have any clusters or instances present; Replication is turned off"));
        }
        try {
            //Get list of suplemental commands
            Collection<SupplementalCommand> suplementalCommands = supplementalExecutor
                .listSuplementalCommands(model.getCommandName());
            try {
                /*
                 * Extract any uploaded files and build a map from parameter names
                 * to the corresponding extracted, uploaded file.
                 */
                ufm = new UploadedFilesManager(ctx.report(), ctx.inboundPayload(), domain.getApplicationRoot());

                if (ctx.typedParams() != null) {
                    logger.fine(adminStrings.getLocalString("dynamicreconfiguration.diagnostics.delegatedcommand",
                            "This command is a delegated command. Dynamic reconfiguration will be bypassed"));
                    InjectionResolver<Param> injectionTarget = new DelegatedInjectionResolver(model, ctx.typedParams(),
                        ufm.optionNameToFileMap());
                    if (injectParameters(model, command, injectionTarget, report)) {
                        doCommand(model, command, context, progressHelper);
                    }
                    return;
                }

                parameters = ctx.parameters();
                if (isSet(parameters, "help") || isSet(parameters, "Xhelp")) {
                    BufferedReader in = getManPage(model.getCommandName(), model);
                    String manPage = encodeManPage(in);

                    if (manPage != null && isSet(parameters, "help")) {
                        ctx.report().getTopMessagePart().addProperty("MANPAGE", manPage);
                    } else {
                        report.getTopMessagePart().addProperty(AdminCommandResponse.GENERATED_HELP, "true");
                        getHelp(command, report);
                    }
                    return;
                }

                try {
                    if (!fromCheckpoint && !skipValidation(command)) {
                        validateParameters(model, parameters);
                    }
                } catch (MultiException e) {
                    // If the cause is UnacceptableValueException -- we want the message
                    // from it.  It is wrapped with a less useful Exception.

                    Exception exception = e;
                    for (Throwable cause : e.getErrors()) {
                        if (cause != null
                                && (cause instanceof UnacceptableValueException)) {
                            // throw away the wrapper.
                            exception = (Exception) cause;
                            break;
                        }

                    }

                    logger.log(SEVERE, KernelLoggerInfo.invocationException, exception);
                    report.setActionExitCode(ActionReport.ExitCode.FAILURE);
                    report.setMessage(exception.getMessage());
                    report.setFailureCause(exception);
                    ActionReport.MessagePart childPart = report.getTopMessagePart().addChild();
                    childPart.setMessage(getUsageText(model));
                    return;
                }

                // initialize the injector and inject
                MapInjectionResolver injectionMgr = new MapInjectionResolver(model, parameters,
                    ufm.optionNameToFileMap());
                injectionMgr.setContext(context);
                if (!injectParameters(model, command, injectionMgr, report)) {
                    return;
                }

                init(serviceLocator, command, context, job);

                /*
                 * Now that parameters have been injected into the command object,
                 * decide if the current Subject should be permitted to execute
                 * the command.  We need to wait until after injection is done
                 * because the class might implement its own authorization check
                 * and that logic might need the injected values.
                 */
                final Map<String,Object> env = buildEnvMap(parameters);
                try {
                    if (!commandSecurityChecker.authorize(context.getSubject(), env, command, context)) {
                        /*
                         * If the command class tried to prepare itself but
                         * could not then the return is false and the command has
                         * set the action report accordingly.  Don't process
                         * the command further and leave the action report alone.
                         */
                        return;
                    }
                } catch (SecurityException ex) {
                    report.setFailureCause(ex);
                    report.setActionExitCode(ActionReport.ExitCode.FAILURE);
                    report.setMessage(
                        adminStrings.getLocalString("commandrunner.noauth", "User is not authorized for this command"));
                    return;
                } catch (Exception ex) {
                    report.setFailureCause(ex);
                    report.setActionExitCode(ActionReport.ExitCode.FAILURE);
                    report
                        .setMessage(adminStrings.getLocalString("commandrunner.errAuth", "Error during authorization"));
                    return;
                }


                logger.fine(adminStrings.getLocalString("dynamicreconfiguration.diagnostics.injectiondone",
                        "Parameter mapping, validation, injection completed successfully; Starting paramater injection"));

                // Read cluster annotation attributes
                org.glassfish.api.admin.ExecuteOn clAnnotation = model.getClusteringAttributes();
                if (clAnnotation == null) {
                    runtimeTypes.add(RuntimeType.DAS);
                    runtimeTypes.add(RuntimeType.INSTANCE);
                    fp = FailurePolicy.Error;
                } else {
                    if (clAnnotation.value().length == 0) {
                        runtimeTypes.add(RuntimeType.DAS);
                        runtimeTypes.add(RuntimeType.INSTANCE);
                    } else {
                        runtimeTypes.addAll(Arrays.asList(clAnnotation.value()));
                    }
                    if (clAnnotation.ifFailure() == null) {
                        fp = FailurePolicy.Error;
                    } else {
                        fp = clAnnotation.ifFailure();
                    }
                }
                TargetType tgtTypeAnnotation = command.getClass().getAnnotation(TargetType.class);

                //@ExecuteOn(RuntimeType.SINGLE_INSTANCE) cannot be combined with
                //@TargetType since we do not want to replicate the command
                if (runtimeTypes.contains(RuntimeType.SINGLE_INSTANCE)) {
                   if (tgtTypeAnnotation != null) {

                       report.setActionExitCode(ActionReport.ExitCode.FAILURE);
                       report.setMessage(adminStrings.getLocalString("commandrunner.executor.targettype.unallowed",
                               "Target type is not allowed on single instance command {0}  ,"
                                       , model.getCommandName()));
                       return;
                   }
                   //Do not replicate the command when there is
                   //@ExecuteOn(RuntimeType.SINGLE_INSTANCE)
                   doReplication = false;
                }

                String targetName = parameters.getOne("target");
                if (targetName == null || model.getModelFor("target").getParam().obsolete()) {
                    if (command instanceof DeploymentTargetResolver) {
                        targetName = ((DeploymentTargetResolver) command).getTarget(parameters);
                    } else {
                        targetName = "server";
                    }
                }

                logger.fine(adminStrings.getLocalString("dynamicreconfiguration.diagnostics.target",
                        "@ExecuteOn parsing and default settings done; Current target is {0}", targetName));

                if (serverEnvironment.isDas()) {
                    // Check if the command allows this target type; first read the annotation
                    //TODO : See is @TargetType can also be moved to the CommandModel

                    if (tgtTypeAnnotation != null) {
                        targetTypesAllowed.addAll(Arrays.asList(tgtTypeAnnotation.value()));
                    }

                    // If not @TargetType, default it
                    if (targetTypesAllowed.isEmpty()) {
                        targetTypesAllowed.add(CommandTarget.DAS);
                        targetTypesAllowed.add(CommandTarget.STANDALONE_INSTANCE);
                        targetTypesAllowed.add(CommandTarget.CLUSTER);
                        targetTypesAllowed.add(CommandTarget.CONFIG);
                    }

                    // If the target is "server" and the command is not marked for DAS,
                    // add DAS to RuntimeTypes; This is important because those class of CLIs that
                    // do not always have to be run on DAS followed by applicable instances
                    // will have @ExecuteOn(RuntimeType.INSTANCE) and they have to be run on DAS
                    // ONLY if the target is "server"
                    if (CommandTarget.DAS.isValid(serviceLocator, targetName) && !runtimeTypes.contains(RuntimeType.DAS)) {
                        runtimeTypes.add(RuntimeType.DAS);
                    }

                    logger.fine(adminStrings.getLocalString("dynamicreconfiguration.diagnostics.runtimeTypes",
                            "RuntimeTypes are: {0}", runtimeTypes.toString()));
                    logger.fine(adminStrings.getLocalString("dynamicreconfiguration,diagnostics.targetTypes",
                            "TargetTypes are: {0}", targetTypesAllowed.toString()));

                    // Check if the target is valid
                    //Is there a server or a cluster or a config with given name ?
                    if ((!CommandTarget.DOMAIN.isValid(serviceLocator, targetName))
                            && (domain.getServerNamed(targetName) == null)
                            && (domain.getClusterNamed(targetName) == null)
                            && (domain.getConfigNamed(targetName) == null)) {
                        report.setActionExitCode(ActionReport.ExitCode.FAILURE);
                        report.setMessage(adminStrings.getLocalString("commandrunner.executor.invalidtarget",
                                "Unable to find a valid target with name {0}", targetName));
                        return;
                    }

                    // Does this command allow this target type
                    boolean isTargetValidType = false;
                    Iterator<CommandTarget> it = targetTypesAllowed.iterator();
                    while (it.hasNext()) {
                        if (it.next().isValid(serviceLocator, targetName)) {
                            isTargetValidType = true;
                            break;
                        }
                    }
                    if (!isTargetValidType) {
                        StringBuilder validTypes = new StringBuilder();
                        it = targetTypesAllowed.iterator();
                        while (it.hasNext()) {
                            validTypes.append(it.next().getDescription()).append(", ");
                        }
                        report.setActionExitCode(ActionReport.ExitCode.FAILURE);
                        report.setMessage(adminStrings.getLocalString("commandrunner.executor.invalidtargettype",
                                "Target {0} is not a supported type. Command {1} supports these types of targets only : {2}",
                                targetName, model.getCommandName(), validTypes.toString()));
                        return;
                    }

                    // If target is a clustered instance and the allowed types does not allow operations on clustered
                    // instance, return error
                    if ((CommandTarget.CLUSTERED_INSTANCE.isValid(serviceLocator, targetName))
                            && (!targetTypesAllowed.contains(CommandTarget.CLUSTERED_INSTANCE))) {
                        Cluster c = domain.getClusterForInstance(targetName);
                        report.setActionExitCode(ActionReport.ExitCode.FAILURE);
                        report.setMessage(adminStrings.getLocalString("commandrunner.executor.instanceopnotallowed",
                                "The {0} command is not allowed on instance {1} because it is part of cluster {2}",
                                model.getCommandName(), targetName, c.getName()));
                        return;
                    }
                    logger.fine(adminStrings.getLocalString("dynamicreconfiguration.diagnostics.replicationvalidationdone",
                            "All @ExecuteOn attribute and type validation completed successfully. Starting replication stages"));
                }

                /**
                 * We're finally ready to actually execute the command instance.
                 * Acquire the appropriate lock.
                 */
                Lock lock = null;
                boolean lockTimedOut = false;
                try {
                    // XXX: The owner of the lock should not be hardcoded.  The
                    //      value is not used yet.
                    lock = adminLock.getLock(command, "asadmin");

                    //Set there progress statuses
                    if (!fromCheckpoint) {
                        for (SupplementalCommand supplementalCommand : suplementalCommands) {
                            progressHelper.addProgressStatusToSupplementalCommand(supplementalCommand);
                        }
                    }

                    // If command is undoable, then invoke prepare method
                    if (command instanceof UndoableCommand) {
                        UndoableCommand uCmd = (UndoableCommand) command;
                        logger.fine(adminStrings.getLocalString("dynamicreconfiguration.diagnostics.prepareunodable",
                            "Command execution stage 1 : Calling prepare for undoable command {0}",
                            ctx.getCommandName()));
                        if (!uCmd.prepare(context, parameters).equals(ActionReport.ExitCode.SUCCESS)) {
                            report.setActionExitCode(ActionReport.ExitCode.FAILURE);
                            report.setMessage(adminStrings.getLocalString("commandrunner.executor.errorinprepare",
                                    "The command {0} cannot be completed because the preparation for the command failed "
                                    + "indicating potential issues : {1}", model.getCommandName(), report.getMessage()));
                            return;
                        }
                    }

                    ClusterOperationUtil.clearInstanceList();

                    // Run Supplemental commands that have to run before this command on this instance type
                    if (!fromCheckpoint) {
                        logger.fine(adminStrings.getLocalString("dynamicreconfiguration.diagnostics.presupplemental",
                            "Command execution stage 2 : Call pre supplemental commands for {0}",
                            ctx.getCommandName()));
                        preSupplementalReturn = supplementalExecutor.execute(suplementalCommands,
                                Supplemental.Timing.Before, context, parameters, ufm.optionNameToFileMap());
                        if (preSupplementalReturn.equals(ActionReport.ExitCode.FAILURE)) {
                            report.setActionExitCode(preSupplementalReturn);
                            if (!StringUtils.ok(report.getTopMessagePart().getMessage())) {
                                report.setMessage(adminStrings.getLocalString("commandrunner.executor.supplementalcmdfailed",
                                    "A supplemental command failed; cannot proceed further"));
                            }
                            return;
                        }
                    }

                    //Run main command if it is applicable for this instance type
                    if ((runtimeTypes.contains(RuntimeType.ALL))
                            || (serverEnvironment.isDas() &&
                                (CommandTarget.DOMAIN.isValid(serviceLocator, targetName) || runtimeTypes.contains(RuntimeType.DAS)))
                            || runtimeTypes.contains(RuntimeType.SINGLE_INSTANCE)
                            || (serverEnvironment.isInstance() && runtimeTypes.contains(RuntimeType.INSTANCE))) {
                        logger.fine(adminStrings.getLocalString("dynamicreconfiguration.diagnostics.maincommand",
                            "Command execution stage 3 : Calling main command implementation for {0}",
                            ctx.getCommandName()));
                        doCommand(model, command, context, progressHelper);
                    }



                    if (!FailurePolicy.applyFailurePolicy(fp,
                            report.getActionExitCode()).equals(ActionReport.ExitCode.FAILURE)) {
                        //Run Supplemental commands that have to be run after this command on this instance type
                        logger.fine(adminStrings.getLocalString("dynamicreconfiguration.diagnostics.postsupplemental",
                            "Command execution stage 4 : Call post supplemental commands for {0}",
                            ctx.getCommandName()));
                        postSupplementalReturn = supplementalExecutor.execute(suplementalCommands,
                                Supplemental.Timing.After, context, parameters, ufm.optionNameToFileMap());
                        if (postSupplementalReturn.equals(ActionReport.ExitCode.FAILURE)) {
                            report.setActionExitCode(postSupplementalReturn);
                            report.setMessage(adminStrings.getLocalString("commandrunner.executor.supplementalcmdfailed",
                                    "A supplemental command failed; cannot proceed further"));
                            return;
                        }
                    }
                } catch (AdminCommandLockTimeoutException ex) {
                    lockTimedOut = true;
                    String lockTime = formatSuspendDate(ex.getTimeOfAcquisition());
                    String msg = adminStrings.getLocalString("lock.timeout",
                            "Command timed out.  Unable to acquire a lock to access "
                            + "the domain.  Another command acquired exclusive access "
                            + "to the domain on {0}.  Retry the command at a later "
                            + "time.", lockTime);
                    report.setMessage(msg);
                    report.setActionExitCode(ActionReport.ExitCode.FAILURE);
                } catch (AdminCommandLockException ex) {
                    lockTimedOut = true;
                    String lockTime = formatSuspendDate(ex.getTimeOfAcquisition());
                    String lockMsg = ex.getMessage();
                    String msg = adminStrings.getLocalString("lock.notacquired",
                            "The command was blocked.  The domain was suspended by "
                            + "a user on {0}.", lockTime);

                    if (lockMsg != null && !lockMsg.isEmpty()) {
                        msg += " " + adminStrings.getLocalString("lock.reason", "Reason:") + " " + lockMsg;
                    }

                    report.setMessage(msg);
                    report.setActionExitCode(ActionReport.ExitCode.FAILURE);
                } finally {
                    // command is done, release the lock
                    if (lock != null && !lockTimedOut) {
                        lock.unlock();
                    }
                }

            } catch (Exception ex) {
                logger.log(SEVERE, KernelLoggerInfo.invocationException, ex);
                report.setActionExitCode(ActionReport.ExitCode.FAILURE);
                report.setMessage(ex.getMessage());
                report.setFailureCause(ex);
                ActionReport.MessagePart childPart = report.getTopMessagePart().addChild();
                childPart.setMessage(getUsageText(model));
                return;
            }
            /*
             * Command execution completed; If this is DAS and the command succeeded,
             * time to replicate; At this point we will get the appropriate ClusterExecutor
             * and give it complete control; We will let the executor take care all considerations
             * (like FailurePolicy settings etc)
             * and just give the final execution results which we will set as is in the Final
             * Action report returned to the caller.
             */

            if (processEnvironment.getProcessType().isEmbedded()) {
                return;
            }
            if (preSupplementalReturn == ActionReport.ExitCode.WARNING
                    || postSupplementalReturn == ActionReport.ExitCode.WARNING) {
                report.setActionExitCode(ActionReport.ExitCode.WARNING);
            }
            if (doReplication
                    && (!FailurePolicy.applyFailurePolicy(fp, report.getActionExitCode()).equals(ActionReport.ExitCode.FAILURE))
                    && (serverEnvironment.isDas())
                    && (runtimeTypes.contains(RuntimeType.INSTANCE) || runtimeTypes.contains(RuntimeType.ALL))) {
                logger.fine(adminStrings.getLocalString("dynamicreconfiguration.diagnostics.startreplication",
                        "Command execution stages completed on DAS; Starting replication on remote instances"));
                ClusterExecutor executor = null;
                // This try-catch block is a fix for 13838
                try {
                    if (model.getClusteringAttributes() != null && model.getClusteringAttributes().executor() != null) {
                        executor = serviceLocator.getService(model.getClusteringAttributes().executor());
                    } else {
                        executor = serviceLocator.getService(ClusterExecutor.class, "GlassFishClusterExecutor");
                    }
                } catch (UnsatisfiedDependencyException usdepex) {
                    logger.log(Level.WARNING, KernelLoggerInfo.cantGetClusterExecutor, usdepex);
                }
                if (executor != null) {
                    report.setActionExitCode(executor.execute(model.getCommandName(), command, context, parameters));
                    if (report.getActionExitCode().equals(ActionReport.ExitCode.FAILURE)) {
                        report.setMessage(adminStrings.getLocalString("commandrunner.executor.errorwhilereplication",
                                "An error occurred during replication"));
                    } else {
                        if (!FailurePolicy.applyFailurePolicy(fp,
                                report.getActionExitCode()).equals(ActionReport.ExitCode.FAILURE)) {
                            logger.fine(
                                adminStrings.getLocalString("dynamicreconfiguration.diagnostics.afterreplsupplemental",
                                    "Command execution stage 5 : Call post-replication supplemental commands for {0}",
                                    ctx.getCommandName()));
                            ActionReport.ExitCode afterReplicationSupplementalReturn = supplementalExecutor.execute(suplementalCommands,
                                    Supplemental.Timing.AfterReplication, context, parameters, ufm.optionNameToFileMap());
                            if (afterReplicationSupplementalReturn.equals(ActionReport.ExitCode.FAILURE)) {
                                report.setActionExitCode(afterReplicationSupplementalReturn);
                                report.setMessage(adminStrings.getLocalString("commandrunner.executor.supplementalcmdfailed",
                                        "A supplemental command failed; cannot proceed further"));
                                return;
                            }
                        }
                    }
                }
            }
            if (report.getActionExitCode().equals(ActionReport.ExitCode.FAILURE)) {
                // If command is undoable, then invoke undo method method
                if (command instanceof UndoableCommand) {
                    UndoableCommand uCmd = (UndoableCommand) command;
                    logger.fine(adminStrings.getLocalString("dynamicreconfiguration.diagnostics.undo",
                            "Command execution failed; calling undo() for command {0}", ctx.getCommandName()));
                    uCmd.undo(context, parameters, ClusterOperationUtil.getCompletedInstances());
                }
            } else {
                //TODO : Is there a better way of doing this ? Got to look into it
                if ("_register-instance".equals(model.getCommandName())) {
                    instanceStateService.addServerToStateService(parameters.getOne("DEFAULT"));
                }
                if ("_unregister-instance".equals(model.getCommandName())) {
                    instanceStateService.removeInstanceFromStateService(parameters.getOne("DEFAULT"));
                }
            }
        } finally {
            if (ufm != null) {
                ufm.close();
            }
        }
    }

    private Map<String,Object> buildEnvMap(final ParameterMap params) {
        final Map<String,Object> result = new HashMap<>();
        for (Map.Entry<String,List<String>> entry : params.entrySet()) {
            final List<String> values = entry.getValue();
            if (values != null && values.size() > 0) {
                result.put(entry.getKey(), values.get(0));
            }
        }
        return result;
    }


    private void publishCommandInvokedEvent(CommandInvocation<?> invocation, Subject subject) {
        final ParameterMap parameters = invocation.parameters().getMaskedMap(PASSWORD_ATTRIBUTE_NAMES);
        final CommandInvokedEvent event = new CommandInvokedEvent(invocation.getCommandName(), parameters, subject);
        eventService.getCommandInvokedTopic().publish(event);
    }


    /**
     * An InjectionResolver that uses an Object as the source of the data to inject.
     */
    private static class DelegatedInjectionResolver extends InjectionResolver<Param> {

        private final CommandModel model;
        private final CommandParameters parameters;
        private final MultiMap<String, File> optionNameToUploadedFileMap;

        public DelegatedInjectionResolver(CommandModel model, CommandParameters parameters,
            final MultiMap<String, File> optionNameToUploadedFileMap) {
            super(Param.class);
            this.model = model;
            this.parameters = parameters;
            this.optionNameToUploadedFileMap = optionNameToUploadedFileMap;

        }

        @Override
        public boolean isOptional(AnnotatedElement element, Param annotation) {
            String name = CommandModel.getParamName(annotation, element);
            CommandModel.ParamModel param = model.getModelFor(name);
            return param.getParam().optional();
        }

        @Override
        public <V> V getValue(Object component, AnnotatedElement target, Type genericType, Class<V> type) {

            // look for the name in the list of parameters passed.
            if (target instanceof Field) {
                final Field targetField = (Field) target;
                try {
                    Field sourceField =
                            parameters.getClass().getField(targetField.getName());
                    AccessController.doPrivileged(new PrivilegedAction<Object>() {

                        @Override
                        public Object run() {
                            targetField.setAccessible(true);
                            return null;
                        }
                    });
                    Object paramValue = sourceField.get(parameters);

                    /*
                     * If this field is a File, then replace the param value
                     * (which is whatever the client supplied on the command) with
                     * the actual absolute path(s) of the uploaded and extracted
                     * file(s) if, in fact, the file(s) was (were) uploaded.
                     */

                    final List<String> paramFileValues =
                            MapInjectionResolver.getUploadedFileParamValues(
                            targetField.getName(),
                            targetField.getType(),
                            optionNameToUploadedFileMap);
                    if (!paramFileValues.isEmpty()) {
                        V fileValue = (V) MapInjectionResolver.convertListToObject(target, type, paramFileValues);
                        return fileValue;
                    }
                    /*
                    if (paramValue==null) {
                    return convertStringToObject(target, type,
                    param.defaultValue());
                    }
                     */
                    // XXX temp fix, to revisit
                    if (paramValue != null) {
                        checkAgainstAcceptableValues(target,
                                paramValue.toString());
                    }
                    return type.cast(paramValue);
                } catch (IllegalAccessException e) {
                } catch (NoSuchFieldException e) {
                }
            }
            return null;
        }

        private static void checkAgainstAcceptableValues(
                AnnotatedElement target, String paramValueStr) {
            Param param = target.getAnnotation(Param.class);
            String acceptable = param.acceptableValues();
            String paramName = CommandModel.getParamName(param, target);

            if (ok(acceptable) && ok(paramValueStr)) {
                String[] ss = acceptable.split(",");

                for (String s : ss) {
                    if (paramValueStr.equals(s.trim())) {
                        return;         // matched, value is good
                    }
                }

                // didn't match any, error
                throw new UnacceptableValueException(
                        adminStrings.getLocalString(
                        "adapter.command.unacceptableValue",
                        "Invalid parameter: {0}.  Its value is {1} "
                        + "but it isn''t one of these acceptable values: {2}",
                        paramName,
                        paramValueStr,
                        acceptable));
            }
        }
    }

    /**
     * Is the boolean valued parameter specified?
     * If so, and it has a value, is the value "true"?
     */
    private static boolean isSet(ParameterMap params, String name) {
        String val = params.getOne(name);
        if (val == null) {
            return false;
        }
        return val.length() == 0 || Boolean.valueOf(val).booleanValue();
    }

    /** Works as a key in ETag cache map
     */
    private static class NameCommandClassPair {
        private final String name;
        private final Class<? extends AdminCommand> clazz;
        private int hash; //immutable, we can cache it

        public NameCommandClassPair(String name, Class<? extends AdminCommand> clazz) {
            this.name = name;
            this.clazz = clazz;
            hash = 3;
            hash = 67 * hash + (this.name != null ? this.name.hashCode() : 0);
            hash = 67 * hash + (this.clazz != null ? this.clazz.hashCode() : 0);
        }

        @Override
        public boolean equals(Object obj) {
            if ((obj == null) || (getClass() != obj.getClass())) {
                return false;
            }
            final NameCommandClassPair other = (NameCommandClassPair) obj;
            if (this.clazz != other.clazz) {
                return false;
            }
            if ((this.name == null) ? (other.name != null) : !this.name.equals(other.name)) {
                return false;
            }
            return true;
        }

        @Override
        public int hashCode() {
            return hash;
        }
    }

    /**
     * Format the lock acquisition time.
     */
    private String formatSuspendDate(Date lockTime) {
        if (lockTime != null) {
            String DATE_FORMAT = "EEE, dd MMM yyyy HH:mm:ss z";
            SimpleDateFormat sdf = new SimpleDateFormat(DATE_FORMAT);
            return sdf.format(lockTime);
        }
        return adminStrings.getLocalString("lock.timeoutunavailable", "<<Date is unavailable>>");
    }

    /**
     * Execute aspects when command is just completely initialized, i..e injected with parameters.
     */
    private static void init(final ServiceLocator serviceLocator, final AdminCommand command,
        final AdminCommandContext context, final Job instance) {
        processAspects(serviceLocator, command, (a, aspect, command1) -> {
            aspect.init(a, command1, context, instance);
            return command1;
        });
    }

    /**
     * Execute aspects when command is finished successfully or not.
     */
    void done(final AdminCommand command, final Job instance, boolean isNotify) {

        processAspects(serviceLocator, command, (a, aspect, command1) -> {
            aspect.done(a, command1, instance);
            return command1;
        });
        if (isNotify) {
            CommandAspectFacade commandAspectFacade = serviceLocator.getService(CommandAspectFacade.class);
            if (commandAspectFacade != null) {
                commandAspectFacade.done(command, instance);
            }
        }
    }

    /**
     * Execute wrapping aspects, see {@link org.glassfish.api.AsyncImpl} for example.
     */
    public static AdminCommand createWrappers(final ServiceLocator serviceLocator, final CommandModel model, final AdminCommand command,
            final ActionReport report) {

        return processAspects(serviceLocator, command, (a, cai, command1) -> cai.createWrapper(a, model, command1, report));
    }

    private static AdminCommand processAspects(ServiceLocator serviceLocator, AdminCommand command, Function function) {

        Annotation[] annotations = getUnwrappedCommand(command).getClass().getAnnotations();
        // TODO: annotations from wrapper class
        for (Annotation a : annotations) {
            CommandAspect ca = a.annotationType().getAnnotation(CommandAspect.class);
            if (ca != null) {
                CommandAspectImpl<Annotation> cai = serviceLocator.<CommandAspectImpl<Annotation>>getService(ca.value());
                command = function.apply(a, cai, command);
            }
        }

        return command;
    }

    // Get root of wrapped command.
    private static AdminCommand getUnwrappedCommand(AdminCommand wrappedCommand) {
        if (wrappedCommand instanceof WrappedAdminCommand) {
            return ((WrappedAdminCommand) wrappedCommand).getWrappedCommand();
        }
        return wrappedCommand;
    }

    private interface Function {
        AdminCommand apply(Annotation ca, CommandAspectImpl<Annotation> cai, AdminCommand object);
    }
}
