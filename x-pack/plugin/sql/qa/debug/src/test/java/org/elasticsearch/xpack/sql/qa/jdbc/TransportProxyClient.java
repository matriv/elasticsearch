/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.sql.qa.jdbc;

import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.ActionRequest;
import org.elasticsearch.action.ActionRequestBuilder;
import org.elasticsearch.action.ActionResponse;
import org.elasticsearch.action.ActionType;
import org.elasticsearch.action.TransportActionNodeProxy;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.transport.TransportService;

import java.io.Closeable;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static java.util.Collections.unmodifiableMap;

@SuppressWarnings({ "unchecked", "rawtypes" })
final class TransportProxyClient implements Closeable {

    private final TransportClientNodesService nodesService;
    private final Map<ActionType, TransportActionNodeProxy> proxies;

    TransportProxyClient(Settings settings, TransportService transportService, TransportClientNodesService nodesService,
            List<ActionType<?>> actions) {
        this.nodesService = nodesService;
        Map<ActionType<?>, TransportActionNodeProxy> proxies = new HashMap<>();
        for (ActionType<?> action : actions) {
            proxies.put(action, new TransportActionNodeProxy(settings, action, transportService));
        }
        this.proxies = unmodifiableMap(proxies);
    }

    public <Request extends ActionRequest, Response extends ActionResponse, 
            RequestBuilder extends ActionRequestBuilder<Request, Response>> void
        execute(final ActionType<Response> action, final Request request, ActionListener<Response> listener) {
        
        final TransportActionNodeProxy<Request, Response> proxy = proxies.get(action);
        assert proxy != null : "no proxy found for action: " + action;
        nodesService.execute(n -> proxy.execute(n, request, listener));
    }

    @Override
    public void close() {
        nodesService.close();
    }
}
