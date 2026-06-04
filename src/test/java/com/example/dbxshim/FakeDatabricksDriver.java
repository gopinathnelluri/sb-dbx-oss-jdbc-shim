package com.example.dbxshim;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverPropertyInfo;
import java.sql.SQLException;
import java.util.Properties;
import java.util.logging.Logger;

/**
 * Test double that mimics the Databricks OSS JDBC driver: it accepts
 * {@code jdbc:databricks:} URLs and returns a {@link Connection} whose
 * {@code setReadOnly(...)} throws, exactly like the real OSS driver.
 *
 * <p>The shim is pointed at this class via the
 * {@link NoRoDatabricksDriver#DELEGATE_CLASS_PROPERTY} system property in tests,
 * so no real Databricks jar is needed to verify behaviour.
 */
public final class FakeDatabricksDriver
        implements Driver
{
    /** Records the URL the shim delegated with, so tests can assert the prefix rewrite. */
    static volatile String lastUrl;

    @Override
    public Connection connect(String url, Properties info)
    {
        if (url == null || !url.startsWith("jdbc:databricks:")) {
            return null;
        }
        lastUrl = url;
        return (Connection) Proxy.newProxyInstance(
                FakeDatabricksDriver.class.getClassLoader(),
                new Class<?>[] {Connection.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "setReadOnly":
                            throw new SQLException("Databricks OSS JDBC does not support readOnly mode");
                        case "isReadOnly":
                            return Boolean.FALSE;
                        case "getCatalog":
                            return "fake-catalog";
                        case "isClosed":
                            return Boolean.FALSE;
                        case "close":
                            return null;
                        default:
                            return defaultValue(method.getReturnType());
                    }
                });
    }

    @Override
    public boolean acceptsURL(String url)
    {
        return url != null && url.startsWith("jdbc:databricks:");
    }

    @Override
    public DriverPropertyInfo[] getPropertyInfo(String url, Properties info)
    {
        return new DriverPropertyInfo[0];
    }

    @Override
    public int getMajorVersion()
    {
        return 9;
    }

    @Override
    public int getMinorVersion()
    {
        return 7;
    }

    @Override
    public boolean jdbcCompliant()
    {
        return false;
    }

    @Override
    public Logger getParentLogger()
    {
        return Logger.getGlobal();
    }

    private static Object defaultValue(Class<?> type)
    {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return Boolean.FALSE;
        }
        if (type == void.class) {
            return null;
        }
        return 0;
    }
}
