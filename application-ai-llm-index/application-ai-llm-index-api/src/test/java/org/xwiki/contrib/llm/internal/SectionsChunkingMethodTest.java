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

import java.io.StringReader;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.xwiki.contrib.llm.ChunkText;
import org.xwiki.contrib.llm.ChunkingMethod;
import org.xwiki.contrib.llm.Collection;
import org.xwiki.contrib.llm.Document;
import org.xwiki.rendering.block.HeaderBlock;
import org.xwiki.rendering.block.ParagraphBlock;
import org.xwiki.rendering.block.WordBlock;
import org.xwiki.rendering.block.XDOM;
import org.xwiki.rendering.listener.HeaderLevel;
import org.xwiki.rendering.parser.Parser;
import org.xwiki.rendering.syntax.Syntax;
import org.xwiki.test.annotation.AllComponents;
import org.xwiki.test.junit5.mockito.ComponentTest;
import org.xwiki.test.junit5.mockito.InjectComponentManager;
import org.xwiki.test.mockito.MockitoComponentManager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

/**
 * Component test for {@link SectionsChunkingMethod}, on documents parsed from XWiki 2.1 sources and rendered with the
 * real {@link IndexTextRenderer}.
 *
 * @version $Id$
 */
@ComponentTest
@AllComponents
class SectionsChunkingMethodTest
{
    private static final String P1 = "Alpha " + "a".repeat(34);

    private static final String P2 = "Beta " + "b".repeat(35);

    private static final String P3 = "Gamma " + "c".repeat(34);

    private static final String S1 = "First paragraph here. It has two sentences.";

    private static final String S2 = "Second paragraph is short.";

    private static final String S3 = "Third paragraph closes the section now.";

    private static final String LONG_PARAGRAPH =
        "Long paragraph number 1 goes here. Long paragraph number 2 goes here. Long paragraph number 3 goes here.";

    @InjectComponentManager
    private MockitoComponentManager componentManager;

    @Mock
    private Collection collection;

    @Mock
    private Document document;

    private ChunkingMethod chunkingMethod;

    @BeforeEach
    void setUp() throws Exception
    {
        this.chunkingMethod = this.componentManager.getInstance(ChunkingMethod.class, "sections");
        when(this.document.getBreadcrumb()).thenReturn(List.of("Page"));
        when(this.document.getLanguage()).thenReturn("en");
        when(this.collection.getChunkingOverlapOffset()).thenReturn(0);
    }

    @Test
    void smallDocumentIsOneChunkWithItsHeadings() throws Exception
    {
        setUpDocument("Intro text.\n\n= A =\n\nText A.\n\n== A1 ==\n\nText A1.", 1000);
        when(this.document.getBreadcrumb()).thenReturn(List.of("Space", "Page"));

        assertChunks(List.of("Space > Page\n\nIntro text.\n\n# A\n\nText A.\n\n## A1\n\nText A1."));
    }

    @Test
    void sectionsThatDoNotFitTogetherHaveTheirHeadingsAsBreadcrumb() throws Exception
    {
        setUpDocument("= A =\n\n%s\n\n== A1 ==\n\n%s\n\n= B =\n\n%s".formatted(P1, P2, P3), 80);

        List<ChunkText> chunks = this.chunkingMethod.chunk(this.collection, this.document);

        // The text of the document is "# A", P1, "## A1", P2, "# B", P3, separated by empty lines.
        assertEquals(List.of(new ChunkText("Page > A\n\n" + P1, 5, 45), new ChunkText("Page > A > A1\n\n" + P2, 54, 94),
            new ChunkText("Page > B\n\n" + P3, 101, 141)), chunks);
    }

    @Test
    void flatHeadingsAreSectionsToo() throws Exception
    {
        XDOM xdom = new XDOM(List.of(header(HeaderLevel.LEVEL1, "A"), paragraph(P1), header(HeaderLevel.LEVEL2, "A1"),
            paragraph(P2), header(HeaderLevel.LEVEL1, "B"), paragraph(P3)));
        when(this.document.getIndexableXDOM()).thenReturn(xdom);
        when(this.collection.getChunkingMaxSize()).thenReturn(80);

        assertChunks(List.of("Page > A\n\n" + P1, "Page > A > A1\n\n" + P2, "Page > B\n\n" + P3));
    }

    @Test
    void siblingSectionsAreMergedButNotAcrossBranches() throws Exception
    {
        setUpDocument("= A =\n\n== A1 ==\n\nOne.\n\n== A2 ==\n\nTwo.\n\n= B =\n\n" + P3, 60);

        assertChunks(List.of("Page > A\n\n## A1\n\nOne.\n\n## A2\n\nTwo.", "Page > B\n\n" + P3));
    }

    @Test
    void largeParagraphIsSplitAtSentences() throws Exception
    {
        setUpDocument("= A =\n\nThe first sentence is here. The second one follows now. The third ends it.", 50);

        assertChunks(List.of("Page > A\n\nThe first sentence is here.", "Page > A\n\nThe second one follows now.",
            "Page > A\n\nThe third ends it."));
    }

    @Test
    void partsOfASplitParagraphOverlap() throws Exception
    {
        setUpDocument("= A =\n\nThe first sentence is here. The second one follows now. The third ends it.", 50);
        when(this.collection.getChunkingOverlapOffset()).thenReturn(10);

        assertChunks(List.of("Page > A\n\nThe first sentence is here.",
            "Page > A\n\nis here. The second one follows now.", "Page > A\n\nnow. The third ends it."));
    }

    @Test
    void chunksOfASectionSplitBetweenBlocksOverlapByWholeLines() throws Exception
    {
        setUpDocument("= A =\n\n%s\n\n%s\n\n%s".formatted(S1, S2, S3), 100);
        when(this.collection.getChunkingOverlapOffset()).thenReturn(30);

        List<ChunkText> chunks = this.chunkingMethod.chunk(this.collection, this.document);

        // The text of the document is "# A", S1, S2, S3, separated by empty lines: S1 is at 5 to 48, S2 at 50 to 76,
        // S3 at 78 to 117. S2 is shorter than the overlap and starts a line, so the second chunk repeats it whole.
        assertEquals(List.of(new ChunkText("Page > A\n\n" + S1 + "\n\n" + S2, 5, 76),
            new ChunkText("Page > A\n\n" + S2 + "\n\n" + S3, 50, 117)), chunks);
    }

    @Test
    void chunksOfASectionSplitBetweenBlocksOverlapBySentences() throws Exception
    {
        setUpDocument("= A =\n\n%s\n\n%s".formatted(S1, S2), 70);
        when(this.collection.getChunkingOverlapOffset()).thenReturn(30);

        List<ChunkText> chunks = this.chunkingMethod.chunk(this.collection, this.document);

        // S1 is longer than the overlap: the second chunk repeats its last sentence, which starts at 48 - 21.
        assertEquals(List.of(new ChunkText("Page > A\n\n" + S1, 5, 48),
            new ChunkText("Page > A\n\nIt has two sentences.\n\n" + S2, 27, 76)), chunks);
    }

    @Test
    void chunksBeforeAndAfterABlockSplitOnItsOwnOverlapToo() throws Exception
    {
        setUpDocument("= A =\n\nShort one.\n\nThe first sentence is here. The second one follows now. The third ends "
            + "it.\n\nShort two.", 50);
        when(this.collection.getChunkingOverlapOffset()).thenReturn(12);

        List<ChunkText> chunks = this.chunkingMethod.chunk(this.collection, this.document);

        // The text of the document is "# A", "Short one." at 5 to 15, the paragraph at 17 to 91 and "Short two." at
        // 93 to 103. "Short one." has neither a line nor a sentence start after its beginning, so only its last word is
        // repeated.
        assertEquals(List.of(new ChunkText("Page > A\n\nShort one.", 5, 15),
            new ChunkText("Page > A\n\none.\n\nThe first sentence is here.", 11, 45),
            new ChunkText("Page > A\n\nis here. The second one follows now.", 36, 73),
            new ChunkText("Page > A\n\nfollows now. The third ends it.", 60, 91),
            new ChunkText("Page > A\n\nends it.\n\nShort two.", 83, 103)), chunks);
    }

    @Test
    void leadIsNotAChunkOfItsOwn() throws Exception
    {
        setUpDocument("= A =\n\nIntro. Nineteen chars yes.\n\nAlpha beta gamma delta. Epsilon zeta eta theta iota.",
            50);
        when(this.collection.getChunkingOverlapOffset()).thenReturn(19);

        // Each piece takes more than half of the new text that fits beside its overlap.
        assertChunks(List.of("Page > A\n\nIntro. Nineteen chars yes.",
            "Page > A\n\nNineteen chars yes.\n\nAlpha beta gamma", "Page > A\n\nAlpha beta gamma delta. Epsilon zeta",
            "Page > A\n\nEpsilon zeta eta theta iota."));
    }

    @Test
    void piecesOfAParagraphWithAnEmptyLineOverlap() throws Exception
    {
        setUpDocument(
            "= A =\n\n* (((\nFirst paragraph of the item is here.\n\nSecond paragraph of the item is here.\n)))", 80);
        when(this.collection.getChunkingOverlapOffset()).thenReturn(15);

        List<ChunkText> chunks = this.chunkingMethod.chunk(this.collection, this.document);

        // The item is split as text after the empty line; the overlap is counted from the end of its first paragraph.
        assertEquals(List.of(new ChunkText("Page > A\n\n- First paragraph of the item is here.", 5, 84),
            new ChunkText("Page > A\n\nitem is here.\n\n  Second paragraph of the item is here.", 5, 84)), chunks);
    }

    @Test
    void tableAfterALeadStillRepeatsItsRows() throws Exception
    {
        setUpDocument("= T =\n\nIntro text. Last bit.\n\n|=H1|=H2\n|a1|b1\n|a2|b2\n|a3|b3\n\nAfter the table.", 60);
        when(this.collection.getChunkingOverlapOffset()).thenReturn(15);

        List<ChunkText> chunks = this.chunkingMethod.chunk(this.collection, this.document);

        // The paragraph is at 5 to 26, its last sentence from 17, the table at 28 to 89 and the last paragraph at 91 to
        // 107. The rows of the table are no text to take a lead from.
        assertEquals(List.of(new ChunkText("Page > T\n\nIntro text. Last bit.", 5, 26),
            new ChunkText("Page > T\n\nLast bit.\n\n| H1 | H2 |\n| --- | --- |\n| a1 | b1 |", 17, 89),
            new ChunkText("Page > T\n\n| H1 | H2 |\n| --- | --- |\n| a1 | b1 |\n| a2 | b2 |", 28, 89),
            new ChunkText("Page > T\n\n| H1 | H2 |\n| --- | --- |\n| a2 | b2 |\n| a3 | b3 |", 28, 89),
            new ChunkText("Page > T\n\nAfter the table.", 91, 107)), chunks);
    }

    @Test
    void leadTakesTheLastWholeParagraphsThatFitButNotAllOfThem() throws Exception
    {
        setUpDocument("= A =\n\nA one.\n\nB two.\n\nC three.\n\n" + LONG_PARAGRAPH, 80);
        when(this.collection.getChunkingOverlapOffset()).thenReturn(30);

        List<ChunkText> chunks = this.chunkingMethod.chunk(this.collection, this.document);

        // "A one." is at 5 to 11, "B two." at 13 to 19, "C three." at 21 to 29 and the long paragraph at 31 to 135. All
        // three short paragraphs would fit into the overlap, but the lead never repeats a whole chunk.
        assertEquals(List.of(new ChunkText("Page > A\n\nA one.\n\nB two.\n\nC three.", 5, 29),
            new ChunkText("Page > A\n\nB two.\n\nC three.\n\nLong paragraph number 1 goes here.", 13, 66),
            new ChunkText("Page > A\n\nparagraph number 1 goes here. Long paragraph number 2 goes here.", 36, 101),
            new ChunkText("Page > A\n\nparagraph number 2 goes here. Long paragraph number 3 goes here.", 71, 135)),
            chunks);
    }

    @Test
    void codeBlockGivesNoLead() throws Exception
    {
        setUpDocument("= C =\n\n{{{\nline one\nline two\nline three\n}}}\n\nAfter the code block here.", 50);
        when(this.collection.getChunkingOverlapOffset()).thenReturn(20);

        assertChunks(List.of("Page > C\n\n```\nline one\nline two\nline three\n```",
            "Page > C\n\nAfter the code block here."));
    }

    @Test
    void chunksOfDifferentSectionsDoNotOverlap() throws Exception
    {
        setUpDocument("= A =\n\n%s\n\n= B =\n\n%s".formatted(S1, S2), 60);
        when(this.collection.getChunkingOverlapOffset()).thenReturn(30);

        assertChunks(List.of("Page > A\n\n" + S1, "Page > B\n\n" + S2));
    }

    @Test
    void piecesOfATableOverlapByWholeRows() throws Exception
    {
        setUpDocument("= T =\n\n|=H1|=H2\n|a1|b1\n|a2|b2\n|a3|b3", 60);
        when(this.collection.getChunkingOverlapOffset()).thenReturn(15);

        assertChunks(List.of("Page > T\n\n| H1 | H2 |\n| --- | --- |\n| a1 | b1 |\n| a2 | b2 |",
            "Page > T\n\n| H1 | H2 |\n| --- | --- |\n| a2 | b2 |\n| a3 | b3 |"));
    }

    @Test
    void piecesOfAListOverlapByWholeItemsKeepingTheNumbers() throws Exception
    {
        setUpDocument("= L =\n\n1. first item\n1. second item\n1. third item", 40);
        when(this.collection.getChunkingOverlapOffset()).thenReturn(15);

        assertChunks(List.of("Page > L\n\n1. first item\n2. second item", "Page > L\n\n2. second item\n3. third item"));
    }

    @Test
    void piecesOfACodeBlockOverlapByWholeLines() throws Exception
    {
        setUpDocument("= C =\n\n{{{\nline one\nline two\nline three\n}}}", 40);
        when(this.collection.getChunkingOverlapOffset()).thenReturn(10);

        assertChunks(List.of("Page > C\n\n```\nline one\nline two\n```", "Page > C\n\n```\nline two\nline three\n```"));
    }

    @Test
    void largeTableIsSplitBetweenRowsRepeatingItsHeaderRow() throws Exception
    {
        setUpDocument("= T =\n\n|=H1|=H2\n|a1|b1\n|a2|b2\n|a3|b3", 60);

        assertChunks(List.of("Page > T\n\n| H1 | H2 |\n| --- | --- |\n| a1 | b1 |\n| a2 | b2 |",
            "Page > T\n\n| H1 | H2 |\n| --- | --- |\n| a3 | b3 |"));
    }

    @Test
    void largeNumberedListIsSplitBetweenItemsKeepingTheNumbers() throws Exception
    {
        setUpDocument("= L =\n\n1. first item\n1. second item\n1. third item", 40);

        assertChunks(List.of("Page > L\n\n1. first item\n2. second item", "Page > L\n\n3. third item"));
    }

    @Test
    void largeCodeBlockIsSplitBetweenLinesAndFencedAgain() throws Exception
    {
        setUpDocument("= C =\n\n{{{\nline one\nline two\nline three\n}}}", 40);

        assertChunks(List.of("Page > C\n\n```\nline one\nline two\n```", "Page > C\n\n```\nline three\n```"));
    }

    @Test
    void longBreadcrumbIsShortenedInTheMiddle() throws Exception
    {
        setUpDocument("Text.", 80);
        when(this.document.getBreadcrumb())
            .thenReturn(List.of("Wiki", "Very long space name", "Another long space", "Page"));

        assertChunks(List.of("Wiki > … > Page\n\nText."));
    }

    @Test
    void headingWithoutTextOrSubsectionsIsLeftOut() throws Exception
    {
        // The image has no alternative text, so the Poster section has no text, and no section is nested in it.
        setUpDocument(
            "= Movie =\n\nA film.\n\n== Poster ==\n\n[[image:poster.png||width=\"64\"]]\n\n== Runtime ==\n\n87", 1000);

        assertChunks(List.of("Page > Movie\n\nA film.\n\n## Runtime\n\n87"));
    }

    @Test
    void headingWhoseNestedSectionsAreAllWithoutTextIsLeftOutToo() throws Exception
    {
        setUpDocument("= A =\n\nText A.\n\n= B =\n\n== B1 ==\n\n[[image:b1.png]]\n\n= C =\n\nText C.", 1000);

        assertChunks(List.of("Page\n\n# A\n\nText A.\n\n# C\n\nText C."));
    }

    @Test
    void documentWithoutTextHasNoChunks() throws Exception
    {
        setUpDocument("= Only a heading =", 80);

        assertChunks(List.of());
    }

    private void setUpDocument(String source, int maxSize) throws Exception
    {
        Parser parser = this.componentManager.getInstance(Parser.class, Syntax.XWIKI_2_1.toIdString());
        when(this.document.getIndexableXDOM()).thenReturn(parser.parse(new StringReader(source)));
        when(this.collection.getChunkingMaxSize()).thenReturn(maxSize);
    }

    private void assertChunks(List<String> expected) throws Exception
    {
        List<ChunkText> chunks = this.chunkingMethod.chunk(this.collection, this.document);

        assertEquals(expected, chunks.stream().map(ChunkText::content).toList());
    }

    private static HeaderBlock header(HeaderLevel level, String title)
    {
        return new HeaderBlock(List.of(new WordBlock(title)), level);
    }

    private static ParagraphBlock paragraph(String text)
    {
        return new ParagraphBlock(List.of(new WordBlock(text)));
    }

    @Test
    void sectionsOfDifferentBranchesAreNotMergedEvenWhenTheyFit() throws Exception
    {
        setUpDocument("= A =\n\n%s\n\n== A1 ==\n\nOne.\n\n= B =\n\nTwo.".formatted(P1), 60);

        assertChunks(List.of("Page > A\n\n" + P1, "Page > A > A1\n\nOne.", "Page > B\n\nTwo."));
    }

    @Test
    void largeSectionIsSplitBetweenBlocks() throws Exception
    {
        setUpDocument("= A =\n\n%s\n\n%s\n\n%s".formatted(P1, P2, P3), 100);

        List<ChunkText> chunks = this.chunkingMethod.chunk(this.collection, this.document);

        // The text of the document is "# A", P1, P2, P3, separated by empty lines.
        assertEquals(List.of(new ChunkText("Page > A\n\n" + P1 + "\n\n" + P2, 5, 87),
            new ChunkText("Page > A\n\n" + P3, 89, 129)), chunks);
    }

    @Test
    void blockTooLargeForAChunkIsSplitOnItsOwn() throws Exception
    {
        setUpDocument("= A =\n\nShort one.\n\nThe first sentence is here. The second one follows now. The third ends "
            + "it.\n\nShort two.", 50);

        assertChunks(List.of("Page > A\n\nShort one.", "Page > A\n\nThe first sentence is here.",
            "Page > A\n\nThe second one follows now.", "Page > A\n\nThe third ends it.", "Page > A\n\nShort two."));
    }

    @Test
    void headingInsideAGroupDoesNotApplyAfterTheGroup() throws Exception
    {
        setUpDocument("= A =\n\n%s\n\n(((\n== B ==\n\nIn B.\n)))\n\nAfter.\n\n= C =\n\nx".formatted(P1), 60);

        assertChunks(List.of("Page > A\n\n" + P1, "Page > A > B\n\nIn B.", "Page > A\n\nAfter.", "Page > C\n\nx"));
    }

    @Test
    void tableWithOnlyAHeaderRowIsSplitAsText() throws Exception
    {
        String first = "h".repeat(30);
        String second = "k".repeat(30);
        setUpDocument("= T =\n\n|=%s|=%s".formatted(first, second), 60);

        assertChunks(List.of("Page > T\n\n| %s |".formatted(first),
            "Page > T\n\n%s |\n| --- | --- |".formatted(second)));
    }

    @Test
    void breadcrumbWithAVeryLongElementIsCut() throws Exception
    {
        setUpDocument("Text.", 40);
        when(this.document.getBreadcrumb()).thenReturn(List.of("Page", "A very long page title that goes on and on"));

        assertChunks(List.of("Page > A…\n\nText."));
    }

    @Test
    void headingInsideAGroupIsNestedInTheHeadingsAroundTheGroup() throws Exception
    {
        setUpDocument("= A =\n\n%s\n\n(((\n= C =\n\nIn C.\n)))".formatted(P1), 60);

        assertChunks(List.of("Page > A\n\n" + P1, "Page > A > C\n\nIn C."));
    }

    @Test
    void longHeaderRowIsNotRepeated() throws Exception
    {
        setUpDocument("= T =\n\n|=%s|=%s\n|a1|b1\n|a2|b2\n|a3|b3".formatted("x".repeat(20), "y".repeat(20)), 80);

        assertChunks(List.of("Page > T\n\n| %s | %s |\n| --- | --- |".formatted("x".repeat(20), "y".repeat(20)),
            "Page > T\n\n| a1 | b1 |\n| a2 | b2 |\n| a3 | b3 |"));
    }
}
