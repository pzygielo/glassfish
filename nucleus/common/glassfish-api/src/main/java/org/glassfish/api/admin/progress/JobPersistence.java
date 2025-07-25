/*
 * Copyright (c) 2025 Contributors to the Eclipse Foundation
 * Copyright (c) 2012, 2018 Oracle and/or its affiliates. All rights reserved.
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

package org.glassfish.api.admin.progress;

import java.io.File;

import org.jvnet.hk2.annotations.Contract;

/**
 * A contract to persist jobs related information to files
 */
@Contract
public interface JobPersistence {

    /**
     * Adds new job to the file data.
     * If it is already containers, throws runtime exception.
     *
     * @param job
     * @param jobsFile
     * @return new root element
     */
    JobInfos add(JobInfo job);

    /**
     * Removes job from the file data.
     * If it is not found, has no effect.
     *
     * @param job
     * @param jobsFile
     * @return new root element
     */
    JobInfos remove(JobInfo job);

    /**
     * @param file input file
     * @return loaded data
     */
    JobInfos load(File file);
}
