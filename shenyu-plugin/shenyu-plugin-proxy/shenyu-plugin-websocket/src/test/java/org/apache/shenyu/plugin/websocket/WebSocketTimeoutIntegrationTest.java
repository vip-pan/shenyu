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

package org.apache.shenyu.plugin.websocket;

import io.netty.handler.timeout.ReadTimeoutHandler;
import org.apache.shenyu.common.constant.Constants;
import org.apache.shenyu.common.dto.RuleData;
import org.apache.shenyu.common.dto.SelectorData;
import org.apache.shenyu.common.dto.convert.rule.impl.WebSocketRuleHandle;
import org.apache.shenyu.common.enums.LoadBalanceEnum;
import org.apache.shenyu.common.utils.GsonUtils;
import org.apache.shenyu.loadbalancer.cache.UpstreamCacheManager;
import org.apache.shenyu.loadbalancer.entity.Upstream;
import org.apache.shenyu.plugin.api.context.ShenyuContext;
import org.apache.shenyu.plugin.websocket.client.TimeoutWebSocketClient;
import org.apache.shenyu.plugin.websocket.handler.WebSocketPluginDataHandler;
import org.junit.jupiter.api.Test;
import org.springframework.http.server.reactive.ReactorHttpHandlerAdapter;
import org.springframework.web.reactive.socket.CloseStatus;
import org.springframework.web.reactive.socket.client.ReactorNettyWebSocketClient;
import org.springframework.web.reactive.socket.server.support.HandshakeWebSocketService;
import org.springframework.web.server.adapter.WebHttpHandlerBuilder;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.client.HttpClient;
import reactor.netty.http.client.WebsocketClientSpec;
import reactor.netty.http.server.HttpServer;
import reactor.test.StepVerifier;

import java.net.URI;
import java.time.Duration;
import java.util.Collections;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Exercises both proxy legs, synchronized rule updates and close-frame propagation.
 */
public final class WebSocketTimeoutIntegrationTest {

    @Test
    public void testIdleCloseAndRuleUpdateThroughGateway() {
        final DisposableServer backend = HttpServer.create().host("127.0.0.1").port(0)
                .handle((req, res) -> res.sendWebsocket((in, out) -> out.send(in.receive().retain()))).bindNow();
        SelectorData selector = new SelectorData();
        selector.setId("websocket-timeout-integration");
        RuleData rule = new RuleData();
        rule.setId("websocket-timeout-rule");
        rule.setSelectorId(selector.getId());
        WebSocketRuleHandle handle = new WebSocketRuleHandle();
        handle.setLoadBalance(LoadBalanceEnum.LEAST_ACTIVITY.getName());
        handle.setIdleTimeout(300);
        WebSocketPluginDataHandler dataHandler = new WebSocketPluginDataHandler();
        rule.setHandle(GsonUtils.getInstance().toJson(handle));
        dataHandler.handlerRule(rule);
        Upstream upstream = Upstream.builder().url("127.0.0.1:" + backend.port()).protocol("ws://")
                .status(true).weight(100).build();
        UpstreamCacheManager.getInstance().submit(selector.getId(), Collections.singletonList(upstream));
        TimeoutWebSocketClient transport = new TimeoutWebSocketClient(HttpClient.create()
                .doOnConnected(conn -> conn.addHandlerLast(new ReadTimeoutHandler(100, TimeUnit.MILLISECONDS))), WebsocketClientSpec::builder);
        WebSocketPlugin plugin = new WebSocketPlugin(transport, new HandshakeWebSocketService());
        DisposableServer gateway = HttpServer.create().host("127.0.0.1").port(0)
                .handle(new ReactorHttpHandlerAdapter(WebHttpHandlerBuilder.webHandler(exchange -> {
                    exchange.getAttributes().put(Constants.CONTEXT, new ShenyuContext());
                    return plugin.doExecute(exchange, ignored -> Mono.empty(), selector, rule);
                }).build())).bindNow();
        try {
            URI url = URI.create("ws://127.0.0.1:" + gateway.port() + "/test");
            ReactorNettyWebSocketClient browser = new ReactorNettyWebSocketClient();
            AtomicReference<CloseStatus> status = new AtomicReference<>();
            StepVerifier.create(browser.execute(url, session -> Mono.when(session.receive().then(),
                            session.closeStatus().doOnNext(status::set).then())))
                    .expectComplete().verify(Duration.ofSeconds(5));
            assertNotNull(status.get());
            assertEquals(1001, status.get().getCode());
            assertEquals("WebSocket idle timeout", status.get().getReason());
            // A new connection observes the new rule without rebuilding the transport or gateway.
            handle.setIdleTimeout(0);
            rule.setHandle(GsonUtils.getInstance().toJson(handle));
            dataHandler.handlerRule(rule);
            AtomicReference<String> reply = new AtomicReference<>();
            StepVerifier.create(browser.execute(url, session -> Mono.when(
                            session.send(Mono.delay(Duration.ofMillis(700)).map(tick -> session.textMessage("after-idle"))),
                            session.receive().next().doOnNext(message -> reply.set(message.getPayloadAsText()))
                                    .then(session.close()))))
                    .expectComplete().verify(Duration.ofSeconds(5));
            assertEquals("after-idle", reply.get());
        } finally {
            gateway.disposeNow();
            backend.disposeNow();
            dataHandler.removeRule(rule);
            UpstreamCacheManager.getInstance().removeByKey(selector.getId());
        }
    }
}
