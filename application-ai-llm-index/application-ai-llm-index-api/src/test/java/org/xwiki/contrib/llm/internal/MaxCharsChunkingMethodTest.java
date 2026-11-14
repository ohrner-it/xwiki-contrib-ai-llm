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

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.xwiki.contrib.llm.ChunkText;
import org.xwiki.contrib.llm.Collection;
import org.xwiki.contrib.llm.Document;
import org.xwiki.rendering.block.ParagraphBlock;
import org.xwiki.rendering.block.RawBlock;
import org.xwiki.rendering.block.SpaceBlock;
import org.xwiki.rendering.block.WordBlock;
import org.xwiki.rendering.block.XDOM;
import org.xwiki.rendering.syntax.Syntax;
import org.xwiki.test.annotation.ComponentList;
import org.xwiki.test.junit5.mockito.ComponentTest;
import org.xwiki.test.junit5.mockito.InjectMockComponents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

/**
 * Component test for {@link MaxCharsChunkingMethod}.
 *
 * @version $Id$
 */
@ComponentTest
@ComponentList(IndexTextRenderer.class)
class MaxCharsChunkingMethodTest
{
    private static final String TEST_CONTENT = """
        = Heading 1 =

        This is a paragraph.

        == Heading 2 ==

        Another paragraph.

        ## Markdown heading

        Markdown paragraph.
        Second line of the paragraph.
        Third line of the paragraph.

        Heading
        =======

        A sentence in this paragraph. And a very very very long sentence that just doesn't end and needs to be split.\
        """;

    @InjectMockComponents
    private MaxCharsChunkingMethod chunkingMethod;

    @Mock
    private Collection collection;

    @Mock
    private Document document;

    @BeforeEach
    void setUp()
    {
        when(this.document.getIndexableXDOM())
            .thenReturn(new XDOM(List.of(new RawBlock(TEST_CONTENT, Syntax.PLAIN_1_0))));
    }

    @Test
    void chunkWithoutOverlap()
    {
        when(this.collection.getChunkingMaxSize()).thenReturn(60);
        when(this.collection.getChunkingOverlapOffset()).thenReturn(0);

        List<ChunkText> chunks = this.chunkingMethod.chunk(this.collection, this.document);

        List<String> expectedChunks = List.of("""
            = Heading 1 =

            This is a paragraph.

            """, """
            == Heading 2 ==

            Another paragraph.

            """, """
            ## Markdown heading

            Markdown paragraph.
            """, """
            Second line of the paragraph.
            Third line of the paragraph.

            """, """
            Heading
            =======

            A sentence in this paragraph.\s""",
            "And a very very very long sentence that just doesn't end ", "and needs to be split.");
        assertEquals(expectedChunks, chunks.stream().map(ChunkText::content).toList());
    }

    @Test
    void chunkWithOverlap()
    {
        when(this.collection.getChunkingMaxSize()).thenReturn(80);
        when(this.collection.getChunkingOverlapOffset()).thenReturn(30);

        List<ChunkText> chunks = this.chunkingMethod.chunk(this.collection, this.document);

        List<String> expectedChunks = List.of("""
            = Heading 1 =

            This is a paragraph.

            == Heading 2 ==

            Another paragraph.

            """, """
            Another paragraph.

            ## Markdown heading

            """, """
            ## Markdown heading

            Markdown paragraph.
            Second line of the paragraph.
            """, """
            line of the paragraph.
            Third line of the paragraph.

            """, """
            line of the paragraph.

            Heading
            =======

            """,
            """
            Heading
            =======

            A sentence in this paragraph.\s""",
            "sentence in this paragraph. And a very very very long sentence that just ",
            "very long sentence that just doesn't end and needs to be split.");
        assertEquals(expectedChunks, chunks.stream().map(ChunkText::content).toList());
    }

    @Test
    void positionsAreTheRangeOfTheChunkInTheContent()
    {
        when(this.collection.getChunkingMaxSize()).thenReturn(80);
        when(this.collection.getChunkingOverlapOffset()).thenReturn(30);

        List<ChunkText> chunks = this.chunkingMethod.chunk(this.collection, this.document);

        assertEquals(0, chunks.get(0).posFirstChar());
        assertEquals(TEST_CONTENT.length(), chunks.get(chunks.size() - 1).posLastChar());
        for (ChunkText chunk : chunks) {
            assertEquals(TEST_CONTENT.substring(chunk.posFirstChar(), chunk.posLastChar()), chunk.content(),
                "The content of the chunk starting at %d is not the content at its positions"
                    .formatted(chunk.posFirstChar()));
        }
    }

    @Test
    void titleIsTheFirstHeadingOfTheText()
    {
        when(this.collection.getChunkingMaxSize()).thenReturn(100);
        when(this.collection.getChunkingOverlapOffset()).thenReturn(0);
        when(this.document.getTitle()).thenReturn("Employee handbook");
        when(this.document.getIndexableXDOM()).thenReturn(new XDOM(
            List.of(new ParagraphBlock(List.of(new WordBlock("Laptops"), new SpaceBlock(), new WordBlock("first."))))));

        List<ChunkText> chunks = this.chunkingMethod.chunk(this.collection, this.document);

        assertEquals(List.of(new ChunkText("# Employee handbook\n\nLaptops first.", 0, 35)), chunks);
    }

    @Test
    void documentWithOnlyATitleIsTheTitle()
    {
        when(this.collection.getChunkingMaxSize()).thenReturn(100);
        when(this.collection.getChunkingOverlapOffset()).thenReturn(0);
        when(this.document.getTitle()).thenReturn("Empty page");
        when(this.document.getIndexableXDOM()).thenReturn(new XDOM(List.of()));

        assertEquals(List.of(new ChunkText("# Empty page", 0, 12)), this.chunkingMethod.chunk(this.collection,
            this.document));
    }
}
