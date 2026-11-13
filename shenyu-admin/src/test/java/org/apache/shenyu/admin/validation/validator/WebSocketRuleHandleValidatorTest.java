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

package org.apache.shenyu.admin.validation.validator;

import org.apache.shenyu.admin.exception.ShenyuAdminException;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests the Admin boundary for WebSocket timeout rules.
 */
public final class WebSocketRuleHandleValidatorTest {

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"timeout\":3000}", "{\"timeout\":1000,\"idleTimeout\":0}",
        "{\"timeout\":1000,\"idleTimeout\":60000}"})
    public void testValidRules(final String handle) {
        assertDoesNotThrow(() -> WebSocketRuleHandleValidator.validate(handle));
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "[]", "invalid", "{\"timeout\":0}", "{\"timeout\":-1}",
        "{\"idleTimeout\":-1}", "{\"timeout\":2147483648}", "{\"idleTimeout\":2147483648}", "{\"timeout\":0}"})
    public void testInvalidRules(final String handle) {
        assertThrows(ShenyuAdminException.class, () -> WebSocketRuleHandleValidator.validate(handle));
    }
}
