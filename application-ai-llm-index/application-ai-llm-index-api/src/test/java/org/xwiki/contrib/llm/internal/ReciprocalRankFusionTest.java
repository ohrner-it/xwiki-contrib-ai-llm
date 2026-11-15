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
package org.xwiki.contrib.llm.internal;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.xwiki.contrib.llm.openai.Context;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Unit test for {@link ReciprocalRankFusion}.
 *
 * @version $Id$
 */
class ReciprocalRankFusionTest
{
    private static final double RANK_1 = 1.0 / 61;

    private static final double RANK_2 = 1.0 / 62;

    private static Context context(String content, double score)
    {
        return context(content, "document-" + content, score);
    }

    private static Context context(String content, String documentId, double score)
    {
        return new Context("collection", documentId, "en", "url-" + documentId, content, score, null);
    }

    @Test
    void chunkFoundByBothListsAddsBothContributionsAndKeepsItsFirstAppearance()
    {
        List<Context> semantic = List.of(context("a", 0.9), context("b", 0.8));
        List<Context> keyword = List.of(context("b", "other-document", 12.0), context("c", 3.0));

        assertEquals(List.of(context("b", RANK_2 + RANK_1), context("a", RANK_1), context("c", RANK_2)),
            ReciprocalRankFusion.fuse(semantic, keyword));
    }

    @Test
    void equalScoresAreOrderedByFirstAppearanceSemanticFirst()
    {
        List<Context> semantic = List.of(context("a", 0.9), context("b", 0.8));
        List<Context> keyword = List.of(context("c", 12.0), context("d", 3.0));

        assertEquals(List.of(context("a", RANK_1), context("c", RANK_1), context("b", RANK_2), context("d", RANK_2)),
            ReciprocalRankFusion.fuse(semantic, keyword));
    }

    @Test
    void equalScoresPreferTheBetterSingleRankOverFirstAppearance()
    {
        // x is 62nd in both lists: 1/122 + 1/122 is exactly 1/61, the score of y, 1st in the keyword list only. x
        // appears first (in the semantic list), y has the better rank.
        List<Context> semantic = new ArrayList<>();
        List<Context> keyword = new ArrayList<>(List.of(context("y", 9.0)));
        for (int i = 1; i <= 61; i++) {
            semantic.add(context("s" + i, 0.5));
        }
        for (int i = 2; i <= 61; i++) {
            keyword.add(context("k" + i, 1.0));
        }
        semantic.add(context("x", 0.1));
        keyword.add(context("x", 0.1));

        assertEquals(List.of(context("s1", RANK_1), context("y", RANK_1), context("x", 1.0 / 122 + 1.0 / 122)),
            ReciprocalRankFusion.fuse(semantic, keyword).subList(0, 3));
    }

    @Test
    void duplicateContentWithinAListCountsItsBestRankOnly()
    {
        List<Context> semantic = List.of(context("a", 0.9), context("a", 0.8), context("b", 0.7));

        assertEquals(List.of(context("a", RANK_1), context("b", 1.0 / 63)),
            ReciprocalRankFusion.fuse(semantic, List.of()));
    }
}
