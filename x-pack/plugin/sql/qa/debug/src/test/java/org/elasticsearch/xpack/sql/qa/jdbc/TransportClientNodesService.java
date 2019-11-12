/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */

package org.elasticsearch.xpack.sql.qa.jdbc;

import org.elasticsearch.Version;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.admin.cluster.state.ClusterStateAction;
import org.elasticsearch.action.admin.cluster.state.ClusterStateResponse;
import org.elasticsearch.action.support.PlainActionFuture;
import org.elasticsearch.client.Requests;
import org.elasticsearch.cluster.node.DiscoveryNode;
import org.elasticsearch.cluster.node.DiscoveryNodes;
import org.elasticsearch.common.UUIDs;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.transport.TransportAddress;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.transport.ConnectionProfile;
import org.elasticsearch.transport.Transport;
import org.elasticsearch.transport.TransportException;
import org.elasticsearch.transport.TransportFuture;
import org.elasticsearch.transport.TransportRequestOptions;
import org.elasticsearch.transport.TransportResponseHandler;
import org.elasticsearch.transport.TransportService;

import java.io.Closeable;
import java.io.IOException;
import java.util.function.Consumer;

import static java.util.Collections.emptyMap;
import static java.util.Collections.emptySet;

class TransportClientNodesService implements Closeable {

    boolean closed = false;
    final TransportService transportService;
    final TransportAddress address;
    volatile DiscoveryNode node;
    final TimeValue pingTimeout = TimeValue.timeValueSeconds(5);

    /** {@link ConnectionProfile} to use when to connecting to the listed nodes and doing a liveness check */
    private static final ConnectionProfile LISTED_NODES_PROFILE;

    static {
        ConnectionProfile.Builder builder = new ConnectionProfile.Builder();
        builder.addConnections(1,
            TransportRequestOptions.Type.BULK,
            TransportRequestOptions.Type.PING,
            TransportRequestOptions.Type.RECOVERY,
            TransportRequestOptions.Type.REG,
            TransportRequestOptions.Type.STATE);
        LISTED_NODES_PROFILE = builder.build();
    }
    
    TransportClientNodesService(TransportAddress address, TransportService transportService) {
        this.transportService = transportService;
        this.address = address;
    }

    void execute(Consumer<DiscoveryNode> consumer) {
        // check if the node connection has been made
        if (node == null) {
            // create note from transport address
            DiscoveryNode server = new DiscoveryNode("#transport#-" + UUIDs.randomBase64UUID(), address, emptyMap(), emptySet(),
                    Version.CURRENT.minimumCompatibilityVersion());

            try (Transport.Connection connection = PlainActionFuture
                    // block until the connection is made to avoid race conditions
                    .get(f -> transportService.openConnection(server, LISTED_NODES_PROFILE, f))) {

                final TransportFuture<ClusterStateResponse> handler = new TransportFuture<>(
                        new TransportResponseHandler<ClusterStateResponse>() {
                            @Override
                            public ClusterStateResponse read(StreamInput in) throws IOException {
                                return new ClusterStateResponse(in);
                            }

                            @Override
                            public void handleResponse(ClusterStateResponse response) {}

                            @Override
                            public void handleException(TransportException exp) {
                                throw new AssertionError("unexpected", exp);
                            }

                            @Override
                            public String executor() {
                                return ThreadPool.Names.SAME;
                            }
                        });


                transportService.sendRequest(connection, ClusterStateAction.NAME,
                        Requests.clusterStateRequest().clear().nodes(true).local(true),
                        TransportRequestOptions.builder().withType(TransportRequestOptions.Type.STATE).withTimeout(pingTimeout).build(),
                        handler);
                ClusterStateResponse response = handler.txGet();
                DiscoveryNodes nodes = response.getState().nodes();
                // local node returns null and data nodes returns a list - to simplify use the masternode
                node = nodes.getMasterNode();
            }

            if (transportService.nodeConnected(node) == false) {
                // block until the connection is made to avoid race conditions
                PlainActionFuture.get(f -> transportService.connectToNode(node, null, ActionListener.map(f, x -> null)));
            }
        }
        
        consumer.accept(node);
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        transportService.disconnectFromNode(node);
    }
}