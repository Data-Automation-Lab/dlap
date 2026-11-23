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

package com.dlap.policyengine.client.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.deser.std.StdScalarDeserializer;

/**
 * A Spring Data page as da-catalog serialises it. The catalog writes the direct {@code PageImpl}
 * shape ({@code content}, {@code number}, {@code totalPages}, {@code last}; see
 * {@code docs/api/openapi/catalog.json} {@code PageAssetActivityRowDTO}). The nested {@code page}
 * block of Spring Data's {@code VIA_DTO} mode is read too, so a change of serialisation mode on
 * the catalog side does not silently end a sweep after the first page.
 *
 * @param content    the rows of this page
 * @param number     zero-based page number (direct shape)
 * @param totalPages total page count (direct shape)
 * @param last       whether this is the last page (direct shape)
 * @param page       page metadata ({@code VIA_DTO} shape)
 * @param nextAfter  keyset cursor of the activity endpoint (POL-05, Q-445): the {@code assetUri} to send as
 *                   {@code after} to get the next page, empty text when the catalog sent the field as
 *                   {@code null} (the last page), and {@code null} when the field is absent (a catalog that
 *                   pages by offset only). Read it through {@link #keysetAnswered()} and {@link #nextCursor()}
 * @param <T>        row type
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CatalogPageDTO<T>(List<T> content, Integer number, Integer totalPages, Boolean last,
                                PageMetadata page,
                                @JsonDeserialize(using = NextAfterDeserializer.class) String nextAfter) {

    /**
     * Copies the row list.
     */
    public CatalogPageDTO {
        content = content == null ? List.of() : List.copyOf(content);
    }

    /**
     * A page of an offset-only catalog: no keyset cursor in the answer.
     *
     * @param content    the rows of this page
     * @param number     zero-based page number (direct shape)
     * @param totalPages total page count (direct shape)
     * @param last       whether this is the last page (direct shape)
     * @param page       page metadata ({@code VIA_DTO} shape)
     */
    public CatalogPageDTO(List<T> content, Integer number, Integer totalPages, Boolean last, PageMetadata page) {
        this(content, number, totalPages, last, page, null);
    }

    /**
     * Whether the catalog answered with a keyset cursor field, null or not. That is the catalog saying it
     * pages by {@code assetUri} (POL-05, Q-445); a reader that sees it follows {@link #nextCursor()} and
     * ignores the offset fields.
     *
     * @return true when {@code nextAfter} was present in the answer
     */
    public boolean keysetAnswered() {
        return nextAfter != null;
    }

    /**
     * The cursor of the next page.
     *
     * @return the {@code assetUri} to send as {@code after}, or {@code null} when this is the last page or
     *         the answer carries no cursor
     */
    public String nextCursor() {
        return nextAfter == null || nextAfter.isBlank() ? null : nextAfter;
    }

    /**
     * Whether a page follows this one.
     *
     * @return false on the last page, on an empty page, or when the shape carries no paging data
     */
    public boolean hasNext() {
        if (content.isEmpty()) {
            return false;
        }
        if (last != null) {
            return !last;
        }
        Integer n = number != null ? number : page != null ? page.number() : null;
        Integer total = totalPages != null ? totalPages : page != null ? page.totalPages() : null;
        return n != null && total != null && n + 1 < total;
    }

    /**
     * Whether the response carries paging data in either shape. A page without it cannot say
     * whether more rows follow, so a reader that must see every row treats it as a failure.
     *
     * @return true when {@code last}, {@code number} and {@code totalPages}, or {@code page} is present
     */
    public boolean pagingKnown() {
        return last != null || (number != null && totalPages != null)
                || (page != null && page.number() != null && page.totalPages() != null);
    }

    /**
     * Reads {@code nextAfter} so that a field sent as {@code null} (the last keyset page) reads as the empty
     * text and a field that is absent (an offset-only catalog) reads as Java {@code null}. Jackson's own
     * null handling cannot tell the two apart for a record component.
     */
    static final class NextAfterDeserializer extends StdScalarDeserializer<String> {

        private static final long serialVersionUID = 1L;

        NextAfterDeserializer() {
            super(String.class);
        }

        @Override
        public String deserialize(JsonParser parser, DeserializationContext context) throws java.io.IOException {
            return parser.getValueAsString();
        }

        @Override
        public String getNullValue(DeserializationContext context) {
            return "";
        }

        @Override
        public Object getAbsentValue(DeserializationContext context) {
            return null;
        }
    }

    /**
     * Page metadata of Spring Data's {@code VIA_DTO} serialisation.
     *
     * @param size          page size
     * @param number        zero-based page number
     * @param totalElements total row count
     * @param totalPages    total page count
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PageMetadata(Integer size, Integer number, Long totalElements, Integer totalPages) {
    }
}
