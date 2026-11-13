/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.shenyu.plugin.websocket.client;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelOption;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.handler.timeout.IdleStateHandler;
import io.netty.handler.timeout.WriteTimeoutHandler;
import org.apache.shenyu.common.dto.convert.rule.impl.WebSocketRuleHandle;
import org.springframework.http.HttpHeaders;
import org.springframework.lang.NonNull;
import org.springframework.web.reactive.socket.CloseStatus;
import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.reactive.socket.WebSocketSession;
import org.springframework.web.reactive.socket.client.ReactorNettyWebSocketClient;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.netty.Connection;
import reactor.netty.http.client.HttpClient;
import reactor.netty.http.client.WebsocketClientSpec;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * Applies per-rule deadlines without inheriting HTTP request timeouts into WebSocket sessions.
 */
public final class TimeoutWebSocketClient extends ReactorNettyWebSocketClient {

    private final Supplier<WebsocketClientSpec.Builder> builderSupplier;

    /**
     * Create a client preserving the shared transport settings and WebSocket options.
     *
     * @param httpClient shared transport configuration
     * @param builderSupplier WebSocket protocol options
     */
    public TimeoutWebSocketClient(final HttpClient httpClient, final Supplier<WebsocketClientSpec.Builder> builderSupplier) {
        super(httpClient, builderSupplier);
        this.builderSupplier = builderSupplier;
    }

    @Override
    @NonNull
    public Mono<Void> execute(@NonNull final URI url, @NonNull final HttpHeaders headers, @NonNull final WebSocketHandler handler) {
        return Mono.deferContextual(context -> {
            WebSocketRuleHandle rule = context.getOrDefault(WebSocketRuleHandle.class, WebSocketRuleHandle.newDefaultInstance());
            rule.validateTimeouts();
            long connectTimeout = rule.getTimeout();
            long idleTimeout = rule.getIdleTimeout();
            AtomicReference<Connection> connection = new AtomicReference<>();
            Sinks.Empty<Void> connected = Sinks.empty();
            HttpClient transport = getHttpClient().responseTimeout(null)
                    .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, (int) connectTimeout)
                    .doOnConnected(conn -> {
                        // The shared factory runs first. Strip its HTTP-specific timers on this channel only.
                        conn.channel().pipeline().toMap().forEach((name, channelHandler) -> {
                            if (channelHandler instanceof IdleStateHandler || channelHandler instanceof WriteTimeoutHandler) {
                                conn.removeHandler(name);
                            }
                        });
                        connection.set(conn);
                    });
            ReactorNettyWebSocketClient client = new ReactorNettyWebSocketClient(transport, builderSupplier);
            Mono<Void> session = client.execute(url, headers, new WebSocketHandler() {
                @Override
                @NonNull
                public Mono<Void> handle(@NonNull final WebSocketSession webSocketSession) {
                    connected.tryEmitEmpty();
                    if (idleTimeout == 0) {
                        return handler.handle(webSocketSession);
                    }
                    Sinks.Empty<Void> expired = Sinks.empty();
                    Connection conn = connection.get();
                    conn.addHandlerLast("shenyuWebSocketIdle", new IdleStateHandler(0, 0, idleTimeout, TimeUnit.MILLISECONDS));
                    conn.addHandlerLast("shenyuWebSocketIdleClose", new IdleCloseHandler(expired));
                    Mono<Void> close = expired.asMono().then(Mono.defer(() ->
                            webSocketSession.close(CloseStatus.GOING_AWAY.withReason("WebSocket idle timeout"))));
                    return Mono.firstWithSignal(handler.handle(webSocketSession), close);
                }

                @Override
                @NonNull
                public List<String> getSubProtocols() {
                    return handler.getSubProtocols();
                }
            });
            // Stop the deadline at upgrade completion, not at the end of the long-lived session.
            Mono<Void> deadline = connected.asMono().timeout(Duration.ofMillis(connectTimeout)).then(Mono.never());
            return Mono.firstWithSignal(session, deadline);
        });
    }

    private static final class IdleCloseHandler extends ChannelInboundHandlerAdapter {

        private final Sinks.Empty<Void> expired;

        IdleCloseHandler(final Sinks.Empty<Void> expired) {
            this.expired = expired;
        }

        @Override
        public void userEventTriggered(final ChannelHandlerContext context, final Object event) throws Exception {
            if (event instanceof IdleStateEvent && ((IdleStateEvent) event).state() == IdleState.ALL_IDLE) {
                expired.tryEmitEmpty();
            } else {
                super.userEventTriggered(context, event);
            }
        }
    }
}
