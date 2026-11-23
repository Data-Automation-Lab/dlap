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

// package com.dlap.discovery.common.model;


// import org.springframework.stereotype.Component;
// import com.dlap.discovery.common.model.enums.CloudProvider;
// import com.dlap.discovery.crawler.service.IFolderManagementService;
// import org.slf4j.Logger;
// import org.slf4j.LoggerFactory;

// @Component
// public class CloudProviderDispatcher {

//     private final IFolderManagementService folderManagementService;
//     private static final Logger log = LoggerFactory.getLogger(CloudProviderDispatcher.class);


//     public CloudProviderDispatcher(IFolderManagementService folderManagementService) {
//         this.folderManagementService = folderManagementService;
//     }

//    public void dispatch(DiscoveryJob job) {  
//     switch (job.getCloudProvider()) {
//         case GCP:
//             handleGcp(job);
//             break;
//         case AWS:
//             handleAws(job);
//             break;
//         case AZURE:
//             handleAzure(job);
//             break;
//         default:
//             throw new IllegalArgumentException("Unsupported provider: " + job.getCloudProvider());
//     }
// }

//     private void handleGcp(DiscoveryJob job) {
//         // Call your GCP-related class/method here
//        log.info("Handling GCP provider");
//        new GcpHandler(folderManagementService).process(job);
//         // Example: new GcpHandler().process();
//     }

//     private void handleAws(DiscoveryJob job) {
//         // Call your AWS-related class/method here
//         new AwsHandler().process();
//         log.info("Handling AWS provider");
//         // Example: new AwsHandler().process();
//     }

//     private void handleAzure(DiscoveryJob job) {
//         // Call your Azure-related class/method here
//         new AzureHandler().process();
//         log.info("Handling Azure provider");
//         // Example: new AzureHandler().process();
//     }
// }
