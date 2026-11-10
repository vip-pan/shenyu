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

package org.apache.shenyu.plugin.grpc.loadbalance.picker;

import io.grpc.CallOptions;
import io.grpc.ConnectivityState;
import io.grpc.ConnectivityStateInfo;
import io.grpc.EquivalentAddressGroup;
import io.grpc.LoadBalancer;
import org.apache.shenyu.common.dto.convert.selector.GrpcUpstream;
import org.apache.shenyu.plugin.grpc.cache.ApplicationConfigCache;
import org.apache.shenyu.plugin.grpc.context.GrpcConstants;
import org.apache.shenyu.plugin.grpc.loadbalance.SubChannelCopy;
import org.apache.shenyu.plugin.grpc.loadbalance.SubChannels;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;

/**
 * The Test Case For {@link ShenyuPicker}.
 */
@ExtendWith(MockitoExtension.class)
public class ShenyuPickerTest {

    private static final String SELECTOR_ID = "shenyuPickerTestSelectorId";

    private static final String RULE_ID = "shenyuPickerTestRuleId";

    private static final String UPSTREAM_URL = "10.250.230.126:38080";

    private ShenyuPicker shenyuPicker;

    private List<SubChannelCopy> readySubchannels;

    @BeforeEach
    public void setUp() {
        LoadBalancer.Subchannel subchannel = SubChannels.createSubChannel(new UnitTestReadHelper(),
                mock(EquivalentAddressGroup.class), SubChannels.createAttributes(1, "true", UPSTREAM_URL));
        SubChannels.setStateInfo(subchannel, ConnectivityStateInfo.forNonError(ConnectivityState.READY));
        shenyuPicker = new ShenyuPicker(Collections.singletonList(subchannel));
        readySubchannels = shenyuPicker.getSubchannels();
        ApplicationConfigCache.getInstance().cacheRuleHandle(RULE_ID, "{\"loadBalance\":\"roundRobin\"}");
        ApplicationConfigCache.getInstance().handlerUpstream(SELECTOR_ID,
                Collections.singletonList(GrpcUpstream.builder().upstreamUrl(UPSTREAM_URL).weight(1).build()));
    }

    @AfterEach
    public void tearDown() {
        ApplicationConfigCache.getInstance().removeRuleHandle(RULE_ID);
        ApplicationConfigCache.getInstance().invalidate(SELECTOR_ID);
    }

    @Test
    public void testPickWithPluginOptions() {
        CallOptions callOptions = CallOptions.DEFAULT
                .withOption(GrpcConstants.GRPC_SELECTOR_ID, SELECTOR_ID)
                .withOption(GrpcConstants.GRPC_RULE_ID, RULE_ID)
                .withOption(GrpcConstants.GRPC_REMOTE_ADDRESS, "127.0.0.1");
        SubChannelCopy picked = shenyuPicker.pick(readySubchannels, callOptions);
        assertNotNull(picked);
        assertEquals(UPSTREAM_URL, picked.getUrl());
    }

    @Test
    public void testPickOnTransportThreadWithoutRequestContext() throws InterruptedException {
        // A delayed pick runs on a gRPC transport thread once the channel becomes ready;
        // the caller's thread-local Context is absent there, so the ids must travel on the options.
        CallOptions callOptions = CallOptions.DEFAULT
                .withOption(GrpcConstants.GRPC_SELECTOR_ID, SELECTOR_ID)
                .withOption(GrpcConstants.GRPC_RULE_ID, RULE_ID);
        AtomicReference<SubChannelCopy> picked = new AtomicReference<>();
        Thread transportThread = new Thread(() -> picked.set(shenyuPicker.pick(readySubchannels, callOptions)));
        transportThread.start();
        transportThread.join();
        assertNotNull(picked.get());
        assertEquals(UPSTREAM_URL, picked.get().getUrl());
    }

    @Test
    public void testPickWithoutPluginOptionsFallsBackToRandom() {
        // Calls without plugin options must not throw; they fall back to random subchannel choice.
        SubChannelCopy picked = shenyuPicker.pick(readySubchannels, CallOptions.DEFAULT);
        assertNotNull(picked);
        assertEquals(UPSTREAM_URL, picked.getUrl());
    }
}
