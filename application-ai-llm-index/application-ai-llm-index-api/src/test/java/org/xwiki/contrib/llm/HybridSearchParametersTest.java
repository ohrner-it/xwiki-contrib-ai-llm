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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Unit test for {@link HybridSearchParameters}.
 *
 * @version $Id$
 */
class HybridSearchParametersTest
{
    @ParameterizedTest
    @ValueSource(doubles = { 0, 0.7, 1 })
    void minimumSemanticScoreBetweenZeroAndOneIsAccepted(double minSemanticScore)
    {
        assertEquals(minSemanticScore, new HybridSearchParameters(3, 7, null, minSemanticScore).minSemanticScore());
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = { "-0.1 | The minimum semantic score must be between 0 and 1, got [-0.1]",
        "1.1 | The minimum semantic score must be between 0 and 1, got [1.1]",
        "NaN | The minimum semantic score must be between 0 and 1, got [NaN]" })
    void minimumSemanticScoreOutsideZeroAndOneIsRejected(double minSemanticScore, String expectedMessage)
    {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
            () -> new HybridSearchParameters(3, 7, null, minSemanticScore));

        assertEquals(expectedMessage, exception.getMessage());
    }

    @Test
    void negativeLimitsAreAccepted()
    {
        // A limit of 0 or less means that the search is not performed, as for the other hybrid search methods.
        assertEquals(-1, new HybridSearchParameters(-1, 7, null, 0).limitSemanticSimilarity());
    }
}
