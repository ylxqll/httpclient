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

package org.apache.http.impl.client.exec;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.concurrent.TimeUnit;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.http.ConnectionReuseStrategy;
import org.apache.http.Header;
import org.apache.http.HttpEntity;
import org.apache.http.HttpException;
import org.apache.http.HttpHost;
import org.apache.http.HttpRequest;
import org.apache.http.HttpResponse;
import org.apache.http.ProtocolException;
import org.apache.http.ProtocolVersion;
import org.apache.http.annotation.ThreadSafe;
import org.apache.http.auth.AuthProtocolState;
import org.apache.http.auth.AuthState;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.AuthenticationStrategy;
import org.apache.http.client.NonRepeatableRequestException;
import org.apache.http.client.UserTokenHandler;
import org.apache.http.client.methods.AbortableHttpRequest;
import org.apache.http.client.params.HttpClientParams;
import org.apache.http.client.protocol.ClientContext;
import org.apache.http.client.utils.URIUtils;
import org.apache.http.conn.BasicManagedEntity;
import org.apache.http.conn.ClientConnectionManager;
import org.apache.http.conn.ClientConnectionRequest;
import org.apache.http.conn.ConnectionKeepAliveStrategy;
import org.apache.http.conn.ManagedClientConnection;
import org.apache.http.conn.routing.BasicRouteDirector;
import org.apache.http.conn.routing.HttpRoute;
import org.apache.http.conn.routing.HttpRouteDirector;
import org.apache.http.conn.scheme.Scheme;
import org.apache.http.entity.BufferedHttpEntity;
import org.apache.http.impl.auth.BasicScheme;
import org.apache.http.impl.client.HttpAuthenticator;
import org.apache.http.impl.client.TunnelRefusedException;
import org.apache.http.impl.conn.ConnectionShutdownException;
import org.apache.http.message.BasicHttpRequest;
import org.apache.http.params.HttpConnectionParams;
import org.apache.http.params.HttpParams;
import org.apache.http.params.HttpProtocolParams;
import org.apache.http.protocol.ExecutionContext;
import org.apache.http.protocol.HttpContext;
import org.apache.http.protocol.HttpProcessor;
import org.apache.http.protocol.HttpRequestExecutor;
import org.apache.http.util.EntityUtils;

/**
 * <p>
 * The following parameters can be used to customize the behavior of this
 * class:
 * <ul>
 *  <li>{@link org.apache.http.params.CoreProtocolPNames#PROTOCOL_VERSION}</li>
 *  <li>{@link org.apache.http.params.CoreProtocolPNames#STRICT_TRANSFER_ENCODING}</li>
 *  <li>{@link org.apache.http.params.CoreProtocolPNames#HTTP_ELEMENT_CHARSET}</li>
 *  <li>{@link org.apache.http.params.CoreProtocolPNames#USE_EXPECT_CONTINUE}</li>
 *  <li>{@link org.apache.http.params.CoreProtocolPNames#WAIT_FOR_CONTINUE}</li>
 *  <li>{@link org.apache.http.params.CoreProtocolPNames#USER_AGENT}</li>
 *  <li>{@link org.apache.http.params.CoreConnectionPNames#SOCKET_BUFFER_SIZE}</li>
 *  <li>{@link org.apache.http.params.CoreConnectionPNames#MAX_LINE_LENGTH}</li>
 *  <li>{@link org.apache.http.params.CoreConnectionPNames#MAX_HEADER_COUNT}</li>
 *  <li>{@link org.apache.http.params.CoreConnectionPNames#SO_TIMEOUT}</li>
 *  <li>{@link org.apache.http.params.CoreConnectionPNames#SO_LINGER}</li>
 *  <li>{@link org.apache.http.params.CoreConnectionPNames#SO_REUSEADDR}</li>
 *  <li>{@link org.apache.http.params.CoreConnectionPNames#TCP_NODELAY}</li>
 *  <li>{@link org.apache.http.params.CoreConnectionPNames#CONNECTION_TIMEOUT}</li>
 *  <li>{@link org.apache.http.params.CoreConnectionPNames#STALE_CONNECTION_CHECK}</li>
 *  <li>{@link org.apache.http.conn.params.ConnRoutePNames#FORCED_ROUTE}</li>
 *  <li>{@link org.apache.http.conn.params.ConnRoutePNames#LOCAL_ADDRESS}</li>
 *  <li>{@link org.apache.http.conn.params.ConnRoutePNames#DEFAULT_PROXY}</li>
 *  <li>{@link org.apache.http.cookie.params.CookieSpecPNames#DATE_PATTERNS}</li>
 *  <li>{@link org.apache.http.cookie.params.CookieSpecPNames#SINGLE_COOKIE_HEADER}</li>
 *  <li>{@link org.apache.http.auth.params.AuthPNames#CREDENTIAL_CHARSET}</li>
 *  <li>{@link org.apache.http.client.params.ClientPNames#COOKIE_POLICY}</li>
 *  <li>{@link org.apache.http.client.params.ClientPNames#HANDLE_AUTHENTICATION}</li>
 *  <li>{@link org.apache.http.client.params.ClientPNames#VIRTUAL_HOST}</li>
 *  <li>{@link org.apache.http.client.params.ClientPNames#DEFAULT_HOST}</li>
 *  <li>{@link org.apache.http.client.params.ClientPNames#DEFAULT_HEADERS}</li>
 *  <li>{@link org.apache.http.client.params.ClientPNames#CONN_MANAGER_TIMEOUT}</li>
 * </ul>
 *
 * @since 4.3
 */
@ThreadSafe
public class MainRequestExecutor implements HttpClientRequestExecutor {

    private final Log log = LogFactory.getLog(getClass());

    private final ClientConnectionManager connManager;
    private final ConnectionReuseStrategy reuseStrategy;
    private final ConnectionKeepAliveStrategy keepAliveStrategy;
    private final HttpRequestExecutor requestExec;
    private final HttpProcessor httpProcessor;
    private final AuthenticationStrategy targetAuthStrategy;
    private final AuthenticationStrategy proxyAuthStrategy;
    private final HttpAuthenticator authenticator;
    private final UserTokenHandler userTokenHandler;
    private final HttpParams params;

    public MainRequestExecutor(
            final HttpProcessor httpProcessor,
            final ClientConnectionManager connManager,
            final ConnectionReuseStrategy reuseStrategy,
            final ConnectionKeepAliveStrategy keepAliveStrategy,
            final AuthenticationStrategy targetAuthStrategy,
            final AuthenticationStrategy proxyAuthStrategy,
            final UserTokenHandler userTokenHandler,
            final HttpParams params) {
        if (httpProcessor == null) {
            throw new IllegalArgumentException("HTTP protocol processor may not be null");
        }
        if (connManager == null) {
            throw new IllegalArgumentException("Client connection manager may not be null");
        }
        if (reuseStrategy == null) {
            throw new IllegalArgumentException("Connection reuse strategy may not be null");
        }
        if (keepAliveStrategy == null) {
            throw new IllegalArgumentException("Connection keep alive strategy may not be null");
        }
        if (targetAuthStrategy == null) {
            throw new IllegalArgumentException("Target authentication strategy may not be null");
        }
        if (proxyAuthStrategy == null) {
            throw new IllegalArgumentException("Proxy authentication strategy may not be null");
        }
        if (userTokenHandler == null) {
            throw new IllegalArgumentException("User token handler may not be null");
        }
        if (params == null) {
            throw new IllegalArgumentException("HTTP parameters may not be null");
        }
        this.authenticator      = new HttpAuthenticator();
        this.requestExec        = new HttpRequestExecutor();
        this.httpProcessor      = httpProcessor;
        this.connManager        = connManager;
        this.reuseStrategy      = reuseStrategy;
        this.keepAliveStrategy  = keepAliveStrategy;
        this.targetAuthStrategy = targetAuthStrategy;
        this.proxyAuthStrategy  = proxyAuthStrategy;
        this.userTokenHandler   = userTokenHandler;
        this.params             = params;
    }

    private void rewriteRequestURI(
            final HttpRequestWrapper request,
            final HttpRoute route) throws ProtocolException {
        try {
            URI uri = request.getURI();
            if (route.getProxyHost() != null && !route.isTunnelled()) {
                // Make sure the request URI is absolute
                if (!uri.isAbsolute()) {
                    HttpHost target = route.getTargetHost();
                    uri = URIUtils.rewriteURI(uri, target, true);
                } else {
                    uri = URIUtils.rewriteURI(uri);
                }
            } else {
                // Make sure the request URI is relative
                if (uri.isAbsolute()) {
                    uri = URIUtils.rewriteURI(uri, null);
                } else {
                    uri = URIUtils.rewriteURI(uri);
                }
            }
            request.setURI(uri);
        } catch (URISyntaxException ex) {
            throw new ProtocolException("Invalid URI: " +
                    request.getRequestLine().getUri(), ex);
        }
    }

    public HttpResponse execute(
            final HttpRoute route,
            final HttpRequestWrapper request,
            final HttpContext context) throws HttpException, IOException {

        AuthState targetAuthState = (AuthState) context.getAttribute(ClientContext.TARGET_AUTH_STATE);
        if (targetAuthState == null) {
            targetAuthState = new AuthState();
        }
        AuthState proxyAuthState = (AuthState) context.getAttribute(ClientContext.PROXY_AUTH_STATE);
        if (proxyAuthState == null) {
            proxyAuthState = new AuthState();
        }

        HttpHost target = route.getTargetHost();
        HttpHost proxy = route.getProxyHost();

        // Save original request headers
        Header[] origheaders = request.getAllHeaders();

        // Re-write request URI if needed
        rewriteRequestURI(request, route);

        HttpHost virtualHost = request.getVirtualHost();
        // HTTPCLIENT-1092 - add the port if necessary
        if (virtualHost != null && virtualHost.getPort() == -1) {
            int port = target.getPort();
            if (port != -1){
                virtualHost = new HttpHost(virtualHost.getHostName(), port, virtualHost.getSchemeName());
            }
        }

        context.setAttribute(ExecutionContext.HTTP_TARGET_HOST, virtualHost != null ? virtualHost : target);
        context.setAttribute(ExecutionContext.HTTP_PROXY_HOST, proxy);

        Object userToken = context.getAttribute(ClientContext.USER_TOKEN);

        ManagedClientConnection managedConn = null;

        boolean reuse = false;
        try {
            HttpResponse response = null;
            for (int execCount = 1;; execCount++) {

                if (execCount > 1 && !request.isRepeatable()) {
                    throw new NonRepeatableRequestException("Cannot retry request " +
                            "with a non-repeatable request entity.");
                }

                // Allocate connection if needed
                if (managedConn == null) {
                    ClientConnectionRequest connRequest = connManager.requestConnection(
                            route, userToken);
                    if (request instanceof AbortableHttpRequest) {
                        ((AbortableHttpRequest) request).setConnectionRequest(connRequest);
                    }

                    long timeout = HttpClientParams.getConnectionManagerTimeout(params);
                    try {
                        managedConn = connRequest.getConnection(timeout, TimeUnit.MILLISECONDS);
                    } catch(InterruptedException interrupted) {
                        InterruptedIOException iox = new InterruptedIOException();
                        iox.initCause(interrupted);
                        throw iox;
                    }

                    if (HttpConnectionParams.isStaleCheckingEnabled(params)) {
                        // validate connection
                        if (managedConn.isOpen()) {
                            this.log.debug("Stale connection check");
                            if (managedConn.isStale()) {
                                this.log.debug("Stale connection detected");
                                managedConn.close();
                            }
                        }
                    }
                }

                if (request instanceof AbortableHttpRequest) {
                    ((AbortableHttpRequest) request).setReleaseTrigger(managedConn);
                }

                if (!managedConn.isOpen()) {
                    managedConn.open(route, context, params);
                } else {
                    managedConn.setSocketTimeout(HttpConnectionParams.getSoTimeout(params));
                }

                context.setAttribute(ExecutionContext.HTTP_CONNECTION, managedConn);

                try {
                    establishRoute(proxyAuthState, managedConn, route, context);
                } catch (TunnelRefusedException ex) {
                    if (this.log.isDebugEnabled()) {
                        this.log.debug(ex.getMessage());
                    }
                    response = ex.getResponse();
                    break;
                }

                String userinfo = request.getURI().getUserInfo();
                if (userinfo != null) {
                    targetAuthState.update(
                            new BasicScheme(), new UsernamePasswordCredentials(userinfo));
                }

                // Run request protocol interceptors
                requestExec.preProcess(request, httpProcessor, context);

                response = requestExec.execute(request, managedConn, context);
                response.setParams(params);

                // Run response protocol interceptors
                requestExec.postProcess(response, httpProcessor, context);

                // The connection is in or can be brought to a re-usable state.
                reuse = reuseStrategy.keepAlive(response, context);
                if (reuse) {
                    // Set the idle duration of this connection
                    long duration = keepAliveStrategy.getKeepAliveDuration(response, context);
                    if (this.log.isDebugEnabled()) {
                        String s;
                        if (duration > 0) {
                            s = "for " + duration + " " + TimeUnit.MILLISECONDS;
                        } else {
                            s = "indefinitely";
                        }
                        this.log.debug("Connection can be kept alive " + s);
                    }
                    managedConn.setIdleDuration(duration, TimeUnit.MILLISECONDS);
                }

                if (needAuthentication(
                        targetAuthState, proxyAuthState, route, request, response, context)) {
                    if (reuse) {
                        // Make sure the response body is fully consumed, if present
                        HttpEntity entity = response.getEntity();
                        EntityUtils.consume(entity);
                        // entity consumed above is not an auto-release entity,
                        // need to mark the connection re-usable explicitly
                        managedConn.markReusable();
                    } else {
                        managedConn.close();
                        if (proxyAuthState.getState() == AuthProtocolState.SUCCESS
                                && proxyAuthState.getAuthScheme() != null
                                && proxyAuthState.getAuthScheme().isConnectionBased()) {
                            this.log.debug("Resetting proxy auth state");
                            proxyAuthState.reset();
                        }
                        if (targetAuthState.getState() == AuthProtocolState.SUCCESS
                                && targetAuthState.getAuthScheme() != null
                                && targetAuthState.getAuthScheme().isConnectionBased()) {
                            this.log.debug("Resetting target auth state");
                            targetAuthState.reset();
                        }
                    }
                    // discard all headers and retry request
                    request.setHeaders(origheaders);
                } else {
                    break;
                }
            }

            if (managedConn != null) {
                if (userToken == null) {
                    userToken = userTokenHandler.getUserToken(context);
                    context.setAttribute(ClientContext.USER_TOKEN, userToken);
                }
                if (userToken != null) {
                    managedConn.setState(userToken);
                }
            }

            // check for entity, release connection if possible
            HttpEntity entity = response.getEntity();
            if (entity == null || !entity.isStreaming()) {
                // connection not needed and (assumed to be) in re-usable state
                if (reuse) {
                    managedConn.markReusable();
                }
                try {
                    managedConn.releaseConnection();
                } catch(IOException ex) {
                    this.log.debug("IOException releasing connection", ex);
                }
                managedConn = null;
            } else {
                // install an auto-release entity
                entity = new BasicManagedEntity(entity, managedConn, reuse);
                response.setEntity(entity);
            }

            return response;

        } catch (ConnectionShutdownException ex) {
            InterruptedIOException ioex = new InterruptedIOException(
                    "Connection has been shut down");
            ioex.initCause(ex);
            throw ioex;
        } catch (HttpException ex) {
            abortConnection(managedConn);
            throw ex;
        } catch (IOException ex) {
            abortConnection(managedConn);
            throw ex;
        } catch (RuntimeException ex) {
            abortConnection(managedConn);
            throw ex;
        }
    }

    /**
     * Establishes the target route.
     */
    private void establishRoute(
            final AuthState proxyAuthState,
            final ManagedClientConnection managedConn,
            final HttpRoute route,
            final HttpContext context) throws HttpException, IOException {

        HttpRouteDirector rowdy = new BasicRouteDirector();
        int step;
        do {
            HttpRoute fact = managedConn.getRoute();
            step = rowdy.nextStep(route, fact);

            switch (step) {

            case HttpRouteDirector.CONNECT_TARGET:
            case HttpRouteDirector.CONNECT_PROXY:
                managedConn.open(route, context, this.params);
                break;

            case HttpRouteDirector.TUNNEL_TARGET: {
                boolean secure = createTunnelToTarget(proxyAuthState, managedConn, route, context);
                this.log.debug("Tunnel to target created.");
                managedConn.tunnelTarget(secure, this.params);
            }   break;

            case HttpRouteDirector.TUNNEL_PROXY: {
                // The most simple example for this case is a proxy chain
                // of two proxies, where P1 must be tunnelled to P2.
                // route: Source -> P1 -> P2 -> Target (3 hops)
                // fact:  Source -> P1 -> Target       (2 hops)
                final int hop = fact.getHopCount()-1; // the hop to establish
                boolean secure = createTunnelToProxy(route, hop, context);
                this.log.debug("Tunnel to proxy created.");
                managedConn.tunnelProxy(route.getHopTarget(hop),
                                        secure, this.params);
            }   break;


            case HttpRouteDirector.LAYER_PROTOCOL:
                managedConn.layerProtocol(context, this.params);
                break;

            case HttpRouteDirector.UNREACHABLE:
                throw new HttpException("Unable to establish route: " +
                        "planned = " + route + "; current = " + fact);
            case HttpRouteDirector.COMPLETE:
                // do nothing
                break;
            default:
                throw new IllegalStateException("Unknown step indicator "
                        + step + " from RouteDirector.");
            }

        } while (step > HttpRouteDirector.COMPLETE);
    }

    /**
     * Creates a tunnel to the target server.
     * The connection must be established to the (last) proxy.
     * A CONNECT request for tunnelling through the proxy will
     * be created and sent, the response received and checked.
     * This method does <i>not</i> update the connection with
     * information about the tunnel, that is left to the caller.
     */
    private boolean createTunnelToTarget(
            final AuthState proxyAuthState,
            final ManagedClientConnection managedConn,
            final HttpRoute route,
            final HttpContext context) throws HttpException, IOException {

        HttpHost proxy = route.getProxyHost();
        HttpResponse response = null;

        for (;;) {
            if (!managedConn.isOpen()) {
                managedConn.open(route, context, this.params);
            }

            HttpRequest connect = createConnectRequest(route, context);
            connect.setParams(this.params);

            // Populate the execution context
            context.setAttribute(ExecutionContext.HTTP_REQUEST, connect);

            this.requestExec.preProcess(connect, this.httpProcessor, context);

            response = this.requestExec.execute(connect, managedConn, context);

            response.setParams(this.params);
            this.requestExec.postProcess(response, this.httpProcessor, context);

            int status = response.getStatusLine().getStatusCode();
            if (status < 200) {
                throw new HttpException("Unexpected response to CONNECT request: " +
                        response.getStatusLine());
            }

            if (HttpClientParams.isAuthenticating(this.params)) {
                if (this.authenticator.isAuthenticationRequested(proxy, response,
                        this.proxyAuthStrategy, proxyAuthState, context)) {
                    if (this.authenticator.authenticate(proxy, response,
                            this.proxyAuthStrategy, proxyAuthState, context)) {
                        // Retry request
                        if (this.reuseStrategy.keepAlive(response, context)) {
                            this.log.debug("Connection kept alive");
                            // Consume response content
                            HttpEntity entity = response.getEntity();
                            EntityUtils.consume(entity);
                        } else {
                            managedConn.close();
                        }
                    } else {
                        break;
                    }
                } else {
                    break;
                }
            }
        }

        int status = response.getStatusLine().getStatusCode();

        if (status > 299) {

            // Buffer response content
            HttpEntity entity = response.getEntity();
            if (entity != null) {
                response.setEntity(new BufferedHttpEntity(entity));
            }

            managedConn.close();
            throw new TunnelRefusedException("CONNECT refused by proxy: " +
                    response.getStatusLine(), response);
        }

        managedConn.markReusable();

        // How to decide on security of the tunnelled connection?
        // The socket factory knows only about the segment to the proxy.
        // Even if that is secure, the hop to the target may be insecure.
        // Leave it to derived classes, consider insecure by default here.
        return false;
    }

    /**
     * Creates a tunnel to an intermediate proxy.
     * This method is <i>not</i> implemented in this class.
     * It just throws an exception here.
     */
    private boolean createTunnelToProxy(
            final HttpRoute route,
            final int hop,
            final HttpContext context) throws HttpException, IOException {

        // Have a look at createTunnelToTarget and replicate the parts
        // you need in a custom derived class. If your proxies don't require
        // authentication, it is not too hard. But for the stock version of
        // HttpClient, we cannot make such simplifying assumptions and would
        // have to include proxy authentication code. The HttpComponents team
        // is currently not in a position to support rarely used code of this
        // complexity. Feel free to submit patches that refactor the code in
        // createTunnelToTarget to facilitate re-use for proxy tunnelling.

        throw new HttpException("Proxy chains are not supported.");
    }

    /**
     * Creates the CONNECT request for tunnelling.
     * Called by {@link #createTunnelToTarget createTunnelToTarget}.
     */
    private HttpRequest createConnectRequest(
            final HttpRoute route,
            final HttpContext context) {
        // see RFC 2817, section 5.2 and
        // INTERNET-DRAFT: Tunneling TCP based protocols through
        // Web proxy servers

        HttpHost target = route.getTargetHost();

        String host = target.getHostName();
        int port = target.getPort();
        if (port < 0) {
            Scheme scheme = connManager.getSchemeRegistry().
                getScheme(target.getSchemeName());
            port = scheme.getDefaultPort();
        }

        StringBuilder buffer = new StringBuilder(host.length() + 6);
        buffer.append(host);
        buffer.append(':');
        buffer.append(Integer.toString(port));

        String authority = buffer.toString();
        ProtocolVersion ver = HttpProtocolParams.getVersion(params);
        HttpRequest req = new BasicHttpRequest("CONNECT", authority, ver);
        return req;
    }

    private boolean needAuthentication(
            final AuthState targetAuthState,
            final AuthState proxyAuthState,
            final HttpRoute route,
            final HttpRequestWrapper request,
            final HttpResponse response,
            final HttpContext context) throws HttpException, IOException {

        HttpParams params = request.getParams();
        if (HttpClientParams.isAuthenticating(params)) {
            HttpHost target = (HttpHost) context.getAttribute(ExecutionContext.HTTP_TARGET_HOST);
            if (target == null) {
                target = route.getTargetHost();
            }
            if (target.getPort() < 0) {
                Scheme scheme = connManager.getSchemeRegistry().getScheme(target);
                target = new HttpHost(target.getHostName(), scheme.getDefaultPort(), target.getSchemeName());
            }
            if (this.authenticator.isAuthenticationRequested(target, response,
                    this.targetAuthStrategy, targetAuthState, context)) {
                return this.authenticator.authenticate(target, response,
                        this.targetAuthStrategy, targetAuthState, context);
            }
            HttpHost proxy = route.getProxyHost();
            if (this.authenticator.isAuthenticationRequested(proxy, response,
                    this.proxyAuthStrategy, proxyAuthState, context)) {
                return this.authenticator.authenticate(proxy, response,
                        this.proxyAuthStrategy, proxyAuthState, context);
            }
        }
        return false;
    }

    /**
     * Shuts down the connection.
     * This method is called from a <code>catch</code> block in
     * {@link #execute execute} during exception handling.
     */
    private void abortConnection(final ManagedClientConnection managedConn) {
        if (managedConn != null) {
            // we got here as the result of an exception
            // no response will be returned, release the connection
            try {
                managedConn.abortConnection();
            } catch (IOException ex) {
                if (this.log.isDebugEnabled()) {
                    this.log.debug(ex.getMessage(), ex);
                }
            }
        }
    }

}
