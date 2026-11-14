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
import java.util.Map;
import java.util.Optional;

import javax.inject.Inject;
import javax.inject.Singleton;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.slf4j.Logger;
import org.xwiki.component.annotation.Component;
import org.xwiki.rendering.block.Block;
import org.xwiki.rendering.block.LinkBlock;
import org.xwiki.rendering.block.MacroBlock;
import org.xwiki.rendering.block.MetaDataBlock;
import org.xwiki.rendering.block.VerbatimBlock;
import org.xwiki.rendering.block.WordBlock;
import org.xwiki.rendering.block.XDOM;
import org.xwiki.rendering.block.match.ClassBlockMatcher;
import org.xwiki.rendering.listener.MetaData;
import org.xwiki.rendering.listener.reference.ResourceType;
import org.xwiki.rendering.macro.Macro;
import org.xwiki.rendering.macro.MacroContentParser;
import org.xwiki.rendering.macro.MacroId;
import org.xwiki.rendering.macro.MacroLookupException;
import org.xwiki.rendering.macro.MacroManager;
import org.xwiki.rendering.macro.MacroPreparationException;
import org.xwiki.rendering.macro.descriptor.ContentDescriptor;
import org.xwiki.rendering.renderer.reference.link.LinkLabelGenerator;
import org.xwiki.rendering.syntax.Syntax;

/**
 * Prepares a parsed document for indexing without executing anything: every macro is replaced by its content when the
 * macro declares wiki content (e.g. the box and message macros), and removed otherwise (e.g. script macros). The
 * content is prepared by the macro itself ({@link Macro#prepare(MacroBlock)}), so it is parsed in the syntax and in
 * the inline or standalone form the macro uses when it is executed. The content of a {@code code} macro without
 * {@code source} parameter is kept as verbatim block, as code is searched for; with a {@code source}, the code comes
 * from somewhere else.
 * <p>
 * Links to documents without a label get the label the platform generates for them, as the text of the link: with the
 * default label format ({@code rendering.linkLabelFormat}, {@code %np}), the name of the linked page. A label format
 * showing the title ({@code %t}) puts the title of the linked page into the indexed text without checking whether the
 * readers of the indexed page may see that page.
 * <p>
 * Macros are looked up and labels are generated in the context wiki and for the context document: the document must be
 * the context document. A macro that cannot be looked up or prepared is removed.
 *
 * @version $Id$
 * @since 1.0.1
 */
@Component(roles = IndexableXDOMNormalizer.class)
@Singleton
public class IndexableXDOMNormalizer
{
    private static final String CODE_MACRO = "code";

    private static final String LANGUAGE = "language";

    private static final List<ResourceType> DOCUMENT_TYPES =
        List.of(ResourceType.DOCUMENT, ResourceType.SPACE, ResourceType.PAGE);

    @Inject
    private MacroManager macroManager;

    @Inject
    private LinkLabelGenerator linkLabelGenerator;

    @Inject
    private Logger logger;

    /**
     * Replace the macros of the given document by their content or remove them, see the class description.
     *
     * @param xdom the document to normalize, modified in place
     * @param syntax the syntax of the document, used when the document carries no syntax metadata
     */
    public void normalize(XDOM xdom, Syntax syntax)
    {
        if (xdom.getSyntaxMetadata().isEmpty()) {
            xdom.getMetaData().addMetaData(MetaData.SYNTAX, syntax);
        }
        normalizeChildren(xdom);
        addLinkLabels(xdom);
    }

    private void addLinkLabels(XDOM xdom)
    {
        List<LinkBlock> links = xdom.getBlocks(new ClassBlockMatcher(LinkBlock.class), Block.Axes.DESCENDANT);
        for (LinkBlock link : links) {
            if (link.getChildren().isEmpty() && DOCUMENT_TYPES.contains(link.getReference().getType())) {
                try {
                    link.addChild(new WordBlock(this.linkLabelGenerator.generate(link.getReference())));
                } catch (RuntimeException e) {
                    // The link keeps no label: its reference is indexed instead.
                    this.logger.warn("Failed to generate the label of a link to [{}] for indexing: [{}]",
                        link.getReference().getReference(), ExceptionUtils.getRootCauseMessage(e));
                }
            }
        }
    }

    private void normalizeChildren(Block parent)
    {
        int index = 0;
        while (index < parent.getChildren().size()) {
            Block child = parent.getChildren().get(index);
            if (child instanceof MacroBlock macroBlock) {
                // The replacement is examined again: it can contain macros itself.
                parent.replaceChild(getReplacement(macroBlock), macroBlock);
            } else {
                normalizeChildren(child);
                index++;
            }
        }
    }

    private List<Block> getReplacement(MacroBlock macroBlock)
    {
        if (CODE_MACRO.equals(macroBlock.getId())) {
            return getCodeReplacement(macroBlock);
        }

        try {
            Macro<?> macro = getMacro(macroBlock);
            if (!hasWikiContent(macro)) {
                // Only the declared content type counts: the HTML macro with wiki="true" prepares its content as
                // wiki content too, but that content is HTML markup, not text.
                return List.of();
            }
            // The content of a macro nested in prepared content is already prepared.
            if (getPreparedContent(macroBlock) == null) {
                macro.prepare(macroBlock);
            }
        } catch (MacroLookupException | MacroPreparationException e) {
            this.logger.debug("Leaving out the macro [{}] for indexing: [{}]", macroBlock.getId(),
                ExceptionUtils.getRootCauseMessage(e));
        } catch (RuntimeException e) {
            this.logger.warn("Leaving out the macro [{}] for indexing as preparing it failed: [{}]",
                macroBlock.getId(), ExceptionUtils.getRootCauseMessage(e));
        }
        XDOM content = getPreparedContent(macroBlock);
        if (content == null) {
            return List.of();
        }
        // Keep the metadata of the content, its syntax in particular, for the macros it contains.
        return List.of(new MetaDataBlock(content.getChildren(), content.getMetaData()));
    }

    private static XDOM getPreparedContent(MacroBlock macroBlock)
    {
        return macroBlock.getAttribute(MacroContentParser.ATTRIBUTE_PREPARE_CONTENT_XDOM) instanceof XDOM content
            ? content : null;
    }

    private static boolean hasWikiContent(Macro<?> macro)
    {
        ContentDescriptor contentDescriptor = macro.getDescriptor().getContentDescriptor();
        return contentDescriptor != null && Block.LIST_BLOCK_TYPE.equals(contentDescriptor.getType());
    }

    private Macro<?> getMacro(MacroBlock macroBlock) throws MacroLookupException
    {
        Optional<Syntax> syntax = macroBlock.getSyntaxMetadata();
        return this.macroManager.getMacro(new MacroId(macroBlock.getId(), syntax.orElse(null)));
    }

    private static List<Block> getCodeReplacement(MacroBlock macroBlock)
    {
        if (macroBlock.getParameter("source") != null) {
            return List.of();
        }
        String language = macroBlock.getParameter(LANGUAGE);
        Map<String, String> parameters = StringUtils.isEmpty(language) ? Map.of() : Map.of(LANGUAGE, language);
        return List.of(
            new VerbatimBlock(StringUtils.defaultString(macroBlock.getContent()), parameters, macroBlock.isInline()));
    }
}
