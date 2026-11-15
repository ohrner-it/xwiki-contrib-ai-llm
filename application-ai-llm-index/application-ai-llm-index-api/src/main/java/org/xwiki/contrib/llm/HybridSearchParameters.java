/*
 * See the NOTICE file distributed with this work for additional
 * information regarding copyright ownership.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package org.xwiki.contrib.llm;

import java.util.Locale;

import org.xwiki.stability.Unstable;

/**
 * The parameters of a hybrid search in collections, see
 * {@link CollectionManager#hybridSearch(String, java.util.List, HybridSearchParameters)}.
 *
 * @param limitSemanticSimilarity the maximum number of results of the semantic similarity search; 0 or less for no
 *     semantic search
 * @param limitKeywordSearch the maximum number of results of the keyword search; 0 or less for no keyword search
 * @param locale the content language the returned chunks must be indexed under (exact match on the stored language
 *     field), or {@code null} to search all languages
 * @param minSemanticScore the minimum similarity score of a result of the semantic search, between 0 and 1 on the
 *     scale {@code (1 + cos) / 2} of the cosine similarity; 0 for no minimum
 * @version $Id$
 * @since 1.0.1
 */
@Unstable
public record HybridSearchParameters(int limitSemanticSimilarity, int limitKeywordSearch, Locale locale,
    double minSemanticScore)
{
    /**
     * @param limitSemanticSimilarity the maximum number of results of the semantic similarity search
     * @param limitKeywordSearch the maximum number of results of the keyword search
     * @param locale the content language the returned chunks must be indexed under, or {@code null}
     * @param minSemanticScore the minimum similarity score of a result of the semantic search
     * @throws IllegalArgumentException if the minimum semantic score is not between 0 and 1
     */
    public HybridSearchParameters
    {
        // Written so that NaN is rejected as well.
        if (!(minSemanticScore >= 0 && minSemanticScore <= 1)) {
            throw new IllegalArgumentException(
                "The minimum semantic score must be between 0 and 1, got [%s]".formatted(minSemanticScore));
        }
    }
}
