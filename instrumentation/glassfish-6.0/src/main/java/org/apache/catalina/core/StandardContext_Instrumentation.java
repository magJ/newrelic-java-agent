/*
 *
 *  * Copyright 2022 New Relic Corporation. All rights reserved.
 *  * SPDX-License-Identifier: Apache-2.0
 *
 */

package org.apache.catalina.core;

import java.util.EventListener;
import java.util.logging.Level;

import com.newrelic.api.agent.NewRelic;
import com.newrelic.api.agent.weaver.Weave;
import com.newrelic.api.agent.weaver.Weaver;
import com.nr.agent.instrumentation.glassfish6.GlassfishServletRequestListener;

@Weave(originalName = "org.apache.catalina.core.StandardContext")
public abstract class StandardContext_Instrumentation {

    protected void contextListenerStart() {

        try {
            addListener(new GlassfishServletRequestListener());
            NewRelic.getAgent().getLogger().log(Level.FINER, "Registered ServletRequestListener for {0} : {1}",
                    this.getClass(), getPath());
        } catch (Exception e) {
            NewRelic.getAgent().getLogger().log(Level.FINEST, e, "Error registering ServletRequestListener for {0}",
                    this.getClass());
        }

        Weaver.callOriginal();

    }

    public abstract String getPath();

    public abstract void addListener(EventListener listener);
}
