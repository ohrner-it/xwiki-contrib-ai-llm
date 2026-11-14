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
import java.util.OptionalInt;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.inject.Named;
import javax.inject.Singleton;

import org.xwiki.component.annotation.Component;
import org.xwiki.contrib.llm.ChunkText;
import org.xwiki.contrib.llm.ChunkingMethod;
import org.xwiki.contrib.llm.Collection;
import org.xwiki.contrib.llm.Document;

/**
 * Splits the content of a document into chunks of at most the maximum chunk size of the collection, preferring to
 * end a chunk before a heading, then at a blank line, a line break, the end of a sentence or a space, as long as the
 * chunk keeps at least half the maximum size. Consecutive chunks overlap by up to the overlap offset of the
 * collection.
 *
 * @version $Id$
 * @since 1.0.2
 */
@Component
@Named(MaxCharsChunkingMethod.NAME)
@Singleton
public class MaxCharsChunkingMethod implements ChunkingMethod
{
    /**
     * The hint of this chunking method.
     */
    public static final String NAME = "maxChars";

    private static final String HEADING_REGEX = "^( *=+[^=\n]|#+|(?:[^\n]*\n(?:=+|-+)$))";

    private static final Pattern HEADING_PATTERN = Pattern.compile(HEADING_REGEX, Pattern.MULTILINE);

    private static final List<String> SEPARATORS = List.of("\n\n", "\n", ". ", " ");

    @Override
    public List<ChunkText> chunk(Collection collection, Document document)
    {
        int maxChunkSize = collection.getChunkingMaxSize();
        int offset = collection.getChunkingOverlapOffset();

        String content = document.getContent();

        List<ChunkText> result = new ArrayList<>();

        int start = 0;
        int end;

        while (start < content.length()) {
            // Find the next index to end the chunk
            end = Math.min(start + maxChunkSize, content.length());

            // Extract the chunk content
            String chunkContent = content.substring(start, end);

            // Truncate the content to a semantic boundary if we're not at the end of the document, yet.
            if (end < content.length()) {
                OptionalInt truncateIndex = findGoodBoundaryAtEnd(chunkContent, maxChunkSize / 2);
                if (truncateIndex.isPresent()) {
                    end = start + truncateIndex.getAsInt();
                    chunkContent = content.substring(start, end);
                }
            }

            result.add(new ChunkText(chunkContent, start, end));

            // Prepare for the next iteration
            if (end < content.length() && offset > 0) {
                OptionalInt goodOverlap =
                    findGoodBoundaryAtStart(chunkContent.substring(chunkContent.length() - offset), offset / 2);
                start = end - offset + goodOverlap.orElse(0);
            } else {
                start = end;
            }
        }

        return result;
    }

    private OptionalInt findGoodBoundaryAtEnd(String chunk, int minimumIndex)
    {
        // Try finding a nicer chunk boundary. Try the following options in order:
        // 1. The start of a section in XWiki syntax or Markdown.
        // 2. A blank line.
        // 3. A linebreak.
        // 4. The end of a sentence (". ").
        // 5. Any space.
        // Truncate to the first option that still gives a chunk that has at least half of the maximum size length.

        Matcher matcher = HEADING_PATTERN.matcher(chunk);
        int headingIndex = -1;
        // Find the last heading.
        while (matcher.find()) {
            headingIndex = matcher.start(1);
        }
        if (headingIndex > minimumIndex) {
            return OptionalInt.of(headingIndex);
        } else {
            for (String separator : SEPARATORS) {
                int separatorIndex = chunk.lastIndexOf(separator) + separator.length();
                if (separatorIndex > minimumIndex) {
                    return OptionalInt.of(separatorIndex);
                }
            }
        }

        return OptionalInt.empty();
    }

    private OptionalInt findGoodBoundaryAtStart(String chunk, int maximumIndex)
    {
        Matcher matcher = HEADING_PATTERN.matcher(chunk);
        int headingIndex = matcher.find() ? matcher.start() : Integer.MAX_VALUE;
        if (headingIndex < maximumIndex) {
            return OptionalInt.of(headingIndex);
        } else {
            for (String separator : SEPARATORS) {
                int separatorIndex = chunk.indexOf(separator);
                if (separatorIndex > -1 && separatorIndex < maximumIndex) {
                    return OptionalInt.of(separatorIndex + separator.length());
                }
            }
        }

        return OptionalInt.empty();
    }
}
