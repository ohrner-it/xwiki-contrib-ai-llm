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

import org.apache.commons.lang3.StringUtils;
import org.xwiki.rendering.block.Block;
import org.xwiki.rendering.block.FormatBlock;
import org.xwiki.rendering.block.ImageBlock;
import org.xwiki.rendering.block.LinkBlock;
import org.xwiki.rendering.block.NewLineBlock;
import org.xwiki.rendering.block.SpaceBlock;
import org.xwiki.rendering.block.SpecialSymbolBlock;
import org.xwiki.rendering.block.VerbatimBlock;
import org.xwiki.rendering.block.WordBlock;

/**
 * Renders the inline content of a block (words, links, images, inline verbatim) for {@link IndexTextRenderer}, which
 * describes the format.
 *
 * @version $Id$
 * @since 1.0.1
 */
final class IndexTextInlineRenderer
{
    private static final String BACKTICK = "`";

    private static final List<Class<? extends Block>> INLINE_BLOCKS = List.of(WordBlock.class, SpaceBlock.class,
        SpecialSymbolBlock.class, NewLineBlock.class, FormatBlock.class, LinkBlock.class, ImageBlock.class);

    private IndexTextInlineRenderer()
    {
    }

    /**
     * @param block a block
     * @return {@code true} if the block is inline content handled by {@link #render(Block, boolean)}
     */
    static boolean isInline(Block block)
    {
        if (block instanceof VerbatimBlock verbatim) {
            return verbatim.isInline();
        }
        return INLINE_BLOCKS.stream().anyMatch(type -> type.isInstance(block));
    }

    /**
     * @param block an inline block, see {@link #isInline(Block)}
     * @param oneLine whether line breaks are rendered as spaces
     * @return the text of the block
     */
    static String render(Block block, boolean oneLine)
    {
        String result;
        if (block instanceof WordBlock word) {
            result = word.getWord();
        } else if (block instanceof SpaceBlock) {
            result = " ";
        } else if (block instanceof SpecialSymbolBlock symbol) {
            result = String.valueOf(symbol.getSymbol());
        } else if (block instanceof NewLineBlock) {
            result = oneLine ? " " : "\n";
        } else if (block instanceof LinkBlock link) {
            result = renderLink(link, oneLine);
        } else if (block instanceof ImageBlock image) {
            result = StringUtils.defaultString(image.getParameter("alt"));
        } else if (block instanceof VerbatimBlock verbatim) {
            String fence = fence(verbatim.getProtectedString(), 1);
            result = fence + verbatim.getProtectedString() + fence;
        } else {
            result = renderChildren(block, oneLine);
        }
        return result;
    }

    /**
     * @param content the content to put between backticks
     * @param minimumLength the minimum number of backticks
     * @return a run of backticks longer than the longest run of backticks in the content, and at least of the
     *     minimum length
     */
    static String fence(String content, int minimumLength)
    {
        int longestRun = 0;
        int currentRun = 0;
        for (char character : content.toCharArray()) {
            currentRun = character == '`' ? currentRun + 1 : 0;
            longestRun = Math.max(longestRun, currentRun);
        }
        return BACKTICK.repeat(Math.max(minimumLength, longestRun + 1));
    }

    private static String renderLink(LinkBlock link, boolean oneLine)
    {
        // Links to documents got their label from IndexableXDOMNormalizer.
        return link.getChildren().isEmpty() ? link.getReference().getReference() : renderChildren(link, oneLine);
    }

    private static String renderChildren(Block block, boolean oneLine)
    {
        StringBuilder result = new StringBuilder();
        for (Block child : block.getChildren()) {
            result.append(render(child, oneLine));
        }
        return result.toString();
    }
}
