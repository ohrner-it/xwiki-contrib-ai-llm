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
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.xwiki.rendering.block.BulletedListBlock;
import org.xwiki.rendering.block.LinkBlock;
import org.xwiki.rendering.block.ListItemBlock;
import org.xwiki.rendering.block.NumberedListBlock;
import org.xwiki.rendering.block.ParagraphBlock;
import org.xwiki.rendering.block.QuotationBlock;
import org.xwiki.rendering.block.RawBlock;
import org.xwiki.rendering.block.SpaceBlock;
import org.xwiki.rendering.block.WordBlock;
import org.xwiki.rendering.block.XDOM;
import org.xwiki.rendering.listener.reference.DocumentResourceReference;
import org.xwiki.rendering.parser.Parser;
import org.xwiki.rendering.syntax.Syntax;
import org.xwiki.test.annotation.AllComponents;
import org.xwiki.test.junit5.mockito.ComponentTest;
import org.xwiki.test.junit5.mockito.InjectComponentManager;
import org.xwiki.test.mockito.MockitoComponentManager;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Component test for {@link IndexTextRenderer}, on documents parsed from XWiki 2.1 sources.
 *
 * @version $Id$
 */
@ComponentTest
@AllComponents
class IndexTextRendererTest
{
    @InjectComponentManager
    private MockitoComponentManager componentManager;

    private IndexTextRenderer renderer;

    @BeforeEach
    void setUp() throws Exception
    {
        this.renderer = this.componentManager.getInstance(IndexTextRenderer.class);
    }

    @Test
    void headingsAndParagraphs() throws Exception
    {
        assertRendered("# Title\n\nFirst line\nsecond line\n\n## Section\n\nText",
            "= Title =\n\nFirst line\nsecond line\n\n== Section ==\n\nText");
    }

    @Test
    void formattingIsDropped() throws Exception
    {
        assertRendered("bold, italic, underlined and monospace",
            "**bold**, //italic//, __underlined__ and ##monospace##");
    }

    @Test
    void nestedBulletedList() throws Exception
    {
        assertRendered("- a\n  - b\n  - c\n- d", "* a\n** b\n** c\n* d");
    }

    @Test
    void numberedListInsideABulletedList() throws Exception
    {
        assertRendered("Intro\n\n- a\n  1. x\n  2. y\n- b", "Intro\n\n* a\n*1. x\n*1. y\n* b");
    }

    @Test
    void listItemWithSeveralBlocksIndentsTheContinuationLines() throws Exception
    {
        assertRendered("- item\n  continued\n\n  second paragraph", "* (((item\ncontinued\n\nsecond paragraph)))");
    }

    @Test
    void tableWithHeaderRow() throws Exception
    {
        assertRendered("| System | Owner |\n| --- | --- |\n| Wiki | IT |\n| CRM | Sales |",
            "|=System|=Owner\n|Wiki|IT\n|CRM|Sales");
    }

    @Test
    void tableWithoutHeaderRow() throws Exception
    {
        assertRendered("| a | b |\n| c | d |", "|a|b\n|c|d");
    }

    @Test
    void tableWithAHeaderColumnHasNoSeparator() throws Exception
    {
        assertRendered("| Name | Alice |\n| Role | Admin |", "|=Name|Alice\n|=Role|Admin");
    }

    @Test
    void blockContentOfATableCellIsFlattenedToOneLine() throws Exception
    {
        assertRendered("| Heading text first paragraph second line x; y | b |",
            "|(((= Heading =\n\ntext\n\nfirst paragraph\nsecond line\n\n* x\n* y)))|b");
    }

    @Test
    void standaloneVerbatimIsFenced() throws Exception
    {
        assertRendered("```\nline 1\nline 2\n```", "{{{\nline 1\nline 2\n}}}");
    }

    @Test
    void fenceIsLongerThanTheBackticksOfTheContent() throws Exception
    {
        assertRendered("````\na ``` b\n````", "{{{\na ``` b\n}}}");
    }

    @Test
    void inlineVerbatimUsesBackticks() throws Exception
    {
        assertRendered("Run `mvn install` now", "Run {{{mvn install}}} now");
    }

    @Test
    void linkWithLabelKeepsTheLabelOnly() throws Exception
    {
        assertRendered("See the handbook here", "See the [[handbook>>Main.Handbook]] here");
    }

    @Test
    void linkWithoutLabelPrintsItsReference()
    {
        // Built directly: without the platform, the parser does not recognize document references.
        XDOM xdom = new XDOM(List.of(new ParagraphBlock(List.of(new WordBlock("See"), new SpaceBlock(),
            new LinkBlock(List.of(), new DocumentResourceReference("Main.Handbook"), false)))));

        assertEquals("See Main.Handbook", this.renderer.render(xdom));
    }

    @Test
    void linkToAURLWithoutLabelPrintsTheURL() throws Exception
    {
        assertRendered("See https://www.xwiki.org and mail to a@example.org",
            "See [[https://www.xwiki.org]] and mail to [[mailto:a@example.org]]");
    }

    @Test
    void imagesPrintTheirAlternativeText() throws Exception
    {
        assertRendered("Before A chart of the sales after",
            "Before [[image:chart.png||alt=\"A chart of the sales\"]] after");
    }

    @Test
    void imageWithoutAlternativeTextIsDropped() throws Exception
    {
        assertRendered("Text", "[[image:chart.png]]\n\nText");
    }

    @Test
    void quotations() throws Exception
    {
        assertRendered("> first\n> second\n> > nested", ">first\n>second\n>>nested");
    }

    @Test
    void definitionList() throws Exception
    {
        assertRendered("Term: Description\nOther term: Other description",
            "; Term\n: Description\n; Other term\n: Other description");
    }

    @Test
    void horizontalLinesAndExtraEmptyLinesAreDropped() throws Exception
    {
        assertRendered("a\n\nb\n\nc", "a\n\n\n\n\nb\n\n----\n\nc");
    }

    @Test
    void headingInsideAListItemIsAnOrdinaryLine() throws Exception
    {
        assertRendered("- Heading\n\n  text", "* (((= Heading =\n\ntext)))");
    }

    @Test
    void rawBlocksArePrintedOnlyInPlainText()
    {
        XDOM xdom = new XDOM(List.of(new RawBlock("plain *text*", Syntax.PLAIN_1_0),
            new RawBlock("<b>html</b>", Syntax.HTML_5_0), new RawBlock("more text", Syntax.PLAIN_1_0)));

        assertEquals("plain *text*\n\nmore text", this.renderer.render(xdom));
    }

    private void assertRendered(String expected, String source) throws Exception
    {
        Parser parser = this.componentManager.getInstance(Parser.class, Syntax.XWIKI_2_1.toIdString());

        assertEquals(expected, this.renderer.render(parser.parse(new StringReader(source))));
    }

    @Test
    void quotationContainingBlocks()
    {
        // Built directly, as the Markdown parser produces it: the blocks are children of the quotation.
        XDOM xdom = new XDOM(List.of(new QuotationBlock(List.of(new ParagraphBlock(List.of(new WordBlock("Intro"))),
            new BulletedListBlock(List.of(item("a"), item("b")))))));

        assertEquals("> Intro\n> - a\n> - b", this.renderer.render(xdom));
    }

    @Test
    void numberedListStartsAtItsStartNumber()
    {
        XDOM xdom = new XDOM(List.of(new NumberedListBlock(List.of(item("x"), item("y")), Map.of("start", "3"))));

        assertEquals("3. x\n4. y", this.renderer.render(xdom));
    }

    @Test
    void emptyVerbatimIsDropped() throws Exception
    {
        assertRendered("Text", "{{{\n}}}\n\nText");
    }

    @Test
    void blockWithoutTextDoesNotSplitAParagraph()
    {
        XDOM xdom = new XDOM(List.of(new ParagraphBlock(List.of(new WordBlock("before"), new SpaceBlock(),
            new RawBlock("<a id=\"anchor\"></a>", Syntax.HTML_5_0), new WordBlock("after")))));

        assertEquals("before after", this.renderer.render(xdom));
    }

    private static ListItemBlock item(String text)
    {
        return new ListItemBlock(List.of(new WordBlock(text)));
    }
}
