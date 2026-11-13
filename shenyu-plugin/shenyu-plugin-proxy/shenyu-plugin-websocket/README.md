<!--
Licensed to the Apache Software Foundation (ASF) under one
or more contributor license agreements. See the NOTICE file
distributed with this work for additional information
regarding copyright ownership. The ASF licenses this file
to you under the Apache License, Version 2.0 (the
"License"); you may not use this file except in compliance
with the License. You may obtain a copy of the License at

http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing,
software distributed under the License is distributed on an
"AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
KIND, either express or implied. See the License for the
specific language governing permissions and limitations
under the License.
-->

# WebSocket rule timeouts

The standard WebSocket starter applies two independent rule settings:

| Field | Unit | Default | Behavior |
| --- | --- | --- | --- |
| `timeout` | milliseconds | `3000` | Deadline for establishing the upstream connection, including TLS and the WebSocket upgrade. Must be between 1 and 2147483647. Stops when the upgrade succeeds. |
| `idleTimeout` | milliseconds | `0` | Closes an established connection when neither direction of the upstream WebSocket transports frames for this duration. Zero disables idle expiration; positive values up to 2147483647 enable it. |

For example, allow 5 seconds to connect and close after 60 seconds without proxied traffic:

```json
{"loadBalance":"random","retry":0,"timeout":5000,"idleTimeout":60000}
```

Traffic in either direction resets idle detection, including forwarded Ping/Pong frames.
Control frames handled locally on the browser-facing connection do not reach the
upstream connection and do not reset its idle timer. Applications relying on these
heartbeats should enable WebSocket ping proxying or send application heartbeat messages.
Idle expiration sends close code `1001` with reason `WebSocket idle timeout`.

The existing `timeout` JSON field is retained to preserve configured values.
Older rules without `idleTimeout` disable idle expiration. The previous accidental
HTTP-client read timeout no longer limits WebSocket sessions. HTTP requests continue
to use their existing HTTP-client timeouts; shared TLS, proxy and protocol settings
are retained. Custom replacements for the standard WebSocket client must implement
their own timeout support.

Rule synchronization applies changed values to subsequent connections. Existing
connections retain the timeout settings captured when they connected.

## Admin schema

Fresh-install schemas include the connection-timeout label and idle-timeout field.
For existing installations, the WebSocket block at the end of the matching
`db/upgrade/2.7.2-upgrade-2.7.3-*.sql` updates the form metadata without rewriting rule
JSON. If other migrations have already been applied, execute only the new WebSocket
block; do not rerun unrelated schema changes. Refresh the Admin page after applying it.
Deploy the updated Admin and bootstrap code. Existing remote databases and running
processes are not changed by building these modules.
