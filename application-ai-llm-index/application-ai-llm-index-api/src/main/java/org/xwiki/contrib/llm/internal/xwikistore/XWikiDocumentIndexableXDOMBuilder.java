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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.inject.Inject;
import javax.inject.Singleton;

import org.apache.commons.lang3.exception.ExceptionUtils;
import org.slf4j.Logger;
import org.xwiki.component.annotation.Component;
import org.xwiki.contrib.llm.internal.IndexTextRenderer;
import org.xwiki.contrib.llm.internal.IndexableXDOMNormalizer;
import org.xwiki.rendering.block.Block;
import org.xwiki.rendering.block.HeaderBlock;
import org.xwiki.rendering.block.MetaDataBlock;
import org.xwiki.rendering.block.RawBlock;
import org.xwiki.rendering.block.WordBlock;
import org.xwiki.rendering.block.XDOM;
import org.xwiki.rendering.listener.HeaderLevel;
import org.xwiki.rendering.listener.MetaData;
import org.xwiki.rendering.parser.ContentParser;
import org.xwiki.rendering.parser.MissingParserException;
import org.xwiki.rendering.parser.ParseException;
import org.xwiki.rendering.syntax.Syntax;

import com.xpn.xwiki.doc.XWikiDocument;
import com.xpn.xwiki.objects.classes.PropertyClass;
import com.xpn.xwiki.objects.classes.TextAreaClass;

/**
 * Builds the indexed body of a wiki page ({@link XWikiDocumentDocument#getIndexableXDOM()}) from its content and
 * from the given objects and attachments. The content and wiki text
 * properties are parsed but not executed, and their macros are replaced by their content
 * ({@link IndexableXDOMNormalizer}). Plain text properties and the text of attachments are kept as plain text,
 * Velocity properties are left out.
 * <p>
 * Needs the page as context document, see {@link IndexableXDOMNormalizer}.
 *
 * @version $Id$
 * @since 1.0.2
 */
@Component(roles = XWikiDocumentIndexableXDOMBuilder.class)
@Singleton
public class XWikiDocumentIndexableXDOMBuilder
{
    @Inject
    private IndexableXDOMNormalizer normalizer;

    @Inject
    private ContentParser contentParser;

    @Inject
    private IndexTextRenderer renderer;

    @Inject
    private Logger logger;

    /**
     * An object selected for indexing.
     *
     * @param heading the heading of the object
     * @param properties the properties of the object to index, in order
     */
    public record ObjectSection(String heading, List<PropertyValue> properties)
    {
    }

    /**
     * A property of an object selected for indexing.
     *
     * @param name the name of the property to show
     * @param value the value of the property, not blank
     * @param propertyClass the class of the property, which decides how the value is read
     */
    public record PropertyValue(String name, String value, PropertyClass propertyClass)
    {
    }

    /**
     * @param document the page
     * @param objects the objects of the page to index, in order
     * @param attachmentTexts the text of the attachments of the page to index, by file name, in order
     * @return the content of the page followed by one section per object with indexed values and one per attachment
     */
    public XDOM build(XWikiDocument document, List<ObjectSection> objects, Map<String, String> attachmentTexts)
    {
        Syntax syntax = document.getSyntax();
        XDOM content = document.getXDOM();
        this.normalizer.normalize(content, syntax);

        List<Block> blocks = new ArrayList<>();
        blocks.add(new MetaDataBlock(content.getChildren(), content.getMetaData()));
        for (ObjectSection object : objects) {
            blocks.addAll(getObjectBlocks(object, document, syntax));
        }
        attachmentTexts.forEach((fileName, text) -> {
            blocks.add(header(HeaderLevel.LEVEL1, fileName));
            blocks.add(new RawBlock(text, Syntax.PLAIN_1_0));
        });
        return new XDOM(blocks, new MetaData(Map.of(MetaData.SYNTAX, syntax)));
    }

    private List<Block> getObjectBlocks(ObjectSection object, XWikiDocument document, Syntax syntax)
    {
        List<Block> propertyBlocks = new ArrayList<>();
        for (PropertyValue property : object.properties()) {
            List<Block> valueBlocks = getValueBlocks(property, document, syntax);
            if (!valueBlocks.isEmpty()) {
                propertyBlocks.add(header(HeaderLevel.LEVEL2, property.name()));
                propertyBlocks.addAll(valueBlocks);
            }
        }
        if (propertyBlocks.isEmpty()) {
            return List.of();
        }
        List<Block> result = new ArrayList<>();
        result.add(header(HeaderLevel.LEVEL1, object.heading()));
        result.addAll(propertyBlocks);
        return result;
    }

    private List<Block> getValueBlocks(PropertyValue property, XWikiDocument document, Syntax syntax)
    {
        if (!(property.propertyClass() instanceof TextAreaClass textArea)) {
            return List.of(new RawBlock(property.value(), Syntax.PLAIN_1_0));
        }
        TextAreaClass.ContentType contentType = TextAreaClass.ContentType.getByValue(textArea.getContentType());
        if (contentType == TextAreaClass.ContentType.PURE_TEXT) {
            return List.of(new RawBlock(property.value(), Syntax.PLAIN_1_0));
        }
        if (contentType != TextAreaClass.ContentType.WIKI_TEXT) {
            // Velocity code is not text.
            return List.of();
        }
        try {
            XDOM parsed = this.contentParser.parse(property.value(), syntax, document.getDocumentReference());
            this.normalizer.normalize(parsed, syntax);
            // A value without indexed text (see IndexTextRenderer), e.g. only scripts or an image without
            // alternative text, leaves nothing, and no heading either.
            return this.renderer.render(parsed.getChildren()).isBlank() ? List.of()
                : List.of(new MetaDataBlock(parsed.getChildren(), parsed.getMetaData()));
        } catch (ParseException | MissingParserException e) {
            this.logger.warn("Leaving out the property [{}] in document [{}] for indexing as it cannot be parsed: [{}]",
                property.name(), document.getDocumentReference(), ExceptionUtils.getRootCauseMessage(e));
            return List.of();
        }
    }

    private static HeaderBlock header(HeaderLevel level, String text)
    {
        return new HeaderBlock(List.of(new WordBlock(text)), level);
    }
}
