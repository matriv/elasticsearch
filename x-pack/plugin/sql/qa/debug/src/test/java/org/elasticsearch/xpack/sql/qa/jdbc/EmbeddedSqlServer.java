/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */

package org.elasticsearch.xpack.sql.qa.jdbc;

import org.apache.logging.log4j.LogManager;
import org.junit.rules.ExternalResource;

import java.security.AccessControlException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Properties;

import static org.junit.Assert.assertNotNull;

/**
 * Embedded JDBC server that uses the transport client to power
 * the jdbc endpoints in the same JVM as the tests.
 */
public class EmbeddedSqlServer extends ExternalResource implements AutoCloseable {

    private SqlTransportClient client;
    private SqlHttpServer server;
    private String jdbcUrl;
    private final Properties properties;

    public EmbeddedSqlServer() {
        this(false);
    }

    public EmbeddedSqlServer(boolean debug) {
        properties = new Properties();
        if (debug) {
            properties.setProperty("debug", "true");
        }
    }

    @Override
    protected void before() throws Throwable {
        try {
            client = new SqlTransportClient();

            // update static reference
            ClientReference.HANDLER.actualClient = client;
        } catch (ExceptionInInitializerError e) {
            if (e.getCause() instanceof AccessControlException) {
                throw new RuntimeException(getClass().getSimpleName() + " is not available with the security manager", e);
            } else {
                throw e;
            }
        }

        server = new SqlHttpServer(client);
        server.start(0);
        jdbcUrl = server.jdbcUrl();

        LogManager.getLogger(EmbeddedSqlServer.class).info("Embedded SQL started at [{}]", server.url());
    }

    @Override
    public void close() throws Exception {
        after();
    }

    @Override
    protected void after() {
        if (client != null) {
            client.close();
            client = null;
        }
        if (server != null) {
            server.stop();
            server = null;
        }
    }

    public Connection connection(Properties props) throws SQLException {
        assertNotNull("ES JDBC Server is null - make sure ES is properly run as a @ClassRule", client);
        Properties p = new Properties(properties);
        p.putAll(props);
        return DriverManager.getConnection(jdbcUrl, p);
        //        JdbcDataSource dataSource = new JdbcDataSource();
        //        dataSource.setProperties(properties);
        //        dataSource.setUrl(jdbcUrl);
        //        return dataSource.getConnection();
    }
}