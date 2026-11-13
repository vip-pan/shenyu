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

import io.netty.handler.timeout.ReadTimeoutHandler;
import org.apache.shenyu.common.dto.convert.rule.impl.WebSocketRuleHandle;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.socket.CloseStatus;
import org.springframework.web.reactive.socket.WebSocketMessage;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.netty.DisposableServer;
import reactor.netty.http.client.HttpClient;
import reactor.netty.http.client.WebsocketClientSpec;
import reactor.netty.http.server.HttpServer;
import reactor.netty.tcp.TcpServer;
import reactor.test.StepVerifier;

import java.net.URI;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Socket-level regression tests for connection deadlines and bidirectional idle detection.
 */
public final class TimeoutWebSocketClientTest {

    private final TimeoutWebSocketClient client = new TimeoutWebSocketClient(HttpClient.create()
            .responseTimeout(Duration.ofMillis(100))
            .doOnConnected(connection -> connection.addHandlerLast(new ReadTimeoutHandler(100, TimeUnit.MILLISECONDS))),
            WebsocketClientSpec::builder);

    @Test
    public void testHandshakeDeadline() {
        DisposableServer server = TcpServer.create().host("127.0.0.1").port(0)
                .handle((in, out) -> in.receive().then(Mono.never())).bindNow();
        try {
            WebSocketRuleHandle rule = rule(200, 0);
            StepVerifier.create(client.execute(uri(server), session -> Mono.never())
                            .contextWrite(context -> context.put(WebSocketRuleHandle.class, rule)))
                    .expectError(TimeoutException.class).verify(Duration.ofSeconds(5));
        } finally {
            server.disposeNow();
        }
    }

    @Test
    public void testIdleTimeoutClosesWithReason() {
        DisposableServer server = echoServer();
        AtomicReference<CloseStatus> status = new AtomicReference<>();
        try {
            StepVerifier.create(client.execute(uri(server), session -> Mono.when(
                            session.receive().then(), session.closeStatus().doOnNext(status::set).then()))
                            .contextWrite(context -> context.put(WebSocketRuleHandle.class, rule(2000, 250))))
                    .expectComplete().verify(Duration.ofSeconds(5));
            assertNotNull(status.get());
            assertEquals(1001, status.get().getCode());
            assertEquals("WebSocket idle timeout", status.get().getReason());
        } finally {
            server.disposeNow();
        }
    }

    @Test
    public void testIdleDisabledAndHandshakeDeadlineStopsAfterUpgrade() {
        DisposableServer server = echoServer();
        try {
            StepVerifier.create(client.execute(uri(server), session -> Mono.delay(Duration.ofMillis(800))
                            .then(session.close()))
                            .contextWrite(context -> context.put(WebSocketRuleHandle.class, rule(500, 0))))
                    .expectComplete().verify(Duration.ofSeconds(5));
        } finally {
            server.disposeNow();
        }
    }

    @Test
    public void testClientOnlyTrafficKeepsConnectionAlive() {
        DisposableServer server = HttpServer.create().host("127.0.0.1").port(0)
                .handle((request, response) -> response.sendWebsocket((in, out) -> in.receive().then())).bindNow();
        AtomicInteger sent = new AtomicInteger();
        try {
            StepVerifier.create(client.execute(uri(server), session -> session.send(Flux.interval(Duration.ofMillis(50))
                            .take(12).doOnNext(index -> sent.incrementAndGet()).map(index -> session.textMessage("client-only-" + index))).then(session.close()))
                            .contextWrite(context -> context.put(WebSocketRuleHandle.class, rule(2000, 200))))
                    .expectComplete().verify(Duration.ofSeconds(5));
            assertEquals(12, sent.get());
        } finally {
            server.disposeNow();
        }
    }

    @Test
    public void testServerOnlyTrafficKeepsConnectionAlive() {
        DisposableServer server = HttpServer.create().host("127.0.0.1").port(0)
                .handle((request, response) -> response.sendWebsocket((in, out) -> out.sendString(
                        Flux.interval(Duration.ofMillis(50)).take(12).map(Object::toString)))).bindNow();
        try {
            AtomicReference<Long> count = new AtomicReference<>();
            StepVerifier.create(client.execute(uri(server), session -> session.receive()
                            .map(WebSocketMessage::getPayloadAsText).count().doOnNext(count::set).then())
                            .contextWrite(context -> context.put(WebSocketRuleHandle.class, rule(2000, 200))))
                    .expectComplete().verify(Duration.ofSeconds(5));
            assertEquals(12L, count.get());
        } finally {
            server.disposeNow();
        }
    }

    @Test
    public void testDifferentRuleTimeoutsDoNotLeakBetweenConnections() {
        DisposableServer server = echoServer();
        AtomicReference<CloseStatus> expired = new AtomicReference<>();
        AtomicReference<String> reply = new AtomicReference<>();
        try {
            Mono<Void> shortIdle = client.execute(uri(server), session -> Mono.when(session.receive().then(),
                    session.closeStatus().doOnNext(expired::set).then()))
                    .contextWrite(context -> context.put(WebSocketRuleHandle.class, rule(2000, 200)));
            Mono<Void> noIdle = client.execute(uri(server), session -> Mono.when(
                    session.send(Mono.delay(Duration.ofMillis(600)).map(tick -> session.textMessage("still-open"))),
                    session.receive().next().doOnNext(message -> reply.set(message.getPayloadAsText())).then(session.close())))
                    .contextWrite(context -> context.put(WebSocketRuleHandle.class, rule(2000, 0)));
            StepVerifier.create(Mono.when(shortIdle, noIdle)).expectComplete().verify(Duration.ofSeconds(5));
            assertEquals("WebSocket idle timeout", expired.get().getReason());
            assertEquals("still-open", reply.get());
        } finally {
            server.disposeNow();
        }
    }

    @Test
    public void testCancellationClosesUpstreamConnection() {
        Sinks.Empty<Void> established = Sinks.empty();
        Sinks.Empty<Void> disposed = Sinks.empty();
        DisposableServer server = HttpServer.create().host("127.0.0.1").port(0)
                .handle((request, response) -> response.sendWebsocket((in, out) -> in.receive().then()
                        .doFinally(signal -> disposed.tryEmitEmpty()))).bindNow();
        try {
            StepVerifier.create(client.execute(uri(server), session -> {
                established.tryEmitEmpty();
                return Mono.never();
            }).contextWrite(context -> context.put(WebSocketRuleHandle.class, rule(2000, 0))))
                    .then(() -> established.asMono().block(Duration.ofSeconds(5)))
                    .thenCancel().verify(Duration.ofSeconds(5));
            StepVerifier.create(disposed.asMono()).expectComplete().verify(Duration.ofSeconds(5));
        } finally {
            server.disposeNow();
        }
    }

    private static DisposableServer echoServer() {
        return HttpServer.create().host("127.0.0.1").port(0)
                .handle((request, response) -> response.sendWebsocket((in, out) -> out.send(in.receive().retain()))).bindNow();
    }

    private static URI uri(final DisposableServer server) {
        return URI.create("ws://127.0.0.1:" + server.port() + "/test");
    }

    private static WebSocketRuleHandle rule(final long connectTimeout, final long idleTimeout) {
        WebSocketRuleHandle rule = new WebSocketRuleHandle();
        rule.setTimeout(connectTimeout);
        rule.setIdleTimeout(idleTimeout);
        return rule;
    }
}
