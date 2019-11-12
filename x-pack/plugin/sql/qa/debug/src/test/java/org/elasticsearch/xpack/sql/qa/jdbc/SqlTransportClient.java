/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */

package org.elasticsearch.xpack.sql.qa.jdbc;

import io.netty.util.ThreadDeathWatcher;
import io.netty.util.concurrent.GlobalEventExecutor;

import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.ActionModule;
import org.elasticsearch.action.ActionRequest;
import org.elasticsearch.action.ActionResponse;
import org.elasticsearch.action.ActionType;
import org.elasticsearch.client.support.AbstractClient;
import org.elasticsearch.cluster.ClusterModule;
import org.elasticsearch.cluster.node.DiscoveryNode;
import org.elasticsearch.cluster.node.DiscoveryNodeRole;
import org.elasticsearch.common.SuppressForbidden;
import org.elasticsearch.common.UUIDs;
import org.elasticsearch.common.breaker.CircuitBreaker;
import org.elasticsearch.common.inject.Injector;
import org.elasticsearch.common.inject.ModulesBuilder;
import org.elasticsearch.common.io.stream.NamedWriteableRegistry;
import org.elasticsearch.common.network.NetworkModule;
import org.elasticsearch.common.network.NetworkService;
import org.elasticsearch.common.settings.Setting;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.settings.SettingsModule;
import org.elasticsearch.common.transport.TransportAddress;
import org.elasticsearch.common.util.BigArrays;
import org.elasticsearch.common.util.PageCacheRecycler;
import org.elasticsearch.common.xcontent.NamedXContentRegistry;
import org.elasticsearch.core.internal.io.IOUtils;
import org.elasticsearch.http.HttpServerTransport;
import org.elasticsearch.indices.IndicesModule;
import org.elasticsearch.indices.breaker.CircuitBreakerService;
import org.elasticsearch.license.XPackLicenseState;
import org.elasticsearch.node.InternalSettingsPreparer;
import org.elasticsearch.node.Node;
import org.elasticsearch.plugins.ActionPlugin;
import org.elasticsearch.plugins.NetworkPlugin;
import org.elasticsearch.plugins.Plugin;
import org.elasticsearch.plugins.PluginsService;
import org.elasticsearch.plugins.SearchPlugin;
import org.elasticsearch.search.SearchModule;
import org.elasticsearch.search.aggregations.matrix.MatrixAggregationPlugin;
import org.elasticsearch.threadpool.ExecutorBuilder;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.transport.Netty4Plugin;
import org.elasticsearch.transport.Transport;
import org.elasticsearch.transport.TransportService;
import org.elasticsearch.transport.TransportSettings;
import org.elasticsearch.xpack.sql.plugin.SqlPlugin;

import java.io.Closeable;
import java.lang.reflect.Field;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.util.stream.Collectors.toList;

class SqlTransportClient extends AbstractClient {

    private static XPackLicenseState LICENSE = new XPackLicenseState(Settings.EMPTY);

    static {
        setSystemPropertyIfUnset("io.netty.noUnsafe", Boolean.toString(true));
        setSystemPropertyIfUnset("io.netty.noKeySetOptimization", Boolean.toString(true));
        setSystemPropertyIfUnset("io.netty.recycler.maxCapacityPerThread", Integer.toString(0));
    }

    @SuppressForbidden(reason = "set system properties to configure Netty")
    private static void setSystemPropertyIfUnset(final String key, final String value) {
        final String currentValue = System.getProperty(key);
        if (currentValue == null) {
            System.setProperty(key, value);
        }
    }

    private static final class ClientTemplate {
        final Injector injector;
        private final NamedWriteableRegistry namedWriteableRegistry;
        private final TransportProxyClient proxy;

        private ClientTemplate(Injector injector, TransportProxyClient proxy, NamedWriteableRegistry namedWriteableRegistry) {
            this.injector = injector;
            this.proxy = proxy;
            this.namedWriteableRegistry = namedWriteableRegistry;
        }

        Settings getSettings() {
            return injector.getInstance(Settings.class);
        }

        ThreadPool getThreadPool() {
            return injector.getInstance(ThreadPool.class);
        }
    }

    private static PluginsService newPluginService(final Settings settings, Collection<Class<? extends Plugin>> plugins) {
        final Settings.Builder settingsBuilder = Settings.builder().put(TransportSettings.PING_SCHEDULE.getKey(), "5s")
                // enable by default the transport schedule ping interval
                .put(InternalSettingsPreparer.prepareSettings(settings)).put(NetworkService.NETWORK_SERVER.getKey(), false)
                // can be moved to "node"
                .put(CLIENT_TYPE_SETTING_S.getKey(), "transport");
        return new PluginsService(settingsBuilder.build(), null, null, null, plugins);
    }


    private static ClientTemplate clientTemplate() {
        //
        // Initial setup
        //

        // address
        TransportAddress transportAddress = new TransportAddress(InetAddress.getLoopbackAddress(), 9300);

        // plugins
        List<Class<? extends Plugin>> plugins = List.of(
                // network
                Netty4Plugin.class,
                // maybe?
                // ReindexPlugin.class, PercolatorPlugin.class, MustachePlugin.class, ParentJoinPlugin.class
                // sql
                MatrixAggregationPlugin.class, SqlPlugin.class);

        //
        // Actual initialization
        //

        DiscoveryNode.setPossibleRoles(DiscoveryNodeRole.BUILT_IN_ROLES);

        final Settings initialSettings = Settings.builder().put(Node.NODE_NAME_SETTING.getKey(), "_client_").build();
        final PluginsService pluginsService = newPluginService(initialSettings, plugins);
        final Settings settings = Settings.builder().put(initialSettings).put(pluginsService.updatedSettings())
                .put(TransportSettings.FEATURE_PREFIX + "." + "transport_client", true).build();

        final List<Closeable> resourcesToClose = new ArrayList<>();
        final ThreadPool threadPool = new ThreadPool(settings);
        resourcesToClose.add(() -> ThreadPool.terminate(threadPool, 10, TimeUnit.SECONDS));
        final NetworkService networkService = new NetworkService(Collections.emptyList());
        try {
            final List<Setting<?>> additionalSettings = new ArrayList<>(pluginsService.getPluginSettings());
            final List<String> additionalSettingsFilter = new ArrayList<>(pluginsService.getPluginSettingsFilter());
            for (final ExecutorBuilder<?> builder : threadPool.builders()) {
                additionalSettings.addAll(builder.getRegisteredSettings());
            }
            SettingsModule settingsModule = new SettingsModule(settings, additionalSettings, additionalSettingsFilter,
                    Collections.emptySet());

            SearchModule searchModule = new SearchModule(true, settings, pluginsService.filterPlugins(SearchPlugin.class));
            IndicesModule indicesModule = new IndicesModule(Collections.emptyList());
            List<NamedWriteableRegistry.Entry> entries = new ArrayList<>();
            entries.addAll(NetworkModule.getNamedWriteables());
            entries.addAll(searchModule.getNamedWriteables());
            entries.addAll(indicesModule.getNamedWriteables());
            entries.addAll(ClusterModule.getNamedWriteables());
            entries.addAll(pluginsService.filterPlugins(Plugin.class).stream().flatMap(p -> p.getNamedWriteables().stream())
                    .collect(Collectors.toList()));
            NamedWriteableRegistry namedWriteableRegistry = new NamedWriteableRegistry(entries);
            NamedXContentRegistry xContentRegistry = new NamedXContentRegistry(Stream
                    .of(searchModule.getNamedXContents().stream(),
                            pluginsService.filterPlugins(Plugin.class).stream().flatMap(p -> p.getNamedXContent().stream()))
                    .flatMap(Function.identity()).collect(toList()));

            ModulesBuilder modules = new ModulesBuilder();
            // plugin modules must be added here, before others or we can get crazy injection errors...
            //            for (Module pluginModule : pluginsService.createGuiceModules()) {
            //                modules.add(pluginModule);
            //            }


            //
            // Bind client and license
            //
            modules.add(ClientReference.createGuiceModules());

            modules.add(b -> b.bind(ThreadPool.class).toInstance(threadPool));
            ActionModule actionModule = new ActionModule(true, settings, null, settingsModule.getIndexScopedSettings(),
                    settingsModule.getClusterSettings(), settingsModule.getSettingsFilter(), threadPool,
                    pluginsService.filterPlugins(ActionPlugin.class), null, null, null, null);
            modules.add(actionModule);

            CircuitBreakerService circuitBreakerService = Node.createCircuitBreakerService(settingsModule.getSettings(),
                    settingsModule.getClusterSettings());
            resourcesToClose.add(circuitBreakerService);
            PageCacheRecycler pageCacheRecycler = new PageCacheRecycler(settings);
            BigArrays bigArrays = new BigArrays(pageCacheRecycler, circuitBreakerService, CircuitBreaker.REQUEST);
            modules.add(settingsModule);
            NetworkModule networkModule = new NetworkModule(settings, pluginsService.filterPlugins(NetworkPlugin.class), threadPool,
                    bigArrays, pageCacheRecycler, circuitBreakerService, namedWriteableRegistry, xContentRegistry, networkService, null);
            hack(networkModule);
            final Transport transport = networkModule.getTransportSupplier().get();
            final TransportService transportService = new TransportService(settings, transport, threadPool,
                    networkModule.getTransportInterceptor(), boundTransportAddress -> DiscoveryNode.createLocal(settings,
                            new TransportAddress(TransportAddress.META_ADDRESS, 0), UUIDs.randomBase64UUID()),
                    null, Collections.emptySet());
            modules.add((b -> {
                b.bind(BigArrays.class).toInstance(bigArrays);
                b.bind(PageCacheRecycler.class).toInstance(pageCacheRecycler);
                b.bind(PluginsService.class).toInstance(pluginsService);
                b.bind(CircuitBreakerService.class).toInstance(circuitBreakerService);
                b.bind(NamedWriteableRegistry.class).toInstance(namedWriteableRegistry);
                b.bind(Transport.class).toInstance(transport);
                b.bind(TransportService.class).toInstance(transportService);
                b.bind(NetworkService.class).toInstance(networkService);
            }));

            Injector injector = modules.createInjector();

            // construct the list of client actions
            final List<ActionPlugin> actionPlugins = pluginsService.filterPlugins(ActionPlugin.class);
            final List<ActionType<?>> clientActions = actionPlugins.stream().flatMap(p -> p.getClientActions().stream())
                    .collect(Collectors.toList());
            // add all the base actions
            final List<? extends ActionType<?>> baseActions = actionModule.getActions().values().stream()
                    .map(ActionPlugin.ActionHandler::getAction).collect(Collectors.toList());
            clientActions.addAll(baseActions);

            final TransportClientNodesService nodesService = new TransportClientNodesService(transportAddress, transportService);
            final TransportProxyClient proxy = new TransportProxyClient(settings, transportService, nodesService, clientActions);

            // Does not seem to be used
            //            List<LifecycleComponent> pluginLifecycleComponents = new ArrayList<>(
            //                    pluginsService.getGuiceServiceClasses().stream().map(injector::getInstance).collect(Collectors.toList()));
            //            resourcesToClose.addAll(pluginLifecycleComponents);

            transportService.start();
            transportService.acceptIncomingRequests();

            ClientTemplate transportClient = new ClientTemplate(injector, proxy, namedWriteableRegistry);
            resourcesToClose.clear();
            return transportClient;
        } finally {
            IOUtils.closeWhileHandlingException(resourcesToClose);
        }
    }

    private static void hack(NetworkModule module) {
        setField(module, "transportHttpFactories", new HashMap<String, Supplier<HttpServerTransport>>());

    }

    private static void setField(Object instance, String name, Object value) {
        try {
            Class<? extends Object> clazz = instance.getClass();
            Field field = clazz.getDeclaredField(name);
            field.setAccessible(true);
            field.set(instance, value);
        } catch (Exception ex) {
            throw new IllegalArgumentException(ex);
        }
    }

    private final ClientTemplate client;

    SqlTransportClient() {
        this(clientTemplate());
    }

    private SqlTransportClient(ClientTemplate template) {
        super(template.getSettings(), template.getThreadPool());
        this.client = template;
    }

    @Override
    protected <Request extends ActionRequest, Response extends ActionResponse> void doExecute(ActionType<Response> action,
            Request request, ActionListener<Response> listener) {
        client.proxy.execute(action, request, listener);
    }

    @Override
    public void close() {
        List<Closeable> closeables = new ArrayList<>();
        closeables.add(client.injector.getInstance(TransportService.class));
        closeables.add(() -> ThreadPool.terminate(client.injector.getInstance(ThreadPool.class), 10, TimeUnit.SECONDS));
        closeables.add(client.proxy);
        IOUtils.closeWhileHandlingException(closeables);

        // close netty
        if (NetworkModule.TRANSPORT_TYPE_SETTING.exists(settings) == false
                || NetworkModule.TRANSPORT_TYPE_SETTING.get(settings).equals(Netty4Plugin.NETTY_TRANSPORT_NAME)) {
            try {
                GlobalEventExecutor.INSTANCE.awaitInactivity(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            try {
                ThreadDeathWatcher.awaitInactivity(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}