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

package com.dlap.discovery.crawler;
// package com.dlap.discovery.sd.crawler;

// import java.io.IOException;

// import static org.junit.jupiter.api.Assertions.assertEquals;
// import static org.junit.jupiter.api.Assertions.assertNotNull;
// import org.junit.jupiter.api.BeforeEach;
// import org.junit.jupiter.api.Test;
// import org.junit.jupiter.api.extension.ExtendWith;
// import static org.mockito.ArgumentMatchers.any;
// import org.mockito.InjectMocks;
// import org.mockito.Mock;
// import static org.mockito.Mockito.doReturn;
// import static org.mockito.Mockito.mock;
// import static org.mockito.Mockito.spy;
// import static org.mockito.Mockito.times;
// import static org.mockito.Mockito.verify;
// import org.mockito.MockitoAnnotations;
// import org.mockito.junit.jupiter.MockitoExtension;
// import org.slf4j.Logger;
// import org.slf4j.LoggerFactory;

// import com.google.api.services.cloudresourcemanager.v3.model.Folder;
// import com.google.auth.Credentials;
// import com.google.cloud.bigquery.BigQuery;
// import com.google.cloud.resourcemanager.v3.FoldersClient;
// import com.google.cloud.resourcemanager.v3.ListFoldersRequest;

// //import jakarta.persistence.Table;

// @ExtendWith(MockitoExtension.class)
// class GCPCrawlerTest {

//     private static final Logger LOG = LoggerFactory.getLogger(GCPCrawlerTest.class);

//     @Mock
//     private FoldersClient foldersClient;

//     @Mock
//     private BigQuery bigQuery; 

//     @Mock
//     private Credentials credentials;

//     @InjectMocks
//     private GCPCrawler gcpCrawler;
    
//     private GCPCrawler gcpCrawlerSpy;

//     @BeforeEach
//     void setup() {
//         gcpCrawlerSpy = spy(gcpCrawler);

//         MockitoAnnotations.openMocks(this); 
//     }

    


// @Test
// void testGetGCPCredentials() throws IOException {
//     // Arrange
//     GCPCrawler gcpCrawler = new GCPCrawler();

//     // Act
//     Credentials credentials = gcpCrawler.getGCPCredentials();

//     // Assert
//     assertNotNull(credentials);
// }
// @Test
//     void testFetchFolders() throws Exception {
//         // Arrange
//         String folderId = "12345";
//         long expectedTotalFolders = 10L;

//         // Mock the behavior of getGCPCredentials()
//         doReturn(credentials).when(gcpCrawlerSpy).getGCPCredentials();

//         // Mock the behavior of listAllFolders()
//         doReturn(expectedTotalFolders).when(gcpCrawlerSpy).listAllFolders(any(FoldersClient.class), any(ListFoldersRequest.class), any(Credentials.class));

//         // Act
//         long totalFolders = gcpCrawlerSpy.fetchFolders(folderId);

//         // Assert
//         assertEquals(expectedTotalFolders, totalFolders);
//         verify(gcpCrawlerSpy, times(1)).getGCPCredentials();
//         verify(gcpCrawlerSpy, times(1)).listAllFolders(any(FoldersClient.class), any(ListFoldersRequest.class), any(Credentials.class));
//         LOG.debug("Test completed successfully for fetching folders with folder ID: {}", folderId);
//     }

    
//     @Test
//     void testListAllFolders() throws Exception {
//         // Arrange
//         String parentFolderId = "12345";
//         long expectedTotalFolders = 10L;

//         // Mock the behavior of listAllFolders()
//         doReturn(expectedTotalFolders).when(gcpCrawlerSpy).listAllFolders(any(FoldersClient.class), any(ListFoldersRequest.class), any(Credentials.class));

//         // Act
//         long totalFolders = gcpCrawlerSpy.listAllFolders(foldersClient, ListFoldersRequest.newBuilder().setParent(parentFolderId).build(), credentials);

//         // Assert
//         assertEquals(expectedTotalFolders, totalFolders);
//         verify(gcpCrawlerSpy, times(1)).listAllFolders(any(FoldersClient.class), any(ListFoldersRequest.class), any(Credentials.class));
//         LOG.debug("Test completed successfully for listing all folders with parent folder ID: {}", parentFolderId);
//     }

 
    

// @Test
// void testIsFolderAlreadyPersisted() {
//     // Arrange
//     GCPCrawler gcpCrawler = new GCPCrawler();
//     Folder folder = mock(Folder.class);

// }


// @Test
// void testListProjectsInFolder() {
//     // Arrange
//     GCPCrawler gcpCrawler = new GCPCrawler();
//     Folder folder = mock(Folder.class);
//     Credentials credentials = mock(Credentials.class);

// }

// @Test
// void testUsesBigQuery() {
//     // Arrange
//     GCPCrawler gcpCrawler = new GCPCrawler();
//     String projectId = "12345";

// }

// }




