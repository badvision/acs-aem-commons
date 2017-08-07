/*
 * Copyright 2017 Adobe.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.adobe.acs.commons.mcp.util;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.commons.lang3.StringUtils;

/**
 * String utility methods.
 */
public class StringUtil {

    public static String getFriendlyName(String orig) {
        String parts[] = org.apache.commons.lang.StringUtils.split(orig, "._-");
        if (parts.length == 1) {
            parts = org.apache.commons.lang.StringUtils.splitByCharacterTypeCamelCase(orig);
        }
       return Stream.of(parts).map(String::toLowerCase).map(StringUtils::capitalize).collect(Collectors.joining(" "));
    }    
    
    public static boolean isHex(String str) {
        return str.matches("^[0-9A-Fa-f-]+$");
    }
    
    /**
     * Try parsing a date string many numbers of ways.
     * Formats include YYYY-MM-DD, or local defaults (see the SimpleDateFormat javadocs).
     * Also understands things like "today+7" which is one week from today, "yesterday", "now", "tomorrow" and so on.
     * @param dateStr Date string, which could be one of several formats
     * @return Date if parsed.  Null if not parsed.
     */
    public static Date parseDate(String dateStr) {
        if (dateStr == null || dateStr.isEmpty()) {
            return null;
        }
        dateStr = dateStr.trim().toLowerCase();
        if (dateStr.charAt(0) >= 'a') {
            LocalDateTime temporal = null;
            if (dateStr.startsWith("now")) {
                temporal = LocalDateTime.now();
            } else if (dateStr.startsWith("today")) {
                temporal = LocalDate.now().atStartOfDay();
            } else if (dateStr.startsWith("yesterday")) {
                temporal = LocalDate.now().minusDays(1).atStartOfDay();
            } else if (dateStr.startsWith("tomorrow")) {
                temporal = LocalDate.now().plusDays(1).atStartOfDay();
            }

            if (temporal != null) {
                if (dateStr.contains("+")) {
                    int days = Integer.parseInt(dateStr.substring(dateStr.indexOf('+') + 1).trim());
                    temporal = temporal.plusDays(days);
                } else if (dateStr.contains("-")) {
                    int days = Integer.parseInt(dateStr.substring(dateStr.indexOf('-') + 1).trim());
                    temporal = temporal.minusDays(days);
                }
                return Date.from(temporal.atZone(ZoneId.systemDefault()).toInstant());
            }
        }
        try {
            SimpleDateFormat.getDateInstance().parse(dateStr);
        } catch (ParseException ex) {
            return Date.from(LocalDate.parse(dateStr).atStartOfDay().atZone(ZoneId.systemDefault()).toInstant());
        }
        return null;
    }    
    
    private StringUtil() {
        // Utility class has no constructuor.
    }
}
