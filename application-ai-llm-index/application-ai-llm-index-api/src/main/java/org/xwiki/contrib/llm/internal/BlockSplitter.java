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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntUnaryOperator;

import org.apache.commons.lang3.StringUtils;
import org.xwiki.contrib.llm.internal.DocumentSections.Part;
import org.xwiki.contrib.llm.internal.TextSplitter.Piece;
import org.xwiki.rendering.block.Block;
import org.xwiki.rendering.block.BulletedListBlock;
import org.xwiki.rendering.block.ListBLock;
import org.xwiki.rendering.block.NumberedListBlock;
import org.xwiki.rendering.block.TableBlock;
import org.xwiki.rendering.block.TableHeadCellBlock;
import org.xwiki.rendering.block.VerbatimBlock;

/**
 * Splits a content block that is too long for one chunk. Tables are split between rows and repeat their header row
 * (unless it takes more than half of a piece), lists are split between items and keep their numbering, verbatim blocks
 * are split between lines and fenced again: every piece is rendered as a complete block of its own, and repeats the
 * last rows, items or lines of the previous piece that fit into the overlap length. Other blocks, and rows, items or
 * lines that are too long by themselves, are split as text ({@link TextSplitter}).
 * <p>
 * The first piece can start with a lead, the end of the chunk before the block, so that the block overlaps with it.
 * <p>
 * The pieces of a table, list or verbatim block are all given the position of the whole block, the first one from the
 * start of its lead; the pieces of a text their position in it.
 *
 * @version $Id$
 * @since 1.0.1
 */
final class BlockSplitter
{
    private static final String START = "start";

    private static final String EMPTY_LINE = "\n\n";

    private final IndexTextRenderer renderer;

    private final TextSplitter textSplitter;

    private final int maxLength;

    private final int overlap;

    /**
     * @param renderer the renderer of the pieces
     * @param textSplitter the splitter of text, for the same maximum length and overlap
     * @param maxLength the maximum length of a piece
     * @param overlap the maximum length of the rows, items or lines repeated from the previous piece
     */
    BlockSplitter(IndexTextRenderer renderer, TextSplitter textSplitter, int maxLength, int overlap)
    {
        this.renderer = renderer;
        this.textSplitter = textSplitter;
        this.maxLength = maxLength;
        this.overlap = overlap;
    }

    /**
     * @param part a content block whose text is longer than the maximum length
     * @param lead the end of the chunk before the block, to start the first piece with, or {@code null} for none; a
     *     table, list or verbatim block leaves it out when it leaves no room for the first row, item or line
     * @return the pieces of the block, in order
     */
    List<Piece> split(Part part, Part lead)
    {
        if (!isStructured(part.block())) {
            return splitText(part, lead);
        }
        Elements elements;
        if (part.block() instanceof TableBlock table) {
            elements = getRows(table);
        } else if (part.block() instanceof ListBLock list) {
            elements = getItems(list);
        } else {
            elements = getLines((VerbatimBlock) part.block());
        }
        boolean withLead = lead != null
            && (lead.text() + EMPTY_LINE + elements.render(0, 1)).length() <= this.maxLength;
        List<String> texts = group(elements, withLead ? lead.text() : null);
        List<Piece> result = new ArrayList<>();
        for (int i = 0; i < texts.size(); i++) {
            result.add(new Piece(texts.get(i), i == 0 && withLead ? lead.start() : part.start(), part.end()));
        }
        return result;
    }

    /**
     * @param block a content block, or {@code null}
     * @return whether the block is a table, a list or a code block, which are split between their rows, items or
     *     lines rather than as text
     */
    static boolean isStructured(Block block)
    {
        return block instanceof TableBlock || block instanceof ListBLock
            || block instanceof VerbatimBlock verbatim && !verbatim.isInline();
    }

    private List<Piece> splitText(Part part, Part lead)
    {
        String prefix = lead == null ? "" : lead.text() + EMPTY_LINE;
        return this.textSplitter.split(prefix + part.text(), prefix.length()).stream()
            .map(piece -> new Piece(piece.text(), toPosition(piece.start(), part, lead, prefix.length()),
                toPosition(piece.end(), part, lead, prefix.length())))
            .toList();
    }

    /**
     * @param offset an offset in the text of the lead, the empty line and the block
     * @param textStart the offset of the block in that text
     * @return the offset in the text of the document
     */
    private static int toPosition(int offset, Part part, Part lead, int textStart)
    {
        return offset >= textStart ? part.start() + offset - textStart : Math.min(lead.start() + offset, lead.end());
    }

    private Elements getRows(TableBlock table)
    {
        List<Block> rows = table.getChildren();
        boolean repeatHeaderRow = rows.size() > 1 && !rows.get(0).getChildren().isEmpty()
            && rows.get(0).getChildren().stream().allMatch(TableHeadCellBlock.class::isInstance)
            && render(new TableBlock(copy(rows.subList(0, 1)), table.getParameters())).length() <= this.maxLength / 2;
        List<Block> headerRows = repeatHeaderRow ? rows.subList(0, 1) : List.of();
        List<Block> bodyRows = repeatHeaderRow ? rows.subList(1, rows.size()) : rows;
        return new Elements(bodyRows.size(), (first, last) -> {
            List<Block> pieceRows = new ArrayList<>(copy(headerRows));
            pieceRows.addAll(copy(bodyRows.subList(first, last)));
            return render(new TableBlock(pieceRows, table.getParameters()));
        }, index -> render(new TableBlock(copy(bodyRows.subList(index, index + 1)), table.getParameters())).length());
    }

    private Elements getItems(ListBLock list)
    {
        List<Block> items = list.getChildren();
        String start = list.getParameter(START);
        int startNumber = StringUtils.isNumeric(start) ? Integer.parseInt(start) : 1;
        RangeRenderer itemsRenderer = (first, last) -> {
            List<Block> pieceItems = copy(items.subList(first, last));
            Block pieceList;
            if (list instanceof NumberedListBlock) {
                Map<String, String> parameters = new HashMap<>(list.getParameters());
                parameters.put(START, String.valueOf(startNumber + first));
                pieceList = new NumberedListBlock(pieceItems, parameters);
            } else {
                pieceList = new BulletedListBlock(pieceItems, list.getParameters());
            }
            return render(pieceList);
        };
        return new Elements(items.size(), itemsRenderer, index -> itemsRenderer.render(index, index + 1).length());
    }

    private Elements getLines(VerbatimBlock verbatim)
    {
        List<String> lines = StringUtils.strip(verbatim.getProtectedString(), "\r\n").lines().toList();
        return new Elements(lines.size(), (first, last) -> render(new VerbatimBlock(
            String.join("\n", lines.subList(first, last)), verbatim.getParameters(), false)),
            index -> lines.get(index).length());
    }

    private interface RangeRenderer
    {
        String render(int first, int last);
    }

    /**
     * The rows, items or lines of a block.
     *
     * @param count the number of elements
     * @param rangeRenderer renders the elements from the first (included) to the last (excluded) index as a block
     * @param lengths the length of the text of each element on its own, by index
     */
    private record Elements(int count, RangeRenderer rangeRenderer, IntUnaryOperator lengths)
    {
        String render(int first, int last)
        {
            return this.rangeRenderer.render(first, last);
        }
    }

    /**
     * Group consecutive elements, given by their index, into pieces that are as long as possible but not longer than
     * the maximum length, each one repeating the last elements of the previous piece that fit into the overlap; a
     * single element that is too long is split as text, without repeating the elements before it.
     *
     * @param lead the text to start the first piece with, or {@code null}
     */
    private List<String> group(Elements elements, String lead)
    {
        List<String> result = new ArrayList<>();
        String prefix = lead == null ? "" : lead + EMPTY_LINE;
        // The first element of the piece, repeated ones included, and the first element that is new in it.
        int first = 0;
        int next = 0;
        while (next < elements.count()) {
            int last = next + 1;
            String text = prefix + elements.render(first, last);
            // Repeated elements are left out as long as they leave no room for a new one.
            while (first < next && text.length() > this.maxLength) {
                first++;
                text = prefix + elements.render(first, last);
            }
            boolean longerFits = true;
            while (last < elements.count() && longerFits) {
                String longer = prefix + elements.render(first, last + 1);
                longerFits = longer.length() <= this.maxLength;
                if (longerFits) {
                    text = longer;
                    last++;
                }
            }
            // A piece without text, e.g. of an empty line of code, is no chunk.
            if (!text.isBlank()) {
                result.addAll(text.length() > this.maxLength ? splitText(text) : List.of(text));
            }
            first = getRepeatedStart(elements, first, last);
            next = last;
            prefix = "";
        }
        return result;
    }

    /**
     * @return the index of the first of the last elements of a piece that fit into the overlap, separated by line
     *     breaks; {@code last} when none fits
     */
    private int getRepeatedStart(Elements elements, int first, int last)
    {
        int start = last;
        int length = 0;
        boolean fits = this.overlap > 0;
        while (start > first && fits) {
            int withPrevious = length + elements.lengths().applyAsInt(start - 1) + (start < last ? 1 : 0);
            fits = withPrevious <= this.overlap;
            if (fits) {
                length = withPrevious;
                start--;
            }
        }
        return start;
    }

    private List<String> splitText(String text)
    {
        return this.textSplitter.split(text).stream().map(Piece::text).toList();
    }

    private static List<Block> copy(List<Block> blocks)
    {
        return blocks.stream().map(Block::clone).toList();
    }

    private String render(Block block)
    {
        return this.renderer.render(List.of(block));
    }
}
