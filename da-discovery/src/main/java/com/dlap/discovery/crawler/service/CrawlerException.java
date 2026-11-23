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

import java.util.HashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.dlap.discovery.common.exception.DiscoveryException;
import com.dlap.discovery.crawler.exception.ErrorCode;

/**
 * Exception thrown when there's an error during the resource crawling/discovery
 * process.
 * This includes crawler initialization errors, execution failures, etc.
 */
public class CrawlerException extends DiscoveryException {

	private static final Logger log = LoggerFactory.getLogger(CrawlerException.class);
	private static final long serialVersionUID = 1L;

	/**
	 * Creates a new CrawlerException with the specified error code and default
	 * message.
	 * 
	 * @param errorCode The specific crawler error code
	 */
	public CrawlerException(ErrorCode errorCode) {
		super(errorCode);
		validateErrorCode(errorCode);
	}

	/**
	 * Creates a new CrawlerException with a custom message.
	 * 
	 * @param errorCode The specific crawler error code
	 * @param message   A user-friendly error message
	 */
	public CrawlerException(ErrorCode errorCode, String message) {
		super(errorCode, message);
		validateErrorCode(errorCode);
	}

	/**
	 * Creates a new CrawlerException with a specific crawler and operation info.
	 * 
	 * @param errorCode   The specific crawler error code
	 * @param crawlerName The name of the crawler (e.g., "GCSResourceCrawler")
	 * @param jobId       The ID of the job that was executing (can be null)
	 * @param operation   The operation that failed (e.g., "list buckets")
	 */
	public CrawlerException(ErrorCode errorCode, String crawlerName, String jobId, String operation) {
		super(errorCode,
				formatMessage(crawlerName, jobId, operation, null),
				createDetails(crawlerName, jobId, operation, null));
		validateErrorCode(errorCode);
	}

	/**
	 * Creates a new CrawlerException with a specific crawler, operation info, and
	 * cause.
	 * 
	 * @param errorCode   The specific crawler error code
	 * @param crawlerName The name of the crawler (e.g., "GCSResourceCrawler")
	 * @param jobId       The ID of the job that was executing (can be null)
	 * @param operation   The operation that failed (e.g., "list buckets")
	 * @param cause       The underlying exception
	 */
	public CrawlerException(ErrorCode errorCode, String crawlerName, String jobId,
			String operation, Throwable cause) {
		super(errorCode,
				formatMessage(crawlerName, jobId, operation, cause.getMessage()),
				createDetails(crawlerName, jobId, operation, cause.getMessage()),
				cause);
		validateErrorCode(errorCode);
	}

	/**
	 * Validates that the error code is appropriate for a crawler exception.
	 */
	private void validateErrorCode(ErrorCode errorCode) {
		// Ensure the error code is in the crawler range (5000-5999)
		int code = errorCode.getCode();
		if (code < 5000 || code > 5999) {
			// Just log a warning, don't throw an exception as that would be confusing
			log.warn("CrawlerException created with non-crawler error code: {}", code);
		}
	}

	private static String formatMessage(String crawlerName, String jobId, String operation, String errorDetails) {
		StringBuilder msg = new StringBuilder("Crawler error");

		if (crawlerName != null) {
			msg.append(" in ").append(crawlerName);
		}

		if (jobId != null) {
			msg.append(" (job: ").append(jobId).append(")");
		}

		if (operation != null) {
			msg.append(" during operation: ").append(operation);
		}

		if (errorDetails != null) {
			msg.append(" - ").append(errorDetails);
		}

		return msg.toString();
	}

	private static Map<String, Object> createDetails(String crawlerName, String jobId,
			String operation, String errorDetails) {
		Map<String, Object> details = new HashMap<>();

		if (crawlerName != null) {
			details.put("crawlerName", crawlerName);
		}

		if (jobId != null) {
			details.put("jobId", jobId);
		}

		if (operation != null) {
			details.put("operation", operation);
		}

		if (errorDetails != null) {
			details.put("errorDetails", errorDetails);
		}

		return details;
	}
}