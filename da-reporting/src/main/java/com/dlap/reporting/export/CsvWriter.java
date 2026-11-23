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

package com.dlap.reporting.export;

import java.util.Iterator;

/**
 * Minimal CSV cell and row writer that is safe to open in a spreadsheet.
 *
 * <p>Two layers of escaping are applied to every text cell:
 * <ol>
 *   <li><b>Formula neutralisation.</b> A cell whose first character is one of
 *       {@code = + - @} TAB or CR is treated as a possible spreadsheet formula
 *       (CSV injection, OWASP "CSV Injection"). A single quote is prepended so
 *       Excel, LibreOffice and Google Sheets read the cell as text. Report
 *       findings carry asset names, labels and user-supplied descriptions, so
 *       any cell can be attacker-controlled.</li>
 *   <li><b>RFC 4180 quoting.</b> A cell that contains a comma, a double quote,
 *       CR or LF is wrapped in double quotes and embedded quotes are doubled.</li>
 * </ol>
 *
 * <p>{@link Number} and {@link Boolean} values are written as-is: their string
 * form cannot hold a formula, and a negative number must stay numeric. Every
 * other value, including header names, is rendered with {@link String#valueOf}
 * and escaped as text. A {@code null} cell is an empty field. Rows end with
 * {@code \n}.
 */
public final class CsvWriter {

    private static final char FORMULA_GUARD = '\'';

    private CsvWriter() {
        // static helpers only
    }

    /**
     * Render one cell: neutralise formula triggers, then apply RFC 4180 quoting.
     *
     * @param value the cell value; {@code null} renders as an empty field
     * @return the cell text, ready to be placed between commas
     */
    public static String cell(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof Number || value instanceof Boolean) {
            return String.valueOf(value);
        }
        String text = String.valueOf(value);
        if (startsWithFormulaTrigger(text)) {
            text = FORMULA_GUARD + text;
        }
        return quoteIfNeeded(text);
    }

    /**
     * Render one row: each cell through {@link #cell(Object)}, joined by commas,
     * terminated by a line feed.
     *
     * @param cells the row values in column order
     * @return the CSV line including the trailing {@code \n}
     */
    public static String row(Iterable<?> cells) {
        StringBuilder sb = new StringBuilder();
        Iterator<?> it = cells.iterator();
        while (it.hasNext()) {
            sb.append(cell(it.next()));
            if (it.hasNext()) {
                sb.append(',');
            }
        }
        return sb.append('\n').toString();
    }

    /**
     * Whether a text cell would be read as a formula by a spreadsheet.
     *
     * @param text the cell text
     * @return {@code true} when the first character is {@code = + - @}, TAB or CR
     */
    public static boolean startsWithFormulaTrigger(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        return switch (text.charAt(0)) {
            case '=', '+', '-', '@', '\t', '\r' -> true;
            default -> false;
        };
    }

    private static String quoteIfNeeded(String text) {
        boolean needsQuoting = text.indexOf(',') >= 0 || text.indexOf('"') >= 0
                || text.indexOf('\n') >= 0 || text.indexOf('\r') >= 0;
        if (!needsQuoting) {
            return text;
        }
        return "\"" + text.replace("\"", "\"\"") + "\"";
    }
}
