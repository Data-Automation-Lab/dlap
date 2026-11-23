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

package com.dlap.catalog.dto;

import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * One page of {@code GET /api/v1/catalog/assets/activity}. It is the Spring Data page the endpoint
 * has always written (so offset callers read the same fields) plus {@code nextAfter}, the cursor of
 * keyset paging (Q-445, D45).
 *
 * <p>A keyset walk visits the assets in {@code assetUri} ascending order: the caller sends
 * {@code after=<nextAfter of the previous page>} and {@code size=N} and stops when {@code nextAfter}
 * is {@code null}. Because the cursor is a value and not a position, an asset inserted or removed
 * during the walk can neither shift a page onto its neighbour nor make the walk skip or repeat an
 * asset that stays in the table. An asset inserted ahead of the cursor is visited, one inserted
 * behind it is not.
 *
 * <p>{@code nextAfter} is written on an offset page too, when the page is ordered by
 * {@code assetUri} ascending (the default), so a walk can start with an ordinary first page. On a
 * page ordered otherwise it is {@code null}; read {@code last} to tell the end of such a walk. In
 * keyset mode {@code number} is 0 and {@code first} is true only for the page that had no cursor;
 * {@code totalElements} and {@code totalPages} count the whole filtered result, as on an offset page.
 */
public class AssetActivityPageDTO extends PageImpl<AssetActivityRowDTO> {

    private static final long serialVersionUID = 1L;

    private final String nextAfter;
    private final boolean firstPage;
    private final boolean more;
    private final long matching;

    /**
     * Creates a page.
     *
     * @param content   the rows of this page
     * @param pageable  the window of this page
     * @param total     rows over all pages that match the filters
     * @param nextAfter cursor of the next page, or {@code null} on the last page or when the page
     *                  is not ordered by {@code assetUri} ascending
     * @param firstPage whether this page started the result (no cursor, page 0)
     * @param more      whether more rows follow this page (the page is not the last)
     */
    public AssetActivityPageDTO(List<AssetActivityRowDTO> content, Pageable pageable, long total,
                                String nextAfter, boolean firstPage, boolean more) {
        super(content, pageable, total);
        this.nextAfter = nextAfter;
        this.firstPage = firstPage;
        this.more = more;
        this.matching = total;
    }

    /**
     * Wraps an offset page. The page is untouched; only {@code nextAfter} is added.
     *
     * @param page      the page the service built
     * @param requested the paging the caller asked for; decides whether a cursor is meaningful
     * @return the page with its cursor
     */
    public static AssetActivityPageDTO ofOffsetPage(Page<AssetActivityRowDTO> page, Pageable requested) {
        String next = page.hasNext() && !page.getContent().isEmpty() && isAssetUriAscending(requested)
                ? page.getContent().get(page.getContent().size() - 1).assetUri()
                : null;
        return new AssetActivityPageDTO(page.getContent(), page.getPageable(), page.getTotalElements(), next,
                !page.hasPrevious(), page.hasNext());
    }

    /**
     * Whether a sort puts the assets in {@code assetUri} ascending order. The service adds
     * {@code assetUri} as the last sort key, so only a sort whose first key is {@code assetUri}
     * ascending (or no sort at all) is that order.
     *
     * @param pageable the paging the caller asked for; may be {@code null}
     * @return true for the keyset order
     */
    public static boolean isAssetUriAscending(Pageable pageable) {
        if (pageable == null) {
            return true;
        }
        Sort sort = pageable.getSort();
        if (sort.isUnsorted()) {
            return true;
        }
        Sort.Order first = sort.iterator().next();
        return "assetUri".equals(first.getProperty()) && first.isAscending();
    }

    /**
     * The cursor of the next page.
     *
     * @return the {@code assetUri} of the last row of this page when another page follows in
     *         {@code assetUri} order; otherwise {@code null}
     */
    @Schema(nullable = true, description = "assetUri of the last row of this page; send it as 'after' to get "
            + "the next page. Null on the last page, and on a page not ordered by assetUri ascending.")
    public String getNextAfter() {
        return nextAfter;
    }

    @Override
    public boolean isFirst() {
        return firstPage;
    }

    /** A keyset page has no position, so "more" is what the lookahead row found, not a page count. */
    @Override
    public boolean hasNext() {
        return more;
    }

    /** The exact count of the filtered result; the parent would shrink it on a short keyset page. */
    @Override
    public long getTotalElements() {
        return matching;
    }

    @Override
    public int getTotalPages() {
        return getSize() == 0 ? 1 : (int) Math.ceil((double) matching / (double) getSize());
    }
}
