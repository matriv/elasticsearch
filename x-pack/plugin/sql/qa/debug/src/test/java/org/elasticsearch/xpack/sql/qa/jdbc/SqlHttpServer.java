/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */

package org.elasticsearch.xpack.sql.qa.jdbc;

import com.sun.net.httpserver.HttpServer;

import org.elasticsearch.client.Client;
import org.elasticsearch.common.SuppressForbidden;
import org.elasticsearch.mock.orig.Mockito;
import org.elasticsearch.rest.RestController;
import org.elasticsearch.rest.action.RestMainAction;
import org.elasticsearch.xpack.sql.plugin.RestSqlClearCursorAction;
import org.elasticsearch.xpack.sql.plugin.RestSqlQueryAction;
import org.elasticsearch.xpack.sql.proto.Protocol;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

@SuppressForbidden(reason = "use http server")
public class SqlHttpServer {

    private final Client client;
    private final SqlNodeClient sqlClient;

    private HttpServer server;
    private ExecutorService executor;

    public SqlHttpServer(Client client) {
        this.client = client;
        this.sqlClient = client instanceof SqlNodeClient ? (SqlNodeClient) client : new SqlNodeClient(client);
    }


    public void start(int port) throws IOException {
        // similar to Executors.newCached but with a smaller bound and much smaller keep-alive
        executor = new ThreadPoolExecutor(1, 10, 250, TimeUnit.MILLISECONDS, new SynchronousQueue<Runnable>());

        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0);
        initialize(server);
        server.setExecutor(executor);
        server.start();
    }

    private void initialize(HttpServer server) {
        // initialize cursor
        initializeActions(server);
    }


    private void initializeActions(HttpServer server) {

        RestController mock = Mockito.mock(RestController.class);

        // root action
        server.createContext("/",
                new SqlHandler(sqlClient, new RestMainAction(mock)));
        server.createContext(Protocol.SQL_QUERY_REST_ENDPOINT,
                new SqlHandler(sqlClient, new RestSqlQueryAction(mock)));
        server.createContext(Protocol.CLEAR_CURSOR_REST_ENDPOINT,
                new SqlHandler(sqlClient, new RestSqlClearCursorAction(mock)));
    }


    public void stop() {
        server.stop(1);
        server = null;
        executor.shutdownNow();
        executor = null;
    }

    public InetSocketAddress address() {
        return server != null ? server.getAddress() : null;
    }

    public String url() {
        return server != null ? "localhost:" + address().getPort() : "<not started>";
    }

    public String jdbcUrl() {
        return "jdbc:es://" + url();
    }

    public Client client() {
        return client;
    }
}