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

package org.apache.shenyu.admin.mapper;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests fresh Admin form metadata and repeatable upgrades without rewriting existing rules.
 */
public final class WebSocketTimeoutMigrationTest {

    @Test
    public void testFreshSchemaAndRepeatableMigration() throws Exception {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:websocket-timeout-migration;MODE=MySQL;DATABASE_TO_LOWER=TRUE");
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            new ResourceDatabasePopulator(new ClassPathResource("sql-script/h2/schema.sql")).execute(dataSource);
            assertMetadata(statement);
            statement.execute("DELETE FROM plugin_handle WHERE plugin_id = '26' AND field = 'idleTimeout'");
            statement.execute("UPDATE plugin_handle SET label = 'timeout' WHERE plugin_id = '26' AND field = 'timeout'");
            Path root = Path.of(System.getProperty("user.dir")).toAbsolutePath();
            while (Objects.nonNull(root) && !Files.isDirectory(root.resolve("db/upgrade"))) {
                root = root.getParent();
            }
            assertNotNull(root);
            String script = Files.readString(root.resolve("db/upgrade/2.7.2-upgrade-2.7.3-h2.sql"));
            String block = script.substring(script.indexOf("-- WebSocket timeout is the connection/upgrade deadline"));
            ResourceDatabasePopulator migration = new ResourceDatabasePopulator(new ByteArrayResource(block.getBytes(StandardCharsets.UTF_8)));
            migration.execute(dataSource);
            migration.execute(dataSource);
            assertMetadata(statement);
        }
    }

    private void assertMetadata(final Statement statement) throws Exception {
        try (ResultSet rows = statement.executeQuery("SELECT label FROM plugin_handle WHERE plugin_id = '26' AND field = 'timeout'")) {
            assertTrue(rows.next());
            assertEquals("Connection timeout (ms)", rows.getString(1));
        }
        try (ResultSet rows = statement.executeQuery("SELECT count(*) FROM plugin_handle WHERE plugin_id = '26' AND field = 'idleTimeout'")) {
            assertTrue(rows.next());
            assertEquals(1, rows.getInt(1));
        }
    }
}
