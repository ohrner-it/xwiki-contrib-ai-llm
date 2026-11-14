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
package org.xwiki.contrib.llm.internal.xwikistore;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.xwiki.contrib.llm.internal.IndexTextRenderer;
import org.xwiki.contrib.llm.internal.IndexableXDOMNormalizer;
import org.xwiki.contrib.llm.internal.xwikistore.XWikiDocumentIndexableXDOMBuilder.ObjectSection;
import org.xwiki.contrib.llm.internal.xwikistore.XWikiDocumentIndexableXDOMBuilder.PropertyValue;
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.rendering.block.MetaDataBlock;
import org.xwiki.rendering.block.XDOM;
import org.xwiki.rendering.parser.ContentParser;
import org.xwiki.rendering.parser.ParseException;
import org.xwiki.rendering.syntax.Syntax;
import org.xwiki.test.LogLevel;
import org.xwiki.test.annotation.ComponentList;
import org.xwiki.test.junit5.LogCaptureExtension;
import org.xwiki.test.junit5.mockito.ComponentTest;
import org.xwiki.test.junit5.mockito.InjectMockComponents;
import org.xwiki.test.junit5.mockito.MockComponent;

import com.xpn.xwiki.doc.XWikiDocument;
import com.xpn.xwiki.objects.classes.TextAreaClass;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Component test for {@link XWikiDocumentIndexableXDOMBuilder}; the content of the result is tested through
 * {@code XWikiDocumentDocumentTest}.
 *
 * @version $Id$
 */
@ComponentTest
@ComponentList(IndexTextRenderer.class)
class XWikiDocumentIndexableXDOMBuilderTest
{
    private static final DocumentReference DOCUMENT_REFERENCE = new DocumentReference("wiki", "Space", "Page");

    @RegisterExtension
    private LogCaptureExtension logCapture = new LogCaptureExtension(LogLevel.WARN);

    @InjectMockComponents
    private XWikiDocumentIndexableXDOMBuilder builder;

    @MockComponent
    private IndexableXDOMNormalizer normalizer;

    @MockComponent
    private ContentParser contentParser;

    @Test
    void wikiTextPropertyThatCannotBeParsedIsLeftOut() throws Exception
    {
        XWikiDocument document = mock();
        when(document.getSyntax()).thenReturn(Syntax.XWIKI_2_1);
        when(document.getXDOM()).thenReturn(new XDOM(List.of()));
        when(document.getDocumentReference()).thenReturn(DOCUMENT_REFERENCE);
        TextAreaClass textArea = mock();
        when(textArea.getContentType()).thenReturn("FullyRenderedText");
        when(this.contentParser.parse("{{broken", Syntax.XWIKI_2_1, DOCUMENT_REFERENCE))
            .thenThrow(new ParseException("Unexpected end"));

        XDOM xdom = this.builder.build(document,
            List.of(new ObjectSection("Object", List.of(new PropertyValue("Description", "{{broken", textArea)))),
            Map.of());

        assertEquals(1, xdom.getChildren().size());
        assertInstanceOf(MetaDataBlock.class, xdom.getChildren().get(0));
        assertEquals("Leaving out the property [Description] in document [wiki:Space.Page] for indexing as it "
            + "cannot be parsed: [ParseException: Unexpected end]", this.logCapture.getMessage(0));
    }
}
