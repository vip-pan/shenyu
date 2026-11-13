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

import org.apache.commons.lang3.StringUtils;
import org.apache.shenyu.admin.exception.ShenyuAdminException;
import org.apache.shenyu.common.dto.convert.rule.impl.WebSocketRuleHandle;
import org.apache.shenyu.common.utils.GsonUtils;

import java.util.Objects;

/**
 * Validates WebSocket timeout settings before publishing a rule.
 */
public final class WebSocketRuleHandleValidator {

    private WebSocketRuleHandleValidator() {
    }

    /**
     * Validate the timeout range and legacy fallback.
     *
     * @param handle serialized WebSocket rule
     */
    public static void validate(final String handle) {
        if (StringUtils.isBlank(handle)) {
            return;
        }
        try {
            WebSocketRuleHandle rule = GsonUtils.getInstance().fromJson(handle, WebSocketRuleHandle.class);
            if (Objects.isNull(rule)) {
                throw new IllegalArgumentException("rule must be a JSON object");
            }
            rule.validateTimeouts();
        } catch (RuntimeException ex) {
            throw new ShenyuAdminException("invalid websocket rule: " + ex.getMessage());
        }
    }
}
