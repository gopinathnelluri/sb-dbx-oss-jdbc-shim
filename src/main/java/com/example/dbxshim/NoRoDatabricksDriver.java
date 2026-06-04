package com.example.dbxshim;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.DriverPropertyInfo;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Properties;
import java.util.logging.Logger;

/**
 * A thin JDBC {@link Driver} that delegates to the Databricks OSS JDBC driver
 * ({@code com.databricks.client.jdbc.Driver}) but turns
 * {@link Connection#setReadOnly(boolean)} into a no-op.
 *
 * <p>Why this exists: the Databricks OSS JDBC driver throws on
 * {@code setReadOnly(...)} ("Databricks OSS JDBC does not support readOnly mode"),
 * while connectors such as Starburst/Trino {@code generic-jdbc} call
 * {@code connection.setReadOnly(true)} on every connection. The legacy Simba
 * Databricks driver silently ignores that call; this shim reproduces that single
 * behaviour on top of the OSS driver.
 *
 * <p>Design goals / non-dependencies:
 * <ul>
 *   <li>No compile-time or runtime dependency on Starburst/Trino — it implements
 *       only {@link java.sql.Driver} and proxies {@link java.sql.Connection},
 *       both JDK-standard types.</li>
 *   <li>No compile-time dependency on the Databricks driver — the delegate is
 *       loaded by reflection from a class-name string, so there is no version
 *       pin. The class name defaults to {@value #DEFAULT_DELEGATE_CLASS} and can
 *       be overridden with {@code -D{@value #DELEGATE_CLASS_PROPERTY}=...} if a
 *       future driver renames it.</li>
 * </ul>
 *
 * <p>Usage (Starburst {@code generic-jdbc} catalog):
 * <pre>
 *   connector.name=generic-jdbc
 *   driver-class=com.example.dbxshim.NoRoDatabricksDriver
 *   connection-url=jdbc:databricks-noro://&lt;host&gt;:443/default;...
 * </pre>
 * The {@code jdbc:databricks-noro:} prefix is rewritten to {@code jdbc:databricks:}
 * before delegating, so every Databricks URL property works unchanged.
 */
public final class NoRoDatabricksDriver implements Driver {

    /** URL scheme this driver answers to. */
    public static final String SHIM_PREFIX = "jdbc:databricks-noro:";

    /** Real scheme of the delegate Databricks driver. */
    private static final String DELEGATE_PREFIX = "jdbc:databricks:";

    /** System property to override the delegate driver class name. */
    public static final String DELEGATE_CLASS_PROPERTY = "dbxshim.delegateDriverClass";

    /** Default delegate driver class (both the OSS and Simba drivers use this name). */
    public static final String DEFAULT_DELEGATE_CLASS = "com.databricks.client.jdbc.Driver";

    static {
        try {
            DriverManager.registerDriver(new NoRoDatabricksDriver());
        }
        catch (SQLException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    /** Lazily loaded so registration never requires the Databricks jar to be present. */
    private volatile Driver delegate;

    @Override
    public Connection connect(String url, Properties info)
            throws SQLException
    {
        if (!acceptsURL(url)) {
            // Per the JDBC contract, return null for URLs we do not handle.
            return null;
        }
        String delegateUrl = DELEGATE_PREFIX + url.substring(SHIM_PREFIX.length());
        Connection real = delegate().connect(delegateUrl, info);
        if (real == null) {
            return null;
        }
        return wrap(real);
    }

    @Override
    public boolean acceptsURL(String url)
    {
        return url != null && url.startsWith(SHIM_PREFIX);
    }

    @Override
    public DriverPropertyInfo[] getPropertyInfo(String url, Properties info)
            throws SQLException
    {
        String delegateUrl = (url != null && url.startsWith(SHIM_PREFIX))
                ? DELEGATE_PREFIX + url.substring(SHIM_PREFIX.length())
                : url;
        return delegate().getPropertyInfo(delegateUrl, info);
    }

    @Override
    public int getMajorVersion()
    {
        try {
            return delegate().getMajorVersion();
        }
        catch (RuntimeException e) {
            return 0;
        }
    }

    @Override
    public int getMinorVersion()
    {
        try {
            return delegate().getMinorVersion();
        }
        catch (RuntimeException e) {
            return 0;
        }
    }

    @Override
    public boolean jdbcCompliant()
    {
        try {
            return delegate().jdbcCompliant();
        }
        catch (RuntimeException e) {
            return false;
        }
    }

    @Override
    public Logger getParentLogger()
            throws SQLFeatureNotSupportedException
    {
        return delegate().getParentLogger();
    }

    // ---------------------------------------------------------------- internals

    private Driver delegate()
    {
        Driver d = delegate;
        if (d == null) {
            synchronized (this) {
                d = delegate;
                if (d == null) {
                    d = loadDelegate();
                    delegate = d;
                }
            }
        }
        return d;
    }

    private static Driver loadDelegate()
    {
        String className = System.getProperty(DELEGATE_CLASS_PROPERTY, DEFAULT_DELEGATE_CLASS);
        try {
            return (Driver) Class.forName(className)
                    .getDeclaredConstructor()
                    .newInstance();
        }
        catch (ReflectiveOperationException e) {
            throw new IllegalStateException(
                    "Could not load delegate Databricks JDBC driver '" + className + "'. "
                            + "Ensure the Databricks JDBC driver jar is on the classpath, "
                            + "or override the class name via -D" + DELEGATE_CLASS_PROPERTY,
                    e);
        }
    }

    private static Connection wrap(Connection real)
    {
        // Use the shim's own classloader: it is the (plugin) loader that can see
        // both this class and java.sql.Connection via its parent. Using
        // Connection.class.getClassLoader() would be the bootstrap loader (null),
        // which cannot define a proxy class.
        return (Connection) Proxy.newProxyInstance(
                NoRoDatabricksDriver.class.getClassLoader(),
                new Class<?>[] {Connection.class},
                new ReadOnlyNoOpHandler(real));
    }

    /** Forwards every {@link Connection} call to the real connection except {@code setReadOnly}. */
    private static final class ReadOnlyNoOpHandler
            implements InvocationHandler
    {
        private final Connection target;

        ReadOnlyNoOpHandler(Connection target)
        {
            this.target = target;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args)
                throws Throwable
        {
            // The OSS driver throws on setReadOnly(...); swallow the call so the
            // connection succeeds. setReadOnly returns void, so null is correct.
            if ("setReadOnly".equals(method.getName())) {
                return null;
            }
            try {
                return method.invoke(target, args);
            }
            catch (InvocationTargetException e) {
                // Unwrap so callers see the real SQLException, not a wrapper.
                throw e.getCause();
            }
        }
    }
}
