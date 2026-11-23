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

package com.dlap.discovery.common.util.impl;

import com.dlap.discovery.common.util.RetryException;
import com.dlap.discovery.common.util.RetryPolicy;
import com.dlap.discovery.common.util.RetryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.function.Supplier;

/**
 * Default implementation of the RetryService.
 */
@Service
public class DefaultRetryServiceImpl implements RetryService {
    private static final Logger log = LoggerFactory.getLogger(DefaultRetryServiceImpl.class);
    
    private volatile RetryPolicy defaultPolicy = new RetryPolicy();
    
    @Override
    public <T> T executeWithRetry(Supplier<T> operation) {
        return executeWithRetry(operation, defaultPolicy);
    }
    
    @Override
    public <T> T executeWithRetry(Supplier<T> operation, RetryPolicy policy) {
        if (operation == null) {
            throw new IllegalArgumentException("Operation cannot be null");
        }
        
        if (policy == null) {
            policy = defaultPolicy;
        }
        
        int maxAttempts = policy.getMaxAttempts();
        Throwable lastException = null;
        
        for (int attempt = 0; attempt < maxAttempts; attempt++) {
            try {
                if (attempt > 0) {
                    Duration delay = policy.calculateDelay(attempt - 1);
                    log.debug("Retrying operation (attempt {}/{}), waiting for {} ms", 
                        attempt + 1, maxAttempts, delay.toMillis());
                    Thread.sleep(delay.toMillis());
                }
                
                return operation.get();
                
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RetryException("Retry interrupted", e, attempt);
                
            } catch (Throwable e) {
                lastException = e;
                
                if (!policy.isRetryableException(e)) {
                    log.debug("Non-retryable exception: {}", e.getMessage());
                    throw new RetryException(
                        "Operation failed with non-retryable exception", e, attempt + 1);
                }
                
                log.debug("Operation failed (attempt {}/{}): {}", 
                    attempt + 1, maxAttempts, e.getMessage());
                    
                if (attempt == maxAttempts - 1) {
                    log.warn("All retry attempts failed");
                }
            }
        }
        
        throw new RetryException(
            "Operation failed after " + maxAttempts + " attempts", 
            lastException, maxAttempts);
    }
    
    @Override
    public void configureDefaultRetryPolicy(RetryPolicy policy) {
        if (policy != null) {
            this.defaultPolicy = policy;
        }
    }
} 