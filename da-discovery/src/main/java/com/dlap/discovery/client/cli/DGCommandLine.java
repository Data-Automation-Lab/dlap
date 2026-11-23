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

package com.dlap.discovery.client.cli;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

//@Command(command = "dg")
public class DGCommandLine {

    private static final Logger LOGGER = LoggerFactory.getLogger(DGCommandLine.class);

    // @Autowired
    // GCPCrawler gcpCrawler;

    // From Here the shell calls the Folders
    // @Command(command = "scanFolder", description = "Start data guardian Folder")
    // public String runFolder(String folderId) {
    // // @Option(
    // // label = "folderName",
    // // description = "Name of the Folder to Scanned ",
    // // defaultValue = "<folder-id>",
    // // required = true
    // // ) String folderId

    // LOGGER.debug("Starting data guardian Folder.");
    // long FolderCount = gcpCrawler.fetchFolders(folderId);
    // LOGGER.debug("Crawler has stopped running. Total folder found: {}",
    // FolderCount);
    // return "Spider fetched " + FolderCount + "folders.";

    // }

    // @Command(command = "stopFolder", description = "Stop data guardian Folder")
    public String stopFolder() {
        LOGGER.debug("Stopping data guardian Folder.");

        LOGGER.debug("Folder has stopped running.");
        return "Folder is stopped";
    }

    // @Command(command = "folderstatus", description = "Get status of data guardian
    // Folder")
    public String getFolderStatus() {
        return "Folder is running";
    }

    // Fetching assets shell shart from here
    // @Command(command = "runCrawler", description = "Start data guardian ")
    // public String runCrawler() {
    // LOGGER.debug("Starting data guardian .");
    // // long assetCount = gcpCrawler.fetchAssets();
    // LOGGER.debug("Crawler has stopped running. Total assets found: {}",
    // assetCount);
    // return "Spider fetched " + assetCount + "assets.";
    // }

    // @Command(command = "stopCrawler", description = "Stop data guardian ")
    public String stopCrawler() {
        LOGGER.debug("Stopping data guardian .");

        LOGGER.debug("Crawler has stopped running.");
        return "Crawler is stopped";
    }

    // @Command(command = "status", description = "Get status of data guardian ")
    public String getStatus() {
        return "Crawler is running";
    }
}
