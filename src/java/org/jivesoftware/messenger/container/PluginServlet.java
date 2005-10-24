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

package org.jivesoftware.messenger.container;

import org.apache.jasper.JasperException;
import org.apache.jasper.JspC;
import org.dom4j.Document;
import org.dom4j.Element;
import org.dom4j.io.SAXReader;
import org.jivesoftware.util.JiveGlobals;
import org.jivesoftware.util.Log;
import org.jivesoftware.util.StringUtils;
import org.xml.sax.SAXException;

import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The plugin servlet acts as a proxy for web requests (in the admin console)
 * to plugins. Since plugins can be dynamically loaded and live in a different place
 * than normal Jive Messenger admin console files, it's not possible to have them
 * added to the normal Jive Messenger admin console web app directory.<p>
 * <p/>
 * The servlet listens for requests in the form <tt>/plugins/[pluginName]/[JSP File]</tt>
 * (e.g. <tt>/plugins/foo/example.jsp</tt>). It also listens for image requests in the
 * the form <tt>/plugins/[pluginName]/images/*.png|gif</tt> (e.g.
 * <tt>/plugins/foo/images/example.gif</tt>).<p>
 * <p/>
 * JSP files must be compiled and available via the plugin's class loader. The mapping
 * between JSP name and servlet class files is defined in [pluginName]/web/web.xml.
 * Typically, this file is auto-generated by the JSP compiler when packaging the plugin.
 * Alternatively, if development mode is enabled for the plugin then the the JSP file
 * will be dynamically compiled using JSPC.
 *
 * @author Matt Tucker
 */
public class PluginServlet extends HttpServlet {

    private static Map<String, HttpServlet> servlets;
    private static PluginManager pluginManager;
    private static ServletConfig servletConfig;

    static {
        servlets = new ConcurrentHashMap<String, HttpServlet>();
    }

    public void init(ServletConfig config) throws ServletException {
        super.init(config);
        servletConfig = config;
    }

    public void service(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        String pathInfo = request.getPathInfo();
        if (pathInfo == null) {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        else {
            try {
                // Handle JSP requests.
                if (pathInfo.endsWith(".jsp")) {
                    if (handleDevJSP(pathInfo, request, response)) {
                        return;
                    }
                    handleJSP(pathInfo, request, response);
                    return;
                }
                // Handle servlet requests.
                else if (getServlet(pathInfo) != null) {
                    handleServlet(pathInfo, request, response);
                }
                // Handle image requests.
                else {
                    handleOtherRequest(pathInfo, response);
                    return;
                }

                // Anything else results in a 404.

            }
            catch (Exception e) {
                Log.error(e);
                response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
                return;
            }
        }
    }

    /**
     * Registers all JSP page servlets for a plugin.
     *
     * @param manager the plugin manager.
     * @param plugin  the plugin.
     * @param webXML  the web.xml file containing JSP page names to servlet class file
     *                mappings.
     */
    public static void registerServlets(PluginManager manager, Plugin plugin, File webXML) {
        pluginManager = manager;
        if (!webXML.exists()) {
            Log.error("Could not register plugin servlets, file " + webXML.getAbsolutePath() +
                    " does not exist.");
            return;
        }
        // Find the name of the plugin directory given that the webXML file
        // lives in plugins/[pluginName]/web/web.xml
        String pluginName = webXML.getParentFile().getParentFile().getParentFile().getName();
        try {
            // Make the reader non-validating so that it doesn't try to resolve external
            // DTD's. Trying to resolve external DTD's can break on some firewall configurations.
            SAXReader saxReader = new SAXReader(false);
            try {
                saxReader.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd",
                        false);
            }
            catch (SAXException e) {
                Log.warn("Error setting SAXReader feature", e);
            }
            Document doc = saxReader.read(webXML);
            // Find all <servlet> entries to discover name to class mapping.
            List classes = doc.selectNodes("//servlet");
            Map<String, Class> classMap = new HashMap<String, Class>();
            for (int i = 0; i < classes.size(); i++) {
                Element servletElement = (Element)classes.get(i);
                String name = servletElement.element("servlet-name").getTextTrim();
                String className = servletElement.element("servlet-class").getTextTrim();
                classMap.put(name, manager.loadClass(plugin, className));
            }
            // Find all <servelt-mapping> entries to discover name to URL mapping.
            List names = doc.selectNodes("//servlet-mapping");
            for (int i = 0; i < names.size(); i++) {
                Element nameElement = (Element)names.get(i);
                String name = nameElement.element("servlet-name").getTextTrim();
                String url = nameElement.element("url-pattern").getTextTrim();
                // Register the servlet for the URL.
                Class servletClass = classMap.get(name);
                Object instance = servletClass.newInstance();
                if (instance instanceof HttpServlet) {
                    // Initialize the servlet then add it to the map..
                    ((HttpServlet)instance).init(servletConfig);
                    servlets.put(pluginName + url, (HttpServlet)instance);
                }
                else {
                    Log.warn("Could not load " + (pluginName + url) + ": not a servlet.");
                }
            }
        }
        catch (Throwable e) {
            Log.error(e);
        }
    }

    /**
     * Unregisters all JSP page servlets for a plugin.
     *
     * @param webXML the web.xml file containing JSP page names to servlet class file
     *               mappings.
     */
    public static void unregisterServlets(File webXML) {
        if (!webXML.exists()) {
            Log.error("Could not unregister plugin servlets, file " + webXML.getAbsolutePath() +
                    " does not exist.");
            return;
        }
        // Find the name of the plugin directory given that the webXML file
        // lives in plugins/[pluginName]/web/web.xml
        String pluginName = webXML.getParentFile().getParentFile().getParentFile().getName();
        try {
            SAXReader saxReader = new SAXReader(false);
            saxReader.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd",
                    false);
            Document doc = saxReader.read(webXML);
            // Find all <servelt-mapping> entries to discover name to URL mapping.
            List names = doc.selectNodes("//servlet-mapping");
            for (int i = 0; i < names.size(); i++) {
                Element nameElement = (Element)names.get(i);
                String url = nameElement.element("url-pattern").getTextTrim();
                // Destroy the servlet than remove from servlets map.
                HttpServlet servlet = servlets.get(pluginName + url);
                servlet.destroy();
                servlets.remove(pluginName + url);
                servlet = null;
            }
        }
        catch (Throwable e) {
            Log.error(e);
        }
    }

    /**
     * Handles a request for a JSP page. It checks to see if a servlet is mapped
     * for the JSP URL. If one is found, request handling is passed to it. If no
     * servlet is found, a 404 error is returned.
     *
     * @param pathInfo the extra path info.
     * @param request  the request object.
     * @param response the response object.
     * @throws ServletException if a servlet exception occurs while handling the request.
     * @throws IOException      if an IOException occurs while handling the request.
     */
    private void handleJSP(String pathInfo, HttpServletRequest request,
                           HttpServletResponse response) throws ServletException, IOException {
        // Strip the starting "/" from the path to find the JSP URL.
        String jspURL = pathInfo.substring(1);


        HttpServlet servlet = servlets.get(jspURL);
        if (servlet != null) {
            servlet.service(request, response);
            return;
        }
        else {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
    }

    /**
     * Handles a request for a Servlet. If one is found, request handling is passed to it.
     * If no servlet is found, a 404 error is returned.
     *
     * @param pathInfo the extra path info.
     * @param request  the request object.
     * @param response the response object.
     * @throws ServletException if a servlet exception occurs while handling the request.
     * @throws IOException      if an IOException occurs while handling the request.
     */
    private void handleServlet(String pathInfo, HttpServletRequest request,
                               HttpServletResponse response) throws ServletException, IOException {
        // Strip the starting "/" from the path to find the JSP URL.
        HttpServlet servlet = getServlet(pathInfo);
        if (servlet != null) {
            servlet.service(request, response);
            return;
        }
        else {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
    }

    /**
     * Returns the correct servlet with mapping checks.
     *
     * @param pathInfo the pathinfo to map to the servlet.
     * @return the mapped servlet, or null if no servlet was found.
     */
    private HttpServlet getServlet(String pathInfo) {
        pathInfo = pathInfo.substring(1).toLowerCase();

        HttpServlet servlet = servlets.get(pathInfo);
        if (servlet == null) {
            for (String key : servlets.keySet()) {
                int index = key.indexOf("/*");
                String searchkey = key;
                if (index != -1) {
                    searchkey = key.substring(0, index);
                }
                if (searchkey.startsWith(pathInfo) || pathInfo.startsWith(searchkey)) {
                    servlet = servlets.get(key);
                    break;
                }
            }
        }
        return servlet;
    }


    /**
     * Handles a request for other web items (images, flash, applets, etc.)
     *
     * @param pathInfo the extra path info.
     * @param response the response object.
     * @throws IOException if an IOException occurs while handling the request.
     */
    private void handleOtherRequest(String pathInfo, HttpServletResponse response) throws IOException {
        String[] parts = pathInfo.split("/");
        // Image request must be in correct format.
        if (parts.length < 3) {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            return;
        }

        String contextPath = "";
        int index = pathInfo.indexOf(parts[1]);
        if (index != -1) {
            contextPath = pathInfo.substring(index + parts[1].length());
        }

        File pluginDirectory = new File(JiveGlobals.getHomeDirectory(), "plugins");
        File file = new File(pluginDirectory, parts[1] + File.separator + "web" + contextPath);

        // When using dev environment, the images dir may be under something other that web.
        Plugin plugin = pluginManager.getPlugin(parts[1]);
        PluginDevEnvironment environment = pluginManager.getDevEnvironment(plugin);

        if (environment != null) {
            file = new File(environment.getWebRoot(), contextPath);
        }
        if (!file.exists()) {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        else {
            // Content type will be GIF or PNG.
            String contentType = "image/gif";
            if (pathInfo.endsWith(".png")) {
                contentType = "image/png";
            }
            else if (pathInfo.endsWith(".swf")) {
                contentType = "application/x-shockwave-flash";
            }
            response.setHeader("Content-disposition", "filename=\"" + file + "\";");
            response.setContentType(contentType);
            // Write out the image to the user.
            InputStream in = null;
            ServletOutputStream out = null;
            try {
                in = new BufferedInputStream(new FileInputStream(file));
                out = response.getOutputStream();

                // Set the size of the file.
                response.setContentLength((int)file.length());

                // Use a 1K buffer.
                byte[] buf = new byte[1024];
                int len;
                while ((len = in.read(buf)) != -1) {
                    out.write(buf, 0, len);
                }
            }
            finally {
                try {
                    in.close();
                }
                catch (Exception ignored) {
                }
                try {
                    out.close();
                }
                catch (Exception ignored) {
                }
            }
        }
    }


    /**
     * Handles a request for a JSP page in development mode. If development mode is
     * not enabled, this method returns false so that normal JSP handling can be performed.
     * If development mode is enabled, this method tries to locate the JSP, compile
     * it using JSPC, and then return the output.
     *
     * @param pathInfo the extra path info.
     * @param request  the request object.
     * @param response the response object.
     * @return true if this page request was handled; false if the request was not handled.
     */
    private boolean handleDevJSP(String pathInfo, HttpServletRequest request,
                                 HttpServletResponse response) {
        String jspURL = pathInfo.substring(1);

        // Handle pre-existing pages and fail over to pre-compiled pages.
        int fileSeperator = jspURL.indexOf("/");
        if (fileSeperator != -1) {
            String pluginName = jspURL.substring(0, fileSeperator);
            Plugin plugin = pluginManager.getPlugin(pluginName);

            PluginDevEnvironment environment = pluginManager.getDevEnvironment(plugin);
            // If development mode not turned on for plugin, return false.
            if (environment == null) {
                return false;
            }
            File webDir = environment.getWebRoot();
            if (webDir == null || !webDir.exists()) {
                return false;
            }

            File pluginDirectory = pluginManager.getPluginDirectory(plugin);

            File compilationDir = new File(pluginDirectory, "classes");
            compilationDir.mkdirs();

            String jsp = jspURL.substring(fileSeperator + 1);

            int indexOfLastSlash = jsp.lastIndexOf("/");
            String relativeDir = "";
            if (indexOfLastSlash != -1) {
                relativeDir = jsp.substring(0, indexOfLastSlash);
                relativeDir = relativeDir.replaceAll("//", ".") + '.';
            }

            File jspFile = new File(webDir, jsp);
            String filename = jspFile.getName();
            int indexOfPeriod = filename.indexOf(".");
            if (indexOfPeriod != -1) {
                filename = "dev" + StringUtils.randomString(4);
            }

            JspC jspc = new JspC();
            if (!jspFile.exists()) {
                return false;
            }
            try {
                jspc.setJspFiles(jspFile.getCanonicalPath());
            }
            catch (IOException e) {
                Log.error(e);
            }
            jspc.setOutputDir(compilationDir.getAbsolutePath());
            jspc.setClassName(filename);
            jspc.setCompile(true);

            jspc.setClassPath(getClasspathForPlugin(plugin));
            try {
                jspc.execute();

                try {
                    Object servletInstance = pluginManager.loadClass(plugin, "org.apache.jsp." +
                            relativeDir + filename).newInstance();
                    HttpServlet servlet = (HttpServlet)servletInstance;
                    servlet.init(servletConfig);
                    servlet.service(request, response);
                    return true;
                }
                catch (Exception e) {
                    Log.error(e);
                }

            }
            catch (JasperException e) {
                Log.error(e);
            }
        }
        return false;
    }

    /**
     * Returns the classpath to use for the JSPC Compiler.
     *
     * @param plugin the plugin the jspc will handle.
     * @return the classpath needed to compile a single jsp in a plugin.
     */
    private static String getClasspathForPlugin(Plugin plugin) {
        final StringBuilder classpath = new StringBuilder();

        File pluginDirectory = pluginManager.getPluginDirectory(plugin);

        PluginDevEnvironment pluginEnv = pluginManager.getDevEnvironment(plugin);

        PluginClassLoader pluginClassloader = pluginManager.getPluginClassloader(plugin);

        Collection col = pluginClassloader.getURLS();
        for (Object aCol : col) {
            URL url = (URL)aCol;
            File file = new File(url.getFile());

            classpath.append(file.getAbsolutePath().toString()).append(";");
        }

        // Load all jars from lib
        File libDirectory = new File(pluginDirectory, "lib");
        File[] libs = libDirectory.listFiles();
        final int no = libs != null ? libs.length : 0;
        for (int i = 0; i < no; i++) {
            File libFile = libs[i];
            classpath.append(libFile.getAbsolutePath()).append(';');
        }

        File messengerRoot = pluginDirectory.getParentFile().getParentFile().getParentFile();
        File messengerLib = new File(messengerRoot, "target//lib");

        classpath.append(messengerLib.getAbsolutePath()).append("//servlet.jar;");
        classpath.append(messengerLib.getAbsolutePath()).append("//messenger.jar;");
        classpath.append(messengerLib.getAbsolutePath()).append("//jasper-compiler.jar;");
        classpath.append(messengerLib.getAbsolutePath()).append("//jasper-runtime.jar;");
        classpath.append(pluginEnv.getClassesDir().getAbsolutePath()).append(";");

        return classpath.toString();
    }
}
