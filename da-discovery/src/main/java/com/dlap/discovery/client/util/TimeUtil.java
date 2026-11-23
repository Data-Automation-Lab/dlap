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

package com.dlap.discovery.client.util;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;

public class TimeUtil {
    
    public static Instant getCutoffDate(String dateRange) {
        Instant now = Instant.now();
        switch (dateRange) {
            case "24h":
                return now.minus(24, ChronoUnit.HOURS);
            case "7d":
                return now.minus(7, ChronoUnit.DAYS);
            case "30d":
                return now.minus(30, ChronoUnit.DAYS);
            default:
                return Instant.EPOCH;
        }
    }

    public static String formatRelativeTime(Instant instant) {
        Instant now = Instant.now();
        long diffInMinutes = ChronoUnit.MINUTES.between(instant, now);
        
        if (diffInMinutes < 60) {
            return diffInMinutes + " minutes ago";
        } else if (diffInMinutes < 1440) {
            long hours = diffInMinutes / 60;
            return hours + " hour" + (hours > 1 ? "s" : "") + " ago";
        } else {
            long days = diffInMinutes / 1440;
            return days + " day" + (days > 1 ? "s" : "") + " ago";
        }
    }

    public static LocalDate instantToLocalDate(Instant instant) {
        return instant.atZone(ZoneId.systemDefault()).toLocalDate();
    }
} 