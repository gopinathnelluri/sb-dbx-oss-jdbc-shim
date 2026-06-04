package com.example.dbxshim;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NoRoDatabricksDriverTest
{
    private final NoRoDatabricksDriver driver = new NoRoDatabricksDriver();

    @BeforeAll
    static void pointShimAtFakeDriver()
    {
        System.setProperty(NoRoDatabricksDriver.DELEGATE_CLASS_PROPERTY, FakeDatabricksDriver.class.getName());
    }

    @AfterAll
    static void clearProperty()
    {
        System.clearProperty(NoRoDatabricksDriver.DELEGATE_CLASS_PROPERTY);
    }

    @Test
    void acceptsOnlyTheShimPrefix()
    {
        assertTrue(driver.acceptsURL("jdbc:databricks-noro://host:443/default"));
        assertFalse(driver.acceptsURL("jdbc:databricks://host:443/default"));
        assertFalse(driver.acceptsURL("jdbc:postgresql://host/db"));
        assertFalse(driver.acceptsURL(null));
    }

    @Test
    void connectRewritesThePrefixBeforeDelegating()
            throws Exception
    {
        driver.connect("jdbc:databricks-noro://host:443/default;httpPath=/x", new Properties());
        assertEquals("jdbc:databricks://host:443/default;httpPath=/x", FakeDatabricksDriver.lastUrl);
    }

    @Test
    void setReadOnlyIsSwallowedInsteadOfThrowing()
            throws Exception
    {
        Connection connection = driver.connect("jdbc:databricks-noro://host/default", new Properties());
        // The fake (like the real OSS driver) would throw on setReadOnly; the shim must absorb it.
        assertDoesNotThrow(() -> connection.setReadOnly(true));
        assertDoesNotThrow(() -> connection.setReadOnly(false));
    }

    @Test
    void otherCallsAreDelegatedUnchanged()
            throws Exception
    {
        Connection connection = driver.connect("jdbc:databricks-noro://host/default", new Properties());
        assertEquals("fake-catalog", connection.getCatalog());
        assertFalse(connection.isReadOnly());
        assertFalse(connection.isClosed());
    }

    @Test
    void returnsNullForUrlsItDoesNotHandle()
            throws Exception
    {
        assertNull(driver.connect("jdbc:databricks://host/default", new Properties()));
    }
}
