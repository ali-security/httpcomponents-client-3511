/*
 * ====================================================================
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 * ====================================================================
 *
 * This software consists of voluntary contributions made by many
 * individuals on behalf of the Apache Software Foundation.  For more
 * information on the Apache Software Foundation, please see
 * <http://www.apache.org/>.
 *
 */

package org.apache.hc.client5.http.impl.async;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.hc.client5.http.AuthenticationStrategy;
import org.apache.hc.client5.http.EndpointInfo;
import org.apache.hc.client5.http.HttpRoute;
import org.apache.hc.client5.http.RouteTracker;
import org.apache.hc.client5.http.SchemePortResolver;
import org.apache.hc.client5.http.async.AsyncExecCallback;
import org.apache.hc.client5.http.async.AsyncExecChain;
import org.apache.hc.client5.http.async.AsyncExecChainHandler;
import org.apache.hc.client5.http.async.AsyncExecRuntime;
import org.apache.hc.client5.http.auth.AuthExchange;
import org.apache.hc.client5.http.auth.ChallengeType;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.auth.AuthCacheKeeper;
import org.apache.hc.client5.http.impl.auth.HttpAuthenticator;
import org.apache.hc.client5.http.impl.routing.BasicRouteDirector;
import org.apache.hc.client5.http.protocol.HttpClientContext;
import org.apache.hc.client5.http.routing.HttpRouteDirector;
import org.apache.hc.core5.annotation.Contract;
import org.apache.hc.core5.annotation.Internal;
import org.apache.hc.core5.annotation.ThreadingBehavior;
import org.apache.hc.core5.concurrent.CancellableDependency;
import org.apache.hc.core5.concurrent.FutureCallback;
import org.apache.hc.core5.http.EntityDetails;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.HttpException;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.http.HttpRequest;
import org.apache.hc.core5.http.HttpResponse;
import org.apache.hc.core5.http.HttpStatus;
import org.apache.hc.core5.http.HttpVersion;
import org.apache.hc.core5.http.Method;
import org.apache.hc.core5.http.message.BasicHttpRequest;
import org.apache.hc.core5.http.message.StatusLine;
import org.apache.hc.core5.http.nio.AsyncClientExchangeHandler;
import org.apache.hc.core5.http.nio.AsyncDataConsumer;
import org.apache.hc.core5.http.nio.AsyncEntityProducer;
import org.apache.hc.core5.http.nio.CapacityChannel;
import org.apache.hc.core5.http.nio.DataStreamChannel;
import org.apache.hc.core5.http.nio.RequestChannel;
import org.apache.hc.core5.http.protocol.HttpContext;
import org.apache.hc.core5.http.protocol.HttpProcessor;
import org.apache.hc.core5.util.Args;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Request execution handler in the asynchronous request execution chain
 * that is responsible for establishing connection to the target
 * origin server as specified by the current connection route.
 *
 * @since 5.0
 */
@Contract(threading = ThreadingBehavior.STATELESS)
@Internal
public final class AsyncConnectExec implements AsyncExecChainHandler {

    private static final Logger LOG = LoggerFactory.getLogger(AsyncConnectExec.class);

    private final HttpProcessor proxyHttpProcessor;
    private final AuthenticationStrategy proxyAuthStrategy;
    private final HttpAuthenticator authenticator;
    private final AuthCacheKeeper authCacheKeeper;
    private final HttpRouteDirector routeDirector;

    public AsyncConnectExec(
            final HttpProcessor proxyHttpProcessor,
            final AuthenticationStrategy proxyAuthStrategy,
            final SchemePortResolver schemePortResolver,
            final boolean authCachingDisabled) {
        Args.notNull(proxyHttpProcessor, "Proxy HTTP processor");
        Args.notNull(proxyAuthStrategy, "Proxy authentication strategy");
        this.proxyHttpProcessor = proxyHttpProcessor;
        this.proxyAuthStrategy  = proxyAuthStrategy;
        this.authenticator = new HttpAuthenticator();
        this.authCacheKeeper = authCachingDisabled ? null : new AuthCacheKeeper(schemePortResolver);
        this.routeDirector = BasicRouteDirector.INSTANCE;
    }

    static class State {

        State(final HttpRoute route) {
            tracker = new RouteTracker(route);
        }

        final RouteTracker tracker;

        volatile boolean challenged;
        volatile HttpResponse response;
        volatile boolean tunnelRefused;

    }

    @Override
    public void execute(
            final HttpRequest request,
            final AsyncEntityProducer entityProducer,
            final AsyncExecChain.Scope scope,
            final AsyncExecChain chain,
            final AsyncExecCallback asyncExecCallback) throws HttpException, IOException {
        Args.notNull(request, "HTTP request");
        Args.notNull(scope, "Scope");

        final String exchangeId = scope.exchangeId;
        final HttpRoute route = scope.route;
        final CancellableDependency cancellableDependency = scope.cancellableDependency;
        final HttpClientContext clientContext = scope.clientContext;
        final AsyncExecRuntime execRuntime = scope.execRuntime;
        final State state = new State(route);

        if (!execRuntime.isEndpointAcquired()) {
            final Object userToken = clientContext.getUserToken();
            if (LOG.isDebugEnabled()) {
                LOG.debug("{} acquiring connection with route {}", exchangeId, route);
            }
            cancellableDependency.setDependency(execRuntime.acquireEndpoint(
                    exchangeId, route, userToken, clientContext, new FutureCallback<AsyncExecRuntime>() {

                        @Override
                        public void completed(final AsyncExecRuntime execRuntime) {
                            if (execRuntime.isEndpointConnected()) {
                                try {
                                    chain.proceed(request, entityProducer, scope, asyncExecCallback);
                                } catch (final HttpException | IOException ex) {
                                    asyncExecCallback.failed(ex);
                                }
                            } else {
                                proceedToNextHop(state, request, entityProducer, scope, chain, asyncExecCallback);
                            }
                        }

                        @Override
                        public void failed(final Exception ex) {
                            asyncExecCallback.failed(ex);
                        }

                        @Override
                        public void cancelled() {
                            asyncExecCallback.failed(new InterruptedIOException());
                        }

                    }));
        } else {
            if (execRuntime.isEndpointConnected()) {
                proceedConnected(request, entityProducer, scope, chain, asyncExecCallback);
            } else {
                proceedToNextHop(state, request, entityProducer, scope, chain, asyncExecCallback);
            }
        }

    }

    private void proceedToNextHop(
            final State state,
            final HttpRequest request,
            final AsyncEntityProducer entityProducer,
            final AsyncExecChain.Scope scope,
            final AsyncExecChain chain,
            final AsyncExecCallback asyncExecCallback) {
        try {
            doProceedToNextHop(state, request, entityProducer, scope, chain, asyncExecCallback);
        } catch (final RuntimeException ex) {
            asyncExecCallback.failed(ex);
        }
    }

    private void doProceedToNextHop(
            final State state,
            final HttpRequest request,
            final AsyncEntityProducer entityProducer,
            final AsyncExecChain.Scope scope,
            final AsyncExecChain chain,
            final AsyncExecCallback asyncExecCallback) {
        final RouteTracker tracker = state.tracker;
        final String exchangeId = scope.exchangeId;
        final HttpRoute route = scope.route;
        final AsyncExecRuntime execRuntime = scope.execRuntime;
        final CancellableDependency operation = scope.cancellableDependency;
        final HttpClientContext clientContext = scope.clientContext;

        final HttpRoute fact = tracker.toRoute();
        final int step = routeDirector.nextStep(route, fact);

        switch (step) {
            case HttpRouteDirector.CONNECT_TARGET:
                operation.setDependency(execRuntime.connectEndpoint(clientContext, new FutureCallback<AsyncExecRuntime>() {

                    @Override
                    public void completed(final AsyncExecRuntime execRuntime) {
                        tracker.connectTarget(route.isSecure());
                        if (LOG.isDebugEnabled()) {
                            LOG.debug("{} connected to target", exchangeId);
                        }
                        proceedToNextHop(state, request, entityProducer, scope, chain, asyncExecCallback);
                    }

                    @Override
                    public void failed(final Exception ex) {
                        asyncExecCallback.failed(ex);
                    }

                    @Override
                    public void cancelled() {
                        asyncExecCallback.failed(new InterruptedIOException());
                    }

                }));
                break;

            case HttpRouteDirector.CONNECT_PROXY:
                operation.setDependency(execRuntime.connectEndpoint(clientContext, new FutureCallback<AsyncExecRuntime>() {

                    @Override
                    public void completed(final AsyncExecRuntime execRuntime) {
                        final HttpHost proxy  = route.getProxyHost();
                        tracker.connectProxy(proxy, route.isSecure() && !route.isTunnelled());
                        if (LOG.isDebugEnabled()) {
                            LOG.debug("{} connected to proxy", exchangeId);
                        }
                        proceedToNextHop(state, request, entityProducer, scope, chain, asyncExecCallback);
                    }

                    @Override
                    public void failed(final Exception ex) {
                        asyncExecCallback.failed(ex);
                    }

                    @Override
                    public void cancelled() {
                        asyncExecCallback.failed(new InterruptedIOException());
                    }

                }));
                break;

            case HttpRouteDirector.TUNNEL_TARGET:
                final HttpHost proxy = route.getProxyHost();
                final HttpHost target = route.getTargetHost();
                if (LOG.isDebugEnabled()) {
                    LOG.debug("{} create tunnel", exchangeId);
                }
                createTunnel(state, proxy, target, scope, new AsyncExecCallback() {

                    @Override
                    public AsyncDataConsumer handleResponse(final HttpResponse response, final EntityDetails entityDetails) throws HttpException, IOException {
                        return asyncExecCallback.handleResponse(response, entityDetails);
                    }

                    @Override
                    public void handleInformationResponse(final HttpResponse response) throws HttpException, IOException {
                        asyncExecCallback.handleInformationResponse(response);
                    }

                    @Override
                    public void completed() {
                        if (!execRuntime.isEndpointConnected()) {
                            // Remote endpoint disconnected. Need to start over
                            if (LOG.isDebugEnabled()) {
                                LOG.debug("{} proxy disconnected", exchangeId);
                            }
                            state.tracker.reset();
                        }
                        if (state.challenged) {
                            if (LOG.isDebugEnabled()) {
                                LOG.debug("{} proxy authentication required", exchangeId);
                            }
                            proceedToNextHop(state, request, entityProducer, scope, chain, asyncExecCallback);
                        } else {
                            if (state.tunnelRefused) {
                                if (LOG.isDebugEnabled()) {
                                    LOG.debug("{} tunnel refused", exchangeId);
                                }
                                asyncExecCallback.completed();
                            } else {
                                if (LOG.isDebugEnabled()) {
                                    LOG.debug("{} tunnel to target created", exchangeId);
                                }
                                tracker.tunnelTarget(false);
                                proceedToNextHop(state, request, entityProducer, scope, chain, asyncExecCallback);
                            }
                        }
                    }

                    @Override
                    public void failed(final Exception cause) {
                        execRuntime.markConnectionNonReusable();
                        asyncExecCallback.failed(cause);
                    }

                });
                break;

            case HttpRouteDirector.TUNNEL_PROXY:
                // The most simple example for this case is a proxy chain
                // of two proxies, where P1 must be tunnelled to P2.
                // route: Source -> P1 -> P2 -> Target (3 hops)
                // fact:  Source -> P1 -> Target       (2 hops)
                asyncExecCallback.failed(new HttpException("Proxy chains are not supported"));
                break;

            case HttpRouteDirector.LAYER_PROTOCOL:
                execRuntime.upgradeTls(clientContext, new FutureCallback<AsyncExecRuntime>() {

                    @Override
                    public void completed(final AsyncExecRuntime asyncExecRuntime) {
                        if (LOG.isDebugEnabled()) {
                            LOG.debug("{} upgraded to TLS", exchangeId);
                        }
                        tracker.layerProtocol(route.isSecure());
                        proceedToNextHop(state, request, entityProducer, scope, chain, asyncExecCallback);
                    }

                    @Override
                    public void failed(final Exception ex) {
                        asyncExecCallback.failed(ex);
                    }

                    @Override
                    public void cancelled() {
                        asyncExecCallback.failed(new InterruptedIOException());
                    }

                });
                break;

            case HttpRouteDirector.UNREACHABLE:
                asyncExecCallback.failed(new HttpException("Unable to establish route: " +
                        "planned = " + route + "; current = " + fact));
                break;

            case HttpRouteDirector.COMPLETE:
                if (LOG.isDebugEnabled()) {
                    LOG.debug("{} route fully established", exchangeId);
                }
                proceedConnected(request, entityProducer, scope, chain, asyncExecCallback);
                break;

            default:
                throw new IllegalStateException("Unknown step indicator "  + step + " from RouteDirector.");
        }
    }

    private void createTunnel(
            final State state,
            final HttpHost proxy,
            final HttpHost nextHop,
            final AsyncExecChain.Scope scope,
            final AsyncExecCallback asyncExecCallback) {

        final CancellableDependency operation = scope.cancellableDependency;
        final HttpClientContext clientContext = scope.clientContext;
        final AsyncExecRuntime execRuntime = scope.execRuntime;
        final String exchangeId = scope.exchangeId;

        final AuthExchange proxyAuthExchange = proxy != null ? clientContext.getAuthExchange(proxy) : new AuthExchange();

        if (authCacheKeeper != null) {
            authCacheKeeper.loadPreemptively(proxy, null, proxyAuthExchange, clientContext);
        }

        final AsyncClientExchangeHandler internalExchangeHandler = new AsyncClientExchangeHandler() {

            private final AtomicReference<AsyncDataConsumer> entityConsumerRef = new AtomicReference<>();

            @Override
            public void releaseResources() {
                final AsyncDataConsumer entityConsumer = entityConsumerRef.getAndSet(null);
                if (entityConsumer != null) {
                    entityConsumer.releaseResources();
                }
            }

            @Override
            public void failed(final Exception cause) {
                final AsyncDataConsumer entityConsumer = entityConsumerRef.getAndSet(null);
                if (entityConsumer != null) {
                    entityConsumer.releaseResources();
                }
                asyncExecCallback.failed(cause);
            }

            @Override
            public void cancel() {
                failed(new InterruptedIOException());
            }

            @Override
            public void produceRequest(final RequestChannel requestChannel,
                                       final HttpContext httpContext) throws HttpException, IOException {
                final HttpRequest connect = new BasicHttpRequest(Method.CONNECT, nextHop, nextHop.toHostString());
                connect.setVersion(HttpVersion.HTTP_1_1);

                proxyHttpProcessor.process(connect, null, clientContext);
                authenticator.addAuthResponse(proxy, ChallengeType.PROXY, connect, proxyAuthExchange, clientContext);

                requestChannel.sendRequest(connect, null, clientContext);
            }

            @Override
            public void produce(final DataStreamChannel dataStreamChannel) throws IOException {
            }

            @Override
            public int available() {
                return 0;
            }

            @Override
            public void consumeInformation(final HttpResponse httpResponse,
                                           final HttpContext httpContext) throws HttpException, IOException {
            }

            @Override
            public void consumeResponse(final HttpResponse response,
                                        final EntityDetails entityDetails,
                                        final HttpContext httpContext) throws HttpException, IOException {
                clientContext.setResponse(response);
                proxyHttpProcessor.process(response, entityDetails, clientContext);

                final int status = response.getCode();
                if (status < HttpStatus.SC_SUCCESS) {
                    throw new HttpException("Unexpected response to CONNECT request: " + new StatusLine(response));
                }

                if (needAuthentication(proxyAuthExchange, proxy, response, clientContext)) {
                    state.challenged = true;
                } else {
                    state.challenged = false;
                    if (status >= HttpStatus.SC_REDIRECTION) {
                        state.tunnelRefused = true;
                        entityConsumerRef.set(asyncExecCallback.handleResponse(response, entityDetails));
                    } else if (status == HttpStatus.SC_OK) {
                        asyncExecCallback.completed();
                    } else {
                        throw new HttpException("Unexpected response to CONNECT request: " + new StatusLine(response));
                    }
                }
            }

            @Override
            public void updateCapacity(final CapacityChannel capacityChannel) throws IOException {
                final AsyncDataConsumer entityConsumer = entityConsumerRef.get();
                if (entityConsumer != null) {
                    entityConsumer.updateCapacity(capacityChannel);
                } else {
                    capacityChannel.update(Integer.MAX_VALUE);
                }
            }

            @Override
            public void consume(final ByteBuffer src) throws IOException {
                final AsyncDataConsumer entityConsumer = entityConsumerRef.get();
                if (entityConsumer != null) {
                    entityConsumer.consume(src);
                }
            }

            @Override
            public void streamEnd(final List<? extends Header> trailers) throws HttpException, IOException {
                final AsyncDataConsumer entityConsumer = entityConsumerRef.getAndSet(null);
                if (entityConsumer != null) {
                    entityConsumer.streamEnd(trailers);
                }
                asyncExecCallback.completed();
            }

        };

        if (LOG.isDebugEnabled()) {
            operation.setDependency(execRuntime.execute(
                    exchangeId,
                    new LoggingAsyncClientExchangeHandler(LOG, exchangeId, internalExchangeHandler),
                    clientContext));
        } else {
            operation.setDependency(execRuntime.execute(exchangeId, internalExchangeHandler, clientContext));
        }

    }

    private boolean needAuthentication(
            final AuthExchange proxyAuthExchange,
            final HttpHost proxy,
            final HttpResponse response,
            final HttpClientContext context) {
        final RequestConfig config = context.getRequestConfigOrDefault();
        if (config.isAuthenticationEnabled()) {
            final boolean proxyAuthRequested = authenticator.isChallenged(proxy, ChallengeType.PROXY, response, proxyAuthExchange, context);

            if (authCacheKeeper != null) {
                if (proxyAuthRequested) {
                    authCacheKeeper.updateOnChallenge(proxy, null, proxyAuthExchange, context);
                } else {
                    authCacheKeeper.updateOnNoChallenge(proxy, null, proxyAuthExchange, context);
                }
            }

            if (proxyAuthRequested) {
                final boolean updated = authenticator.updateAuthState(proxy, ChallengeType.PROXY, response,
                        proxyAuthStrategy, proxyAuthExchange, context);

                if (authCacheKeeper != null) {
                    authCacheKeeper.updateOnResponse(proxy, null, proxyAuthExchange, context);
                }

                return updated;
            }
        }
        return false;
    }

    private void proceedConnected(
            final HttpRequest request,
            final AsyncEntityProducer entityProducer,
            final AsyncExecChain.Scope scope,
            final AsyncExecChain chain,
            final AsyncExecCallback asyncExecCallback) {
        final AsyncExecRuntime execRuntime = scope.execRuntime;
        final HttpClientContext clientContext = scope.clientContext;
        final EndpointInfo endpointInfo = execRuntime.getEndpointInfo();
        if (endpointInfo != null) {
            clientContext.setProtocolVersion(endpointInfo.getProtocol());
            clientContext.setSSLSession(endpointInfo.getSslSession());
        }
        try {
            chain.proceed(request, entityProducer, scope, asyncExecCallback);
        } catch (final HttpException | IOException ex) {
            asyncExecCallback.failed(ex);
        }
    }

}
