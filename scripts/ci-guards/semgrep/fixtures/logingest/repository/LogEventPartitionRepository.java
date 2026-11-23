/*
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
 */

// Fixture for the Semgrep rules in /.semgrep.yml. Never compiled, never shipped.
// The path of this file is one the SQL-concatenation rule excludes (partition DDL with a validated
// identifier), and nothing else: SqlConcatenation.java in the fixtures root must still be flagged.
package fixtures.logingest.repository;

import org.springframework.jdbc.core.JdbcTemplate;

class LogEventPartitionRepository {
    void dropValidatedPartition(JdbcTemplate jdbc, String qualifiedName) {
        // ok: dlap-java-sql-concatenation
        jdbc.execute("DROP TABLE IF EXISTS " + qualifiedName);
    }
}
