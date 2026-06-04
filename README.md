# Databricks readOnly-tolerant JDBC shim

A thin JDBC driver that delegates to the **Databricks OSS JDBC driver**
(`com.databricks.client.jdbc.Driver`) but turns `Connection.setReadOnly(...)`
into a no-op.

## Why

The Databricks OSS JDBC driver throws on `setReadOnly(...)`:

```
Databricks OSS JDBC does not support readOnly mode
```

Connectors like **Starburst / Trino `generic-jdbc`** call
`connection.setReadOnly(true)` on every connection, so the OSS driver fails at
connection time (surfacing as a *Metadata Initialization Error* in Starburst).
The legacy Simba Databricks driver silently ignores that call; this shim
reproduces that single behaviour on top of the OSS driver.

## No version coupling

- **No Starburst/Trino dependency.** It implements only `java.sql.Driver` and
  proxies `java.sql.Connection` — both JDK-standard. Starburst just loads it via
  `driver-class`.
- **No Databricks dependency.** The delegate driver is loaded by *reflection*
  from a class-name string, so there is no compile-time jar or version pin. The
  class name defaults to `com.databricks.client.jdbc.Driver` and can be
  overridden with `-Ddbxshim.delegateDriverClass=...` if a future driver renames
  it.

The built jar is therefore a tiny, dependency-free artifact (bytecode targets
Java 11 for broad runtime compatibility).

## Build

```bash
mvn clean package
# -> target/databricksnoro-jdbc-shim-1.0.0.jar
```

## Deploy to Starburst

Put **both** jars on the `generic-jdbc` plugin classpath of every node:

```
<starburst>/plugin/generic-jdbc/databricksnoro-jdbc-shim-1.0.0.jar
<starburst>/plugin/generic-jdbc/databricks-jdbc-<version>.jar   # the OSS driver
```

Catalog (`etc/catalog/databricks.properties`):

```properties
connector.name=generic-jdbc
driver-class=com.example.dbxshim.NoRoDatabricksDriver
# Note the jdbc:databricksnoro: scheme; it is rewritten to jdbc:databricks:
# before delegating, so every Databricks URL property works unchanged.
connection-url=jdbc:databricksnoro://<host>:443/default;transportMode=http;ssl=1;AuthMech=11;Auth_Flow=1;OAuth2ClientId=${ENV:DBX_SP_CLIENT_ID};OAuth2Secret=${ENV:DBX_SP_SECRET};httpPath=/sql/1.0/warehouses/<id>
```

Restart the coordinator and workers, then verify:

```sql
SHOW SCHEMAS FROM databricks;
```

## How it works

```
Starburst --connect(jdbc:databricksnoro://...)--> NoRoDatabricksDriver
                                                        |
                          rewrite prefix -> jdbc:databricks://...
                                                        |
                                       delegate.connect(...) [OSS driver]
                                                        |
                              wrap returned Connection in a dynamic proxy
                                                        |
   setReadOnly(...)  --> swallowed (no-op)              other calls --> delegated unchanged
```

## Scope / caveats

- Fixes **only** the client-side `setReadOnly` rejection. It does **not** change
  any server-side behaviour, including the Databricks SQL Warehouse limitation
  that double-quoted identifiers / `spark.sql.ansi.doubleQuotedIdentifiers`
  cannot be enabled there — that is independent of the driver.
- If a caller does `connection.unwrap(Connection.class)` and then calls
  `setReadOnly` on the unwrapped object, it would bypass the proxy. Starburst's
  JDBC framework does not do this for this call.
