/*
 * Copyright 2005-2007 Noelios Consulting.
 * 
 * The contents of this file are subject to the terms of the Common Development
 * and Distribution License (the "License"). You may not use this file except in
 * compliance with the License.
 * 
 * You can obtain a copy of the license at
 * http://www.opensource.org/licenses/cddl1.txt See the License for the specific
 * language governing permissions and limitations under the License.
 * 
 * When distributing Covered Code, include this CDDL HEADER in each file and
 * include the License file at http://www.opensource.org/licenses/cddl1.txt If
 * applicable, add the following below this CDDL HEADER, with the fields
 * enclosed by brackets "[]" replaced with your own identifying information:
 * Portions Copyright [yyyy] [name of copyright owner]
 */

package com.noelios.restlet.ext.xdb;

import com.noelios.restlet.application.ApplicationContext;
import com.noelios.restlet.ext.servlet.ServerServlet;
import com.noelios.restlet.ext.servlet.ServletContextAdapter;
import com.noelios.restlet.http.HttpServerHelper;

import java.io.IOException;

import java.lang.reflect.InvocationTargetException;

import java.lang.reflect.Method;

import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;

import java.util.ArrayList;
import java.util.Enumeration;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.restlet.Application;
import org.restlet.Component;
import org.restlet.Context;
import org.restlet.Route;
import org.restlet.Server;
import org.restlet.data.Protocol;
import org.restlet.util.Engine;

/**
 * Servlet acting like an HTTP server connector. See <a
 * href="/documentation/1.0/faq#02">Developper FAQ #2</a> for details on how to
 * integrate a Restlet application into a servlet container.<br/> Here is a
 * sample configuration for your Restlet webapp:
 * 
 * <pre>
 * &lt;?xml version=&quot;1.0&quot; encoding=&quot;ISO-8859-1&quot;?&gt;
 * &lt;!DOCTYPE web-app PUBLIC &quot;-//Sun Microsystems, Inc.//DTD Web Application 2.3//EN&quot; &quot;http://java.sun.com/dtd/web-app_2_3.dtd&quot;&gt;
 * &lt;web-app&gt;
 *         &lt;display-name&gt;Restlet adapter&lt;/display-name&gt;
 * 
 * 
 *         &lt;!-- Restlet adapter --&gt;
 *         &lt;servlet&gt;
 *                 &lt;servlet-name&gt;XDBServerServlet&lt;/servlet-name&gt;
 *                 &lt;servlet-class&gt;com.noelios.restlet.ext.xdb.XDBServerServlet&lt;/servlet-class&gt;
 *                 &lt;!-- Your application class name --&gt;
 *                 &lt;init-param xmlns=&quot;http://xmlns.oracle.com/xdb/xdbconfig.xsd&quot;&gt;
 *                     &lt;param-name&gt;org.restlet.application&lt;/param-name&gt;
 *                     &lt;param-value&gt;org.restlet.example.tutorial.Part12&lt;/param-value&gt;
 *                     &lt;description&gt;REST Application&lt;/description&gt;
 *                 &lt;/init-param&gt;
 *                 &lt;init-param xmlns=&quot;http://xmlns.oracle.com/xdb/xdbconfig.xsd&quot;&gt;
 *                     &lt;param-name&gt;org.restlet.query&lt;/param-name&gt;
 *                     &lt;param-value&gt;keywords,kwd,true;xx,yy,false&lt;/param-value&gt;
 *                     &lt;description&gt;route.extractQuery arguments&lt;/description&gt;
 *                 &lt;/init-param&gt;
 *         &lt;/servlet&gt;
 * 
 *         &lt;!-- Catch all requests --&gt;
 *         &lt;servlet-mapping&gt;
 *                 &lt;servlet-name&gt;XDBServerServlet&lt;/servlet-name&gt;
 *                 &lt;url-pattern&gt;/users/*&lt;/url-pattern&gt;
 *         &lt;/servlet-mapping&gt;
 * &lt;/web-app&gt;
 * </pre>
 * 
 * The enumeration of initParameters of your Servlet will be copied to the
 * "context.parameters" property of your application. This way, you can pass
 * additional initialization parameters to your Restlet application, and share
 * them with existing Servlets.
 * 
 * @see <a href="http://java.sun.com/j2ee/">J2EE home page</a>
 * @author Marcelo F. Ochoa (mochoa@ieee.org)
 */
public class XdbServerServlet extends ServerServlet {
    /**
     * The Servlet initialization parameter name containing the list of coma
     * separated string of the query extract arguments separated by ; for
     * example keywords,kwd,true;xx,yy,false. This parameters will be used with
     * route.extractQuery(arg1,arg2,arg3) where arg1=keywords, arg2=kwd and
     * arg3=true for the above example
     */
    private static final String EXTRACT_QUERY_ATTRIBUTE = "org.restlet.query";

    /** The default value for the NAME_APPLICATION_ATTRIBUTE parameter. */
    private static final String EXTRACT_QUERY_ATTRIBUTE_DEFAULT = "";

    /** Serial version identifier. */
    private static final long serialVersionUID = 1L;

    /**
     * Closes JDBC resources
     * 
     * @param statement
     *                Any statement.
     * @param resultSet
     *                Any result set.
     */
    protected static void closeDbResources(Statement statement,
            ResultSet resultSet) {
        if (resultSet != null)
            try {
                resultSet.close();
            } catch (SQLException s) {
                s.printStackTrace(System.err);
            } finally {
                resultSet = null;
            }
        if (statement != null)
            try {
                statement.close();
            } catch (SQLException s) {
                s.printStackTrace(System.err);
            } finally {
                statement = null;
            }
    }

    /**
     * Returns a JDBC connection. Works inside or outside the OJVM.
     * 
     * @return A JDBC connection.
     * @throws SQLException
     */
    protected static Connection getConnection() throws ServletException {
        Connection conn = null;

        try {
            if (System.getProperty("java.vm.name").equals("JServer VM")) {
                conn = DriverManager.getConnection("jdbc:oracle:kprb:",
                        "default", "default");
            } else {
                throw new ServletException(
                        "Class designed to be used at Server side: jdbc:oracle:thin:@");
            }
        } catch (SQLException s) {
            System.err.println("Exception getting SQL Connection: "
                    + s.getLocalizedMessage());
            throw new ServletException(
                    "Unable to connect using: jdbc:oracle:kprb:", s);
        }

        return conn;
    }

    /** Connection to the XMLDB repository. */
    private volatile transient Connection conn;

    /** The local address of the server connector. */
    private volatile transient String localAddress = null;

    /** The local port of the server connector. */
    private volatile transient int localPort = -1;

    /** Indicates if remote debugging should be activated. */
    private volatile transient boolean remoteDebugging = false;

    /**
     * Constructor.
     */
    public XdbServerServlet() {
        super();
    }

    /**
     * Creates the single Application used by this Servlet. Do not attach WAR
     * protocol handler because WAR are not loaded into XMLDB repository
     * 
     * TODO: provide an alternative method to deploy applications
     * 
     * @param context
     *                The Context for the Application.
     * 
     * @return The newly created Application or null if unable to create.
     */
    @Override
    @SuppressWarnings("unchecked")
    protected Application createApplication(Context context) {
        Application application = null;

        // Try to instantiate a new target application
        // First, find the application class name
        String applicationClassName = getInitParameter(Application.KEY, null);

        // Load the application class using the given class name
        if (applicationClassName != null) {
            try {
                int doubleDotPos = applicationClassName.indexOf(':');
                Class targetClass;

                if (doubleDotPos > 0) {
                    // Use DbmsJava by reflection to avoid dependency to Oracle
                    // libs at compiling time
                    Class loaderClass = Engine
                            .classForName("oracle.aurora.rdbms.DbmsJava");
                    Method meth = loaderClass.getMethod(
                            "classForNameAndSchema", new Class[] {
                                    String.class, String.class });
                    String sch = applicationClassName
                            .substring(0, doubleDotPos);
                    String className = applicationClassName
                            .substring(doubleDotPos + 1);
                    log("[Noelios Restlet Engine] - Schema: " + sch
                            + " class: " + className + " loader: "
                            + loaderClass);
                    targetClass = (Class) meth.invoke(null, new Object[] {
                            className, sch });
                } else
                    targetClass = Engine.classForName(applicationClassName);

                try {
                    // Create a new instance of the application class by
                    // invoking the constructor with the Context parameter.
                    application = (Application) targetClass.getConstructor(
                            Context.class).newInstance(
                            new ServletContextAdapter(this, context));
                } catch (NoSuchMethodException e) {
                    log(
                            "[Noelios Restlet Engine] - The ServerServlet couldn't invoke the constructor of the target class. Please check this class has a constructor with a single parameter of type Context. The empty constructor and the context setter will be used instead.",
                            e);
                    // The constructor with the Context parameter does not
                    // exist. Instantiate an application with the default
                    // constructor then invoke the setContext method.
                    application = (Application) targetClass.getConstructor()
                            .newInstance();

                    // Set the context based on the Servlet's context
                    ApplicationContext applicationContext = (ApplicationContext) application
                            .getContext();
                    application.setContext(new ApplicationContext(application,
                            new ServletContextAdapter(this, context),
                            applicationContext.getLogger()));
                }
            } catch (ClassNotFoundException e) {
                log(
                        "[Noelios Restlet Engine] - The ServerServlet couldn't find the target class. Please check that your classpath includes "
                                + applicationClassName, e);

            } catch (InstantiationException e) {
                log(
                        "[Noelios Restlet Engine] - The ServerServlet couldn't instantiate the target class. Please check this class has an empty constructor "
                                + applicationClassName, e);
            } catch (IllegalAccessException e) {
                log(
                        "[Noelios Restlet Engine] - The ServerServlet couldn't instantiate the target class. Please check that you have to proper access rights to "
                                + applicationClassName, e);
            } catch (NoSuchMethodException e) {
                log(
                        "[Noelios Restlet Engine] - The ServerServlet couldn't invoke the constructor of the target class. Please check this class has a constructor with a single parameter of Context "
                                + applicationClassName, e);
            } catch (InvocationTargetException e) {
                log(
                        "[Noelios Restlet Engine] - The ServerServlet couldn't instantiate the target class. An exception was thrown while creating "
                                + applicationClassName, e);
            }
        }

        if (application != null) {
            ApplicationContext applicationContext = (ApplicationContext) application
                    .getContext();

            // Copy all the servlet parameters into the context
            String initParam;

            // Copy all the Servlet component initialization parameters
            javax.servlet.ServletConfig servletConfig = getServletConfig();
            for (Enumeration<String> enum1 = servletConfig
                    .getInitParameterNames(); enum1.hasMoreElements();) {
                initParam = enum1.nextElement();
                applicationContext.getParameters().add(initParam,
                        servletConfig.getInitParameter(initParam));
            }

            // Copy all the Servlet application initialization parameters
            for (Enumeration<String> enum1 = getServletContext()
                    .getInitParameterNames(); enum1.hasMoreElements();) {
                initParam = enum1.nextElement();
                applicationContext.getParameters().add(initParam,
                        getServletContext().getInitParameter(initParam));
            }
        }

        return application;
    }

    @Override
    protected HttpServerHelper createServer(HttpServletRequest request) {
        HttpServerHelper result = null;
        Component component = getComponent();
        Application application = getApplication();

        if ((component != null) && (application != null)) {
            // First, let's locate the closest component
            Server server = new Server(component.getContext(),
                    new ArrayList<Protocol>(), localAddress, localPort,
                    component);
            server.getProtocols().add(Protocol.HTTP);
            result = new HttpServerHelper(server);

            // Attach the application, do not use getServletContext here because
            // XMLDB allways return null
            String uriPattern = request.getServletPath();
            log("[Noelios Restlet Engine] - Attaching application: "
                    + application + " uri: " + uriPattern);
            Route route = component.getDefaultHost().attach(uriPattern,
                    application);

            String extractQueries = getInitParameter(EXTRACT_QUERY_ATTRIBUTE,
                    EXTRACT_QUERY_ATTRIBUTE_DEFAULT);
            log("[Noelios Restlet Engine] - Parsing query attributes: "
                    + extractQueries);
            if (extractQueries != null && extractQueries.length() > 0) {
                String extractQuery[] = extractQueries.split(";");
                for (int i = 0; i < extractQuery.length; i++) {
                    String args[] = extractQuery[i].split(",");
                    route.extractQuery(args[0], args[1], "true"
                            .equalsIgnoreCase(args[2]));
                }
            }
        }

        return result;
    }

    @Override
    public void destroy() {
        CallableStatement preparedstatement = null;
        try {
            if (remoteDebugging) {
                preparedstatement = conn
                        .prepareCall("{ call dbms_debug_jdwp.disconnect }");
                preparedstatement.execute();
            }
        } catch (SQLException e) {
            log(e.getLocalizedMessage(), e);
        } finally {
            closeDbResources(preparedstatement, null);
        }
        super.destroy();
    }

    /**
     * Returns a configuration parameter.
     * 
     * @return An String object within the /home/'||USER||'/restlet/app.xml
     *         XMLDB file.
     */
    private String getConfigParameter(String app, String name) {
        String config = null;
        ResultSet resultset = null;
        PreparedStatement preparedstatement = null;
        log("[Noelios Restlet Engine] - Try to load '" + name
                + "' parameter from '/home/'||USER||'" + "/restlet/" + app
                + ".xml");

        try {
            preparedstatement = conn
                    .prepareStatement("select extractValue(res,'/res:Resource/res:Contents/restlet-app/'||?,"
                            + "'xmlns:res=http://xmlns.oracle.com/xdb/XDBResource.xsd') from\n"
                            + "resource_view where equals_path(res,'/home/'||USER||?)=1");
            preparedstatement.setString(1, name);
            preparedstatement.setString(2, "/restlet/" + app + ".xml");
            resultset = preparedstatement.executeQuery();

            if (resultset.next())
                config = resultset.getString(1);
        } catch (SQLException sqe) {
            log(sqe.getLocalizedMessage(), sqe);
            throw new RuntimeException(
                    ".getConfigParameter:  error from XMLDB loading '/home/'||USER||'"
                            + "/restlet/" + app + ".xml", sqe);
        } finally {
            closeDbResources(preparedstatement, resultset);
        }

        return config;
    }

    @Override
    public String getInitParameter(String name, String defaultValue) {
        String app = getServletConfig().getServletName();

        // Try to load from XMLDB repository
        String result = getConfigParameter(app, name);

        // XDB do not support Servlet Context parameter
        // use Servlet init parameter instead
        if (result == null) {
            result = this.getInitParameter(name);
        }

        if (result == null) {
            result = defaultValue;
        }

        return result;
    }

    @Override
    public void init() throws ServletException {
        CallableStatement preparedstatement = null;
        if (this.conn == null)
            this.conn = getConnection();

        try {
            int endPoint = 1;
            preparedstatement = conn
                    .prepareCall("{ call dbms_xdb.getListenerEndPoint(1,?,?,?) }");
            preparedstatement.registerOutParameter(1, Types.VARCHAR);
            preparedstatement.registerOutParameter(2, Types.INTEGER);
            preparedstatement.registerOutParameter(3, Types.INTEGER);
            preparedstatement.execute();

            localAddress = preparedstatement.getString(1);
            localPort = preparedstatement.getInt(2);
            endPoint = preparedstatement.getInt(3);

            log("[Noelios Restlet Engine] - The ServerServlet address = "
                    + localAddress);
            log("[Noelios Restlet Engine] - The ServerServlet port = "
                    + localPort);
            log("[Noelios Restlet Engine] - The ServerServlet endpoint = "
                    + endPoint);
        } catch (SQLException e) {
            log(e.getLocalizedMessage(), e);
        } finally {
            closeDbResources(preparedstatement, null);
        }

        try {
            if (remoteDebugging) {
                preparedstatement = conn
                        .prepareCall("{ call dbms_debug_jdwp.connect_tcp(?,?) }");
                preparedstatement.setString(1, "localhost");
                preparedstatement.setInt(2, 4000);
                preparedstatement.execute();
            }
        } catch (SQLException e) {
            log(e.getLocalizedMessage(), e);
        } finally {
            closeDbResources(preparedstatement, null);
        }

        if ((getApplication() != null) && (getApplication().isStopped())) {
            try {
                getApplication().start();
            } catch (Exception e) {
                log("Error during the starting of the Restlet Application", e);
            }
        }
    }

    @Override
    public void service(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        HttpServerHelper helper = getServer(request);

        if (helper != null) {
            helper.handle(new XdbServletCall(helper.getServer(), request,
                    response));
        } else {
            log("[Noelios Restlet Engine] - Unable to get the Restlet HTTP server connector. Status code 500 returned.");
            response.sendError(500);
        }
    }
}
