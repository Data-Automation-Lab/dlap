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

package com.dlap.discovery.event.service.subscriber;

import com.fasterxml.jackson.annotation.JsonProperty;

public class JsonPojo {

    private Add add;
    private Config config;
    
    @JsonProperty("uploadlogs")
    private UploadLogs uploadLogs; // New field

    // Getters and Setters

    public Add getAdd() {
        return add;
    }

    public void setAdd(Add add) {
        this.add = add;
    }

    public Config getConfig() {
        return config;
    }

    public void setConfig(Config config) {
        this.config = config;
    }

    public UploadLogs getUploadLogs() {
        return uploadLogs;
    }

    public void setUploadLogs(UploadLogs uploadLogs) {
        this.uploadLogs = uploadLogs;
    }

    // Nested Add class
    public static class Add {

        private String section;
        private Object values;

        // Getters and Setters
        public String getSection() {
            return section;
        }

        public void setSection(String section) {
            this.section = section;
        }

        public Object getValues() {
            return values;
        }

        public void setValues(Object values) {
            this.values = values;
        }

        @Override
        public String toString() {
            return "Add{" + "section='" + section + '\'' + ", values=" + values + '}';
        }
    }

    // Nested Config class
    public static class Config {

        private String section;
        private String values; // Changed from Object to String to match JSON input

        // Getters and Setters
        public String getSection() {
            return section;
        }

        public void setSection(String section) {
            this.section = section;
        }

        public String getValues() {
            return values;
        }

        public void setValues(String values) {
            this.values = values;
        }

        @Override
        public String toString() {
            return "Config{" + "section='" + section + '\'' + ", values='" + values + '\'' + '}';
        }
    }

    // New nested UploadLogs class
    public static class UploadLogs {
        private String message;

        // Getters and Setters
        public String getMessage() {
            return message;
        }

        public void setMessage(String message) {
            this.message = message;
        }

        @Override
        public String toString() {
            return "uploadLogs{" + "message='" + message + '\'' + '}';
        }
    }
}
