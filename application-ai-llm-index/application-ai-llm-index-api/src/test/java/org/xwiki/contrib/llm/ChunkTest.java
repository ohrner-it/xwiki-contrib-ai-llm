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

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Unit test for {@link Chunk}.
 *
 * @version $Id$
 */
class ChunkTest
{
    @Test
    void chunksDifferingOnlyInTheirTranslationFieldsAreNotEqual()
    {
        Chunk withoutTranslations = chunk();
        Chunk withTranslations = chunk();
        withTranslations.setTranslationLocales(List.of("de"));

        // The indexer only stores chunks that differ from the stored ones: a change of the translations must count.
        assertNotEquals(withoutTranslations, withTranslations);
        withoutTranslations.setTranslationLocales(List.of("de"));
        assertEquals(withoutTranslations, withTranslations);
        assertEquals(withoutTranslations.hashCode(), withTranslations.hashCode());

        withTranslations.setTranslation(true);
        assertNotEquals(withoutTranslations, withTranslations);
    }

    private static Chunk chunk()
    {
        Chunk chunk = new Chunk();
        chunk.initialize("wiki:Space.Page;", "collection", "url", "en", 0, 4, "text");
        return chunk;
    }
}
