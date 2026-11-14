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
import java.util.Map;
import java.util.function.BiFunction;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import javax.inject.Singleton;

import org.apache.commons.lang3.StringUtils;
import org.xwiki.component.annotation.Component;
import org.xwiki.component.phase.Initializable;
import org.xwiki.rendering.block.Block;
import org.xwiki.rendering.block.BulletedListBlock;
import org.xwiki.rendering.block.DefinitionListBlock;
import org.xwiki.rendering.block.DefinitionTermBlock;
import org.xwiki.rendering.block.GroupBlock;
import org.xwiki.rendering.block.HeaderBlock;
import org.xwiki.rendering.block.ListBLock;
import org.xwiki.rendering.block.MacroMarkerBlock;
import org.xwiki.rendering.block.MetaDataBlock;
import org.xwiki.rendering.block.NumberedListBlock;
import org.xwiki.rendering.block.QuotationBlock;
import org.xwiki.rendering.block.QuotationLineBlock;
import org.xwiki.rendering.block.RawBlock;
import org.xwiki.rendering.block.SectionBlock;
import org.xwiki.rendering.block.TableBlock;
import org.xwiki.rendering.block.TableHeadCellBlock;
import org.xwiki.rendering.block.VerbatimBlock;
import org.xwiki.rendering.block.XDOM;
import org.xwiki.rendering.syntax.Syntax;

/**
 * Renders a document as the text that is indexed for it: simplified Markdown that keeps the structure a language
 * model reads reliably and leaves out everything else.
 * <ul>
 * <li>Headings are written with {@code #}, list items with {@code -} or {@code 1.}, nested lists and the continuation
 * lines of list items are indented, quotations are prefixed with {@code >}, definition lists become
 * {@code term: description} lines.</li>
 * <li>Tables are written as {@code |} rows, with a separator after the first row when all its cells are head cells.
 * The content of a cell is flattened to one line: list items are separated by {@code ;}, everything else by
 * spaces.</li>
 * <li>Verbatim blocks become fenced code blocks, inline verbatim is put between backticks.</li>
 * <li>Formatting, horizontal lines and extra empty lines are left out, links are replaced by their label (by their
 * reference when they have none; links to documents get their label from {@link IndexableXDOMNormalizer}), images
 * by their alternative text. Raw content is kept only when it is plain text. Nothing is escaped: the text is never
 * parsed back.</li>
 * <li>Headings inside list items, definition lists, quotations and table cells are written as ordinary text.</li>
 * </ul>
 * Blocks are separated by one empty line. Macro blocks are not rendered: macros have to be replaced by their content
 * before, see {@link IndexableXDOMNormalizer}.
 *
 * @version $Id$
 * @since 1.0.1
 */
@Component(roles = IndexTextRenderer.class)
@Singleton
public class IndexTextRenderer implements Initializable
{
    private static final String NL = "\n";

    private static final String EMPTY_LINE = "\n\n";

    private static final String SPACE = " ";

    private static final String ITEM_SEPARATOR = "; ";

    private static final Pattern LINE_BREAKS = Pattern.compile("\\s*\\n\\s*");

    /**
     * The blocks that only group other blocks: their children are rendered as if they were children of their parent.
     */
    private static final List<Class<? extends Block>> TRANSPARENT_BLOCKS =
        List.of(MetaDataBlock.class, SectionBlock.class, GroupBlock.class, MacroMarkerBlock.class);

    private Map<Class<? extends Block>, BiFunction<Block, RenderingContext, String>> blockRenderers;

    /**
     * Where a block is rendered.
     *
     * @param inTableCell whether the block is in a table cell, whose content is rendered on one line
     * @param plainHeadings whether headings are rendered as ordinary text
     */
    private record RenderingContext(boolean inTableCell, boolean plainHeadings)
    {
        private static final RenderingContext TOP_LEVEL = new RenderingContext(false, false);

        RenderingContext nested()
        {
            return new RenderingContext(this.inTableCell, true);
        }
    }

    @Override
    public void initialize()
    {
        this.blockRenderers = Map.of(
            HeaderBlock.class, (block, context) -> renderHeader((HeaderBlock) block, context),
            BulletedListBlock.class, (block, context) -> renderList((ListBLock) block, context),
            NumberedListBlock.class, (block, context) -> renderList((ListBLock) block, context),
            TableBlock.class, (block, context) -> renderTable((TableBlock) block),
            QuotationBlock.class, (block, context) -> renderQuotation((QuotationBlock) block, context),
            DefinitionListBlock.class, (block, context) -> renderDefinitionList((DefinitionListBlock) block, context),
            VerbatimBlock.class, (block, context) -> renderVerbatim((VerbatimBlock) block, context),
            RawBlock.class, (block, context) -> renderRaw((RawBlock) block));
    }

    /**
     * @param xdom the document to render
     * @return the text of the document
     */
    public String render(XDOM xdom)
    {
        return render(List.of(xdom));
    }

    /**
     * @param blocks the blocks to render, in order
     * @return the text of the blocks, separated by an empty line
     */
    public String render(List<? extends Block> blocks)
    {
        return String.join(EMPTY_LINE, renderParts(blocks, RenderingContext.TOP_LEVEL));
    }

    /**
     * Render blocks as a list of parts: each standalone block is one part, consecutive inline blocks form one part.
     * Empty parts are left out.
     */
    private List<String> renderParts(List<? extends Block> blocks, RenderingContext context)
    {
        List<String> parts = new ArrayList<>();
        StringBuilder inline = new StringBuilder();
        for (Block block : flatten(blocks)) {
            if (IndexTextInlineRenderer.isInline(block)) {
                inline.append(IndexTextInlineRenderer.render(block, context.inTableCell()));
            } else {
                // A block without text, e.g. an anchor, does not interrupt the inline content around it.
                String text = renderBlock(block, context);
                if (!text.isBlank()) {
                    addPart(parts, inline.toString());
                    inline.setLength(0);
                    addPart(parts, text);
                }
            }
        }
        addPart(parts, inline.toString());
        return parts;
    }

    private static void addPart(List<String> parts, String part)
    {
        String stripped = StringUtils.strip(part);
        if (StringUtils.isNotEmpty(stripped)) {
            parts.add(stripped);
        }
    }

    private List<Block> flatten(List<? extends Block> blocks)
    {
        List<Block> result = new ArrayList<>();
        for (Block block : blocks) {
            if (TRANSPARENT_BLOCKS.stream().anyMatch(type -> type.isInstance(block))) {
                result.addAll(flatten(block.getChildren()));
            } else {
                result.add(block);
            }
        }
        return result;
    }

    private String renderBlock(Block block, RenderingContext context)
    {
        BiFunction<Block, RenderingContext, String> blockRenderer = this.blockRenderers.get(block.getClass());
        if (blockRenderer != null) {
            return blockRenderer.apply(block, context);
        }
        // Paragraphs and any other block: their content. Horizontal lines, empty lines and macros have none.
        return String.join(context.inTableCell() ? SPACE : EMPTY_LINE, renderParts(block.getChildren(), context));
    }

    private String renderHeader(HeaderBlock header, RenderingContext context)
    {
        String text = String.join(SPACE, renderParts(header.getChildren(), context));
        if (context.plainHeadings() || text.isEmpty()) {
            return text;
        }
        return "#".repeat(header.getLevel().getAsInt()) + SPACE + text;
    }

    private String renderList(ListBLock list, RenderingContext context)
    {
        List<String> items = new ArrayList<>();
        for (Block item : list.getChildren()) {
            items.add(renderListItem(item, context.nested()));
        }
        if (context.inTableCell()) {
            return String.join(ITEM_SEPARATOR, items);
        }

        StringBuilder result = new StringBuilder();
        int number = getStartNumber(list);
        for (String item : items) {
            String marker = list instanceof NumberedListBlock ? number++ + ". " : "- ";
            if (!result.isEmpty()) {
                result.append(NL);
            }
            result.append(marker).append(indentContinuationLines(item, marker.length()));
        }
        return result.toString();
    }

    private static int getStartNumber(ListBLock list)
    {
        String start = list.getParameter("start");
        return StringUtils.isNumeric(start) ? Integer.parseInt(start) : 1;
    }

    /**
     * Render a list item: its first part directly, nested lists on the next line, other blocks after an empty line.
     */
    private String renderListItem(Block item, RenderingContext context)
    {
        StringBuilder result = new StringBuilder();
        boolean hasText = false;
        for (Block child : flatten(item.getChildren())) {
            if (IndexTextInlineRenderer.isInline(child)) {
                String text = IndexTextInlineRenderer.render(child, context.inTableCell());
                result.append(text);
                hasText |= !text.isBlank();
            } else {
                String part = renderBlock(child, context).strip();
                if (!part.isEmpty()) {
                    String separator = child instanceof ListBLock ? NL : EMPTY_LINE;
                    if (hasText) {
                        result.append(context.inTableCell() ? SPACE : separator);
                    }
                    result.append(part);
                    hasText = true;
                }
            }
        }
        return result.toString().strip();
    }

    private static String indentContinuationLines(String text, int indentation)
    {
        String indent = SPACE.repeat(indentation);
        List<String> lines = text.lines().toList();
        StringBuilder result = new StringBuilder(lines.isEmpty() ? "" : lines.get(0));
        for (String line : lines.subList(Math.min(1, lines.size()), lines.size())) {
            result.append(NL);
            if (!line.isEmpty()) {
                result.append(indent).append(line);
            }
        }
        return result.toString();
    }

    private String renderTable(TableBlock table)
    {
        List<String> rows = new ArrayList<>();
        for (Block row : table.getChildren()) {
            List<String> cells = new ArrayList<>();
            boolean allHeadCells = true;
            for (Block cell : row.getChildren()) {
                cells.add(renderTableCell(cell));
                allHeadCells &= cell instanceof TableHeadCellBlock;
            }
            rows.add("| " + String.join(" | ", cells) + " |");
            if (rows.size() == 1 && allHeadCells && !cells.isEmpty()) {
                rows.add("|" + " --- |".repeat(cells.size()));
            }
        }
        return String.join(NL, rows);
    }

    private String renderTableCell(Block cell)
    {
        String text = String.join(SPACE, renderParts(cell.getChildren(), new RenderingContext(true, true)));
        return LINE_BREAKS.matcher(text).replaceAll(SPACE);
    }

    private String renderQuotation(QuotationBlock quotation, RenderingContext context)
    {
        List<String> lines = new ArrayList<>();
        for (Block child : quotation.getChildren()) {
            List<Block> blocks = child instanceof QuotationLineBlock ? child.getChildren() : List.of(child);
            lines.add(String.join(NL, renderParts(blocks, context.nested())));
        }
        if (context.inTableCell()) {
            return String.join(SPACE, lines);
        }
        return String.join(NL, lines).lines()
            .map(line -> line.isEmpty() ? ">" : "> " + line)
            .collect(Collectors.joining(NL));
    }

    private String renderDefinitionList(DefinitionListBlock list, RenderingContext context)
    {
        StringBuilder result = new StringBuilder();
        boolean afterTerm = false;
        for (Block child : list.getChildren()) {
            String text = String.join(SPACE, renderParts(child.getChildren(), context.nested()));
            if (afterTerm) {
                result.append(": ").append(text);
            } else {
                if (!result.isEmpty()) {
                    result.append(context.inTableCell() ? ITEM_SEPARATOR : NL);
                }
                result.append(text);
            }
            afterTerm = child instanceof DefinitionTermBlock;
        }
        return result.toString();
    }

    private String renderVerbatim(VerbatimBlock verbatim, RenderingContext context)
    {
        String content = verbatim.getProtectedString();
        if (content.isBlank()) {
            return "";
        }
        if (verbatim.isInline() || context.inTableCell()) {
            return IndexTextInlineRenderer.render(new VerbatimBlock(content, true), context.inTableCell());
        }
        String fence = IndexTextInlineRenderer.fence(content, 3);
        String language = StringUtils.defaultString(verbatim.getParameter("language"));
        // The line breaks after the opening and before the closing verbatim syntax are part of the content.
        return fence + language + NL + StringUtils.strip(content, "\r\n") + NL + fence;
    }

    private static String renderRaw(RawBlock raw)
    {
        return Syntax.PLAIN_1_0.equals(raw.getSyntax()) ? raw.getRawContent() : "";
    }
}
