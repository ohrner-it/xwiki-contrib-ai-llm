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

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import org.xwiki.rendering.block.Block;
import org.xwiki.rendering.block.GroupBlock;
import org.xwiki.rendering.block.HeaderBlock;
import org.xwiki.rendering.block.MacroMarkerBlock;
import org.xwiki.rendering.block.MetaDataBlock;
import org.xwiki.rendering.block.ParagraphBlock;
import org.xwiki.rendering.block.SectionBlock;
import org.xwiki.rendering.block.XDOM;

/**
 * Splits a document into its sections for {@link SectionsChunkingMethod}: the content before the first heading, then
 * one section per heading, each with the path of headings it is nested in and its content blocks rendered by
 * {@link IndexTextRenderer}. Headings are found in nested sections (as the XWiki syntax parsers produce them), in
 * groups and in the content of macros alike, as well as directly in the document (as the Markdown parser produces
 * them); a heading inside a group or the content of a macro applies up to the end of that group or content, where
 * the section around it continues. Headings inside other blocks are part of their text, see
 * {@link IndexTextRenderer}.
 * <p>
 * The text of the document is the sequence of headings and content blocks, separated by an empty line; the
 * positions of the parts are offsets in that text.
 *
 * @version $Id$
 * @since 1.0.2
 */
final class DocumentSections
{
    private static final int SEPARATOR_LENGTH = 2;

    /**
     * The blocks that only group other blocks, whose headings apply only up to their end.
     */
    private static final List<Class<? extends Block>> TRANSPARENT_BLOCKS =
        List.of(MetaDataBlock.class, SectionBlock.class, GroupBlock.class, MacroMarkerBlock.class);

    /**
     * A heading. Two headings with the same title are different headings: equality is identity.
     */
    static final class Heading
    {
        private final int level;

        private final String title;

        Heading(int level, String title)
        {
            this.level = level;
            this.title = title;
        }

        int level()
        {
            return this.level;
        }

        String title()
        {
            return this.title;
        }
    }

    /**
     * A heading or a content block of the document, rendered.
     *
     * @param block the block
     * @param text the text of the block, not blank
     * @param start the offset of the text in the text of the document
     * @param end the offset after the text in the text of the document
     */
    record Part(Block block, String text, int start, int end)
    {
    }

    /**
     * A section of the document.
     *
     * @param path the headings the section is nested in, outermost first, ending with its own heading when it has
     *     one; empty for the content before the first heading
     * @param heading the heading of the section; {@code null} for the content before the first heading, for the
     *     content that continues a section after a group or macro content with headings, and after a heading without
     *     text
     * @param parts the content blocks of the section up to the next heading, in order
     */
    record Section(List<Heading> path, Part heading, List<Part> parts)
    {
    }

    private final IndexTextRenderer renderer;

    private final List<Section> sections = new ArrayList<>();

    private final Deque<Heading> headings = new ArrayDeque<>();

    private final List<Block> pendingInlineBlocks = new ArrayList<>();

    private Part currentHeading;

    private List<Part> currentParts = new ArrayList<>();

    private int offset;

    /**
     * The number of headings that the current block cannot end, see {@link #walkScoped(Block)}.
     */
    private int floor;

    private DocumentSections(IndexTextRenderer renderer)
    {
        this.renderer = renderer;
    }

    /**
     * @param xdom the document
     * @param renderer the renderer of the text of the blocks
     * @return the sections of the document, in order; sections with neither heading nor content, and sections with a
     *     heading but neither content nor nested sections, are left out
     */
    static List<Section> of(XDOM xdom, IndexTextRenderer renderer)
    {
        DocumentSections documentSections = new DocumentSections(renderer);
        documentSections.walk(xdom.getChildren());
        documentSections.endSection();
        return withoutEmptyLeaves(documentSections.sections);
    }

    /**
     * Leave out the sections that have a heading but neither text nor nested sections, e.g. a heading followed only
     * by an image without alternative text: their heading would stand alone in a chunk. A section without text stays
     * when sections are nested in it, as its heading is part of their path.
     */
    private static List<Section> withoutEmptyLeaves(List<Section> sections)
    {
        // Backwards, so that a heading whose nested sections are all left out is left out too.
        Deque<Section> result = new ArrayDeque<>();
        for (int i = sections.size() - 1; i >= 0; i--) {
            Section section = sections.get(i);
            Section next = result.peekFirst();
            boolean hasNestedSections = section.heading() != null && next != null
                && next.path().contains(section.path().get(section.path().size() - 1));
            if (section.heading() == null || !section.parts().isEmpty() || hasNestedSections) {
                result.addFirst(section);
            }
        }
        return List.copyOf(result);
    }

    private void walk(List<Block> blocks)
    {
        for (Block block : blocks) {
            if (IndexTextInlineRenderer.isInline(block)) {
                this.pendingInlineBlocks.add(block.clone());
            } else {
                addPendingInlineBlocks();
                if (block instanceof HeaderBlock header) {
                    startSection(header);
                } else if (TRANSPARENT_BLOCKS.stream().anyMatch(type -> type.isInstance(block))) {
                    walkScoped(block);
                } else {
                    addPart(block);
                }
            }
        }
        addPendingInlineBlocks();
    }

    /**
     * Walk the content of a block that ends the headings it contains: the headings outside it stay, and the content
     * after it continues the section it is in.
     */
    private void walkScoped(Block container)
    {
        int previousFloor = this.floor;
        List<Heading> headingsBefore = List.copyOf(this.headings);
        this.floor = this.headings.size();
        walk(container.getChildren());
        this.floor = previousFloor;
        if (!List.copyOf(this.headings).equals(headingsBefore)) {
            endSection();
            this.headings.clear();
            this.headings.addAll(headingsBefore);
            this.currentHeading = null;
        }
    }

    /**
     * Inline content directly in the document or a group is a paragraph of its own.
     */
    private void addPendingInlineBlocks()
    {
        if (!this.pendingInlineBlocks.isEmpty()) {
            addPart(new ParagraphBlock(new ArrayList<>(this.pendingInlineBlocks)));
            this.pendingInlineBlocks.clear();
        }
    }

    private void addPart(Block block)
    {
        String text = this.renderer.render(List.of(block));
        if (!text.isBlank()) {
            this.currentParts.add(newPart(block, text));
        }
    }

    private Part newPart(Block block, String text)
    {
        Part part = new Part(block, text, this.offset, this.offset + text.length());
        this.offset = part.end() + SEPARATOR_LENGTH;
        return part;
    }

    private void startSection(HeaderBlock header)
    {
        endSection();
        int level = header.getLevel().getAsInt();
        while (this.headings.size() > this.floor && this.headings.peekLast().level() >= level) {
            this.headings.removeLast();
        }
        String title = this.renderer.render(header.getChildren());
        if (title.isBlank()) {
            // A heading without text, e.g. only an image without alternative text, gives no context.
            this.currentHeading = null;
        } else {
            this.headings.addLast(new Heading(level, title));
            this.currentHeading = newPart(header, this.renderer.render(List.of(header)));
        }
    }

    private void endSection()
    {
        if (this.currentHeading != null || !this.currentParts.isEmpty()) {
            this.sections.add(
                new Section(List.copyOf(this.headings), this.currentHeading, List.copyOf(this.currentParts)));
        }
        this.currentParts = new ArrayList<>();
    }
}
