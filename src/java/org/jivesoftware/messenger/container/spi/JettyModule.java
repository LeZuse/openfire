/**
 * $RCSfile$
 * $Revision$
 * $Date$
 *
 * Copyright (C) 2004 Jive Software. All rights reserved.
 *
 * This software is published under the terms of the GNU Public License (GPL),
 * a copy of which is included in this distribution.
 */

package org.jivesoftware.messenger.container.spi;

import org.jivesoftware.messenger.container.*;
import org.jivesoftware.messenger.JiveGlobals;
import org.jivesoftware.util.LocaleUtils;
import org.jivesoftware.util.Log;
import java.io.File;
import org.mortbay.jetty.Server;
import org.mortbay.jetty.servlet.WebApplicationContext;
import org.mortbay.log.*;

/**
 * A simple wrapper that allows Jetty to run inside the Messenger
 * container. Jetty settings are extracted from the ModuleContext.
 * The Jetty module is primarily designed to host the JSP web
 * administration interface to the server when running in standalone
 * mode without an external servlet container.
 *
 * @author Iain Shigeoka
 */
public class JettyModule implements Module {

    private Server jetty = null;
    private WebApplicationContext webAppContext = null;
    private Container serverContainer = null;
    private ServiceRegistration reg = null;
    private String port = null;

    /**
     * Create a jetty module.
     */
    public JettyModule() {
    }

    public String getName() {
        return "Admin Console";
    }

    public void initialize(Container container) {
        try {
            // Configure logging to a file, creating log dir if needed
//            File logDir = new File(JiveGlobals.getMessengerHome(), "logs");
//            if (!logDir.exists()) {
//                logDir.mkdirs();
//            }
//            File logFile = new File(logDir, "admin_console.log");
//            OutputStreamLogSink logSink = new OutputStreamLogSink(logFile.toString());
//            logSink.start();
//            LogImpl log = (LogImpl)Factory.getFactory().getInstance("");
//            log.reset();
//            log.add(logSink);

            jetty = new Server();

            // Configure HTTP socket listener
            port = JiveGlobals.getProperty("embedded-web.port", "9090");
            jetty.addListener(port);
            this.serverContainer = container;

            // Add web-app
            // TODO this shouldn't be hardcoded to look for the "admin" plugin.
            webAppContext = jetty.addWebApplication("/", JiveGlobals.getMessengerHome() +
                    File.separator + "plugins" + File.separator +  "admin" +
                    File.separator + "webapp");
            webAppContext.setWelcomeFiles(new String[]{"index.jsp"});
        }
        catch (Exception e) {
            Log.error("Trouble initializing Jetty", e);
        }
    }

    public void start() {
        try {
            jetty.start();

            ServiceItem serverItem = new ServiceItem(null, this, null);
            reg = serverContainer.getServiceLookup().register(serverItem);
            Log.info("Started embedded web server on port: " + port);
        }
        catch (Exception e) {
            Log.error("Trouble starting Jetty", e);
            stop();
        }
    }

    public void stop() {
        try {
            if (jetty != null) {
                jetty.stop();
                jetty = null;
            }
            if (reg != null) {
                reg.cancel();
                reg = null;
            }
        }
        catch (InterruptedException e) {
            Log.error(LocaleUtils.getLocalizedString("admin.error"), e);
        }
    }

    public void destroy() {
    }
}