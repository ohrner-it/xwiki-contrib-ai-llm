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
import org.junit.jupiter.api.extension.RegisterExtension;
import org.xwiki.rendering.block.LinkBlock;
import org.xwiki.rendering.block.ParagraphBlock;
import org.xwiki.rendering.block.SpaceBlock;
import org.xwiki.rendering.block.WordBlock;
import org.xwiki.rendering.block.XDOM;
import org.xwiki.rendering.listener.reference.DocumentResourceReference;
import org.xwiki.rendering.parser.Parser;
import org.xwiki.rendering.renderer.reference.link.LinkLabelGenerator;
import org.xwiki.rendering.syntax.Syntax;
import org.xwiki.rendering.util.IconProvider;
import org.xwiki.test.LogLevel;
import org.xwiki.test.annotation.AllComponents;
import org.xwiki.test.junit5.LogCaptureExtension;
import org.xwiki.test.junit5.mockito.ComponentTest;
import org.xwiki.test.junit5.mockito.InjectComponentManager;
import org.xwiki.test.mockito.MockitoComponentManager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

/**
 * Component test for {@link IndexableXDOMNormalizer}, with the real macros of the box and message macro modules and
 * documents parsed from XWiki 2.1 sources. The result is checked through {@link IndexTextRenderer}.
 *
 * @version $Id$
 */
@ComponentTest
@AllComponents
class IndexableXDOMNormalizerTest
{
    @RegisterExtension
    private LogCaptureExtension logCapture = new LogCaptureExtension(LogLevel.WARN);

    @InjectComponentManager
    private MockitoComponentManager componentManager;

    private LinkLabelGenerator linkLabelGenerator;

    private IndexableXDOMNormalizer normalizer;

    private IndexTextRenderer renderer;

    @BeforeEach
    void setUp() throws Exception
    {
        // Replace the implementations of the platform, which need a wiki.
        this.linkLabelGenerator = this.componentManager.registerMockComponent(LinkLabelGenerator.class);
        this.componentManager.registerMockComponent(IconProvider.class);
        this.normalizer = this.componentManager.getInstance(IndexableXDOMNormalizer.class);
        this.renderer = this.componentManager.getInstance(IndexTextRenderer.class);
    }

    @Test
    void wikiContentOfAMacroIsKept() throws Exception
    {
        assertNormalized("Before\n\nThe VPN client is required.\n\nAfter",
            "Before\n\n{{info}}\nThe **VPN client** is required.\n{{/info}}\n\nAfter");
    }

    @Test
    void headingsInsideAMacroAreKept() throws Exception
    {
        assertNormalized("# Inner heading\n\nText", "{{box}}\n= Inner heading =\n\nText\n{{/box}}");
    }

    @Test
    void nestedMacrosAreReplacedByTheirContent() throws Exception
    {
        assertNormalized("Deep content", "{{box}}\n{{warning}}\nDeep content\n{{/warning}}\n{{/box}}");
    }

    @Test
    void inlineMacroContentStaysInTheParagraph() throws Exception
    {
        assertNormalized("Before inline content after", "Before {{info}}inline content{{/info}} after");
    }

    @Test
    void contentIsParsedInTheSyntaxOfTheContentMacro() throws Exception
    {
        assertNormalized("**not bold**", "{{content syntax=\"plain/1.0\"}}\n**not bold**\n{{/content}}");
    }

    @Test
    void macroWithContentInAnotherSyntaxIsDropped() throws Exception
    {
        assertNormalized("Text", "{{raw syntax=\"html/5.0\"}}\n<b>raw</b>\n{{/raw}}\n\nText");
    }

    @Test
    void macroWithTextContentIsDropped() throws Exception
    {
        assertNormalized("Text", "{{comment}}\nA comment\n{{/comment}}\n\nText");
    }

    @Test
    void unknownMacroIsDropped() throws Exception
    {
        assertNormalized("Text", "{{doesNotExist}}\nContent\n{{/doesNotExist}}\n\nText");
    }

    @Test
    void codeMacroContentIsKeptAsCode() throws Exception
    {
        assertNormalized("```bash\n# install\nmvn install\n```",
            "{{code language=\"bash\"}}\n# install\nmvn install\n{{/code}}");
    }

    @Test
    void codeMacroWithSourceIsDropped() throws Exception
    {
        assertNormalized("Text", "{{code source=\"attachment:script.sh\"}}{{/code}}\n\nText");
    }

    @Test
    void documentWithoutSyntaxMetadataUsesTheGivenSyntax() throws Exception
    {
        XDOM parsed = parse("{{info}}\nInformation\n{{/info}}");
        XDOM xdom = new XDOM(parsed.getChildren());

        this.normalizer.normalize(xdom, Syntax.XWIKI_2_1);

        assertEquals("Information", this.renderer.render(xdom));
    }

    private void assertNormalized(String expected, String source) throws Exception
    {
        XDOM xdom = parse(source);

        this.normalizer.normalize(xdom, Syntax.XWIKI_2_1);

        assertEquals(expected, this.renderer.render(xdom));
    }

    private XDOM parse(String source) throws Exception
    {
        Parser parser = this.componentManager.getInstance(Parser.class, Syntax.XWIKI_2_1.toIdString());
        return parser.parse(new StringReader(source));
    }

    @Test
    void macroWhosePreparationFailsIsDropped() throws Exception
    {
        // An invalid syntax identifier makes the content macro fail with a runtime exception.
        assertNormalized("Text", "{{content syntax=\"markdown\"}}\ncontent\n{{/content}}\n\nText");

        assertEquals("Leaving out the macro [content] for indexing as preparing it failed: [ParseException: Invalid "
            + "Syntax format [markdown]]", this.logCapture.getMessage(0));
    }

    @Test
    void linkToADocumentWithoutLabelGetsTheGeneratedLabel()
    {
        DocumentResourceReference reference = new DocumentResourceReference("Main.Handbook");
        when(this.linkLabelGenerator.generate(reference)).thenReturn("Handbook");
        XDOM xdom = new XDOM(List.of(new ParagraphBlock(List.of(new WordBlock("See"), new SpaceBlock(),
            new LinkBlock(List.of(), reference, false)))));

        this.normalizer.normalize(xdom, Syntax.XWIKI_2_1);

        assertEquals("See Handbook", this.renderer.render(xdom));
    }

    @Test
    void nestedMacroWithTextContentIsDroppedEvenWhenItsContentWasPrepared() throws Exception
    {
        assertNormalized("Text", "{{box}}\n{{html wiki=\"true\"}}\n<b>bold</b>\n{{/html}}\n{{/box}}\n\nText");
    }

    @Test
    void linkWhoseLabelCannotBeGeneratedKeepsItsReference()
    {
        DocumentResourceReference reference = new DocumentResourceReference("Main.Handbook");
        when(this.linkLabelGenerator.generate(reference)).thenThrow(new IllegalArgumentException("Invalid"));
        XDOM xdom = new XDOM(List.of(new ParagraphBlock(List.of(new LinkBlock(List.of(), reference, false)))));

        this.normalizer.normalize(xdom, Syntax.XWIKI_2_1);

        assertEquals("Main.Handbook", this.renderer.render(xdom));
        assertEquals("Failed to generate the label of a link to [Main.Handbook] for indexing: "
            + "[IllegalArgumentException: Invalid]", this.logCapture.getMessage(0));
    }
}
