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
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.xwiki.contrib.llm.openai.Context;

/**
 * Merges the ranked results of the semantic and the keyword search by reciprocal rank fusion: a chunk scores
 * {@code 1 / (60 + rank)} for each list it appears in, ranks starting at 1, and the chunks are ordered by the sum. The
 * scores of the two searches are on unrelated scales (cosine similarity and BM25), the ranks are not. A chunk is
 * identified by its content, so the same text found by both searches adds both contributions; within one list only
 * its best rank counts. The rank is the position in the list as given, duplicates included. Equal sums are ordered by
 * the better single rank, then by first appearance, semantic results first; a chunk found by both searches keeps the
 * collection, document and URL of its first appearance.
 *
 * @version $Id$
 * @since 1.0.1
 */
final class ReciprocalRankFusion
{
    /**
     * The constant of reciprocal rank fusion, which dampens the weight of the first ranks; 60 is the customary value.
     */
    private static final int K = 60;

    private static final class FusedChunk
    {
        private final Context context;

        private double score;

        private int bestRank = Integer.MAX_VALUE;

        FusedChunk(Context context)
        {
            this.context = context;
        }
    }

    private ReciprocalRankFusion()
    {
    }

    /**
     * @param semanticResults the results of the semantic search, best first
     * @param keywordResults the results of the keyword search, best first
     * @return the fused results, best first, each with its fusion score as similarity score
     */
    static List<Context> fuse(List<Context> semanticResults, List<Context> keywordResults)
    {
        Map<String, FusedChunk> chunks = new LinkedHashMap<>();
        addContributions(chunks, semanticResults);
        addContributions(chunks, keywordResults);

        // The sort is stable: chunks that compare equal keep the order of their first appearance.
        List<FusedChunk> sorted = new ArrayList<>(chunks.values());
        sorted.sort(Comparator.comparingDouble((FusedChunk chunk) -> chunk.score).reversed()
            .thenComparingInt(chunk -> chunk.bestRank));
        return sorted.stream().map(chunk -> withScore(chunk.context, chunk.score)).toList();
    }

    private static void addContributions(Map<String, FusedChunk> chunks, List<Context> results)
    {
        Set<String> seenInList = new HashSet<>();
        for (int index = 0; index < results.size(); index++) {
            Context context = results.get(index);
            if (seenInList.add(context.content())) {
                int rank = index + 1;
                FusedChunk chunk = chunks.computeIfAbsent(context.content(), content -> new FusedChunk(context));
                chunk.score += 1.0 / (K + rank);
                chunk.bestRank = Math.min(chunk.bestRank, rank);
            }
        }
    }

    private static Context withScore(Context context, double score)
    {
        return new Context(context.collectionId(), context.documentId(), context.language(), context.url(),
            context.content(), score, context.vector());
    }
}
