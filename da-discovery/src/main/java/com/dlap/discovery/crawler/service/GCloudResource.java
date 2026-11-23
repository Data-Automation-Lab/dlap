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

package com.dlap.discovery.crawler.service;

import com.google.gson.Gson;
import java.util.List;

public class GCloudResource {

    private String name_;
    private String assetType_;
    private Object resource_;
    private List<String> ancestors_;
    private Object updateTime_;

    // Getters and setters
    public String getName() {
        return name_;
    }

    public void setName(String name) {
        this.name_ = name;
    }

    public String getAssetType() {
        return assetType_;
    }

    public void setAssetType(String assetType_) {
        this.assetType_ = assetType_;
    }

    public List<String> getAncestors() {
        return ancestors_;
    }

    public void setAncestors(List<String> ancestors_) {
        this.ancestors_ = ancestors_;
    }

    public Object getResource() {
        return resource_;
    }

    public void setResource(Object resource_) {
        this.resource_ = resource_;
    }

    public updateTime getUpdateTime() {
        String updateTimeJSON = updateTime_.toString();
        Gson gson = new Gson();
        updateTime updateTimeObj = gson.fromJson(updateTimeJSON, updateTime.class);
        return updateTimeObj;
    }

    public void setUpdateTime(Object updateTime) {
        this.updateTime_ = updateTime;
    }

    public Object getLocation() {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'getLocation'");
    }
}

class updateTime {

    private long seconds_;
    private int nanos_;

    public long getSeconds() {
        return seconds_;
    }

    public void setSeconds(long seconds) {
        this.seconds_ = seconds;
    }

    public int getNanos() {
        return nanos_;
    }

    public void setNanos(int nanos) {
        this.nanos_ = nanos;
    }
}
