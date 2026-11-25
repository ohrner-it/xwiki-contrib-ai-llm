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

import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.xwiki.contrib.llm.openai.Context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests the default methods of {@link CollectionManager}, for implementations that do not override them.
 *
 * @version $Id$
 */
class CollectionManagerTest
{
    private static final String QUERY = "query";

    private static final List<String> COLLECTIONS = List.of("handbook");

    private final CollectionManager collectionManager = mock();

    @BeforeEach
    void setUp() throws Exception
    {
        when(this.collectionManager.hybridSearch(anyString(), anyList(), any(HybridSearchParameters.class)))
            .thenCallRealMethod();
    }

    @Test
    void hybridSearchWithoutMinimumDelegatesTheLimitsAndTheLocale() throws Exception
    {
        List<Context> results = List.of(new Context("handbook", "doc", "fr", "url", "content", 0.5, null));
        when(this.collectionManager.hybridSearch(QUERY, COLLECTIONS, 4, 3, Locale.FRENCH)).thenReturn(results);

        HybridSearchParameters parameters = new HybridSearchParameters(4, 3, Locale.FRENCH, 0);

        assertEquals(results, this.collectionManager.hybridSearch(QUERY, COLLECTIONS, parameters));
    }

    @Test
    void hybridSearchWithMinimumIsNotSupported() throws Exception
    {
        HybridSearchParameters parameters = new HybridSearchParameters(4, 3, null, 0.5);

        assertThrows(UnsupportedOperationException.class,
            () -> this.collectionManager.hybridSearch(QUERY, COLLECTIONS, parameters));
        verify(this.collectionManager, never()).hybridSearch(anyString(), anyList(), anyInt(), anyInt(), any());
    }
}
