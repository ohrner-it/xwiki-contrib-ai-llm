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
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;
import org.xwiki.contrib.llm.ChunkText;
import org.xwiki.contrib.llm.internal.DocumentSections.Heading;
import org.xwiki.contrib.llm.internal.DocumentSections.Part;
import org.xwiki.contrib.llm.internal.DocumentSections.Section;
import org.xwiki.contrib.llm.internal.TextSplitter.Piece;

/**
 * Packs the sections of a document into chunks for {@link SectionsChunkingMethod}.
 * <ul>
 * <li>A chunk holds whole sections. A section is added to the chunk of the section before it when it is nested in the
 * parent of the first section of the chunk (a subsection of it, a following sibling, or a subsection of one) and the
 * chunk still fits; so sections of different branches are never merged. A section without heading of its own (the text
 * after a group or macro content with headings) starts a chunk, which takes only sections nested in its path.</li>
 * <li>Each chunk starts with a breadcrumb: the breadcrumb of the document followed by the headings all sections of
 * the chunk are nested in, separated by {@code >}, then an empty line. The headings of the sections below these
 * common headings are lines of the text. A breadcrumb longer than a quarter of the maximum size is shortened by
 * replacing elements after the first one by an ellipsis, and cut at that length when this is not enough.</li>
 * <li>A section that does not fit into a chunk on its own is split between its blocks, and a block that does not fit
 * is split by {@link BlockSplitter}. The chunks of a split section overlap by up to the overlap length; chunks of
 * different sections do not overlap, as each starts with the headings it belongs to.</li>
 * </ul>
 * The maximum size applies to the whole text of a chunk, breadcrumb included, as that is the text that is embedded.
 *
 * @version $Id$
 * @since 1.0.2
 */
final class SectionPacker
{
    private static final String EMPTY_LINE = "\n\n";

    private static final String BREADCRUMB_SEPARATOR = " > ";

    private static final String ELLIPSIS = "…";

    private final IndexTextRenderer renderer;

    private final List<String> documentBreadcrumb;

    private final int maxSize;

    private final int overlap;

    private final Locale locale;

    /**
     * @param renderer the renderer of split blocks
     * @param documentBreadcrumb the breadcrumb of the document
     * @param maxSize the maximum size of a chunk, at least 10
     * @param overlap the maximum overlap between the chunks of a section that is split
     * @param locale the language of the document
     */
    SectionPacker(IndexTextRenderer renderer, List<String> documentBreadcrumb, int maxSize, int overlap,
        Locale locale)
    {
        this.renderer = renderer;
        this.documentBreadcrumb = documentBreadcrumb;
        this.maxSize = maxSize;
        this.overlap = overlap;
        this.locale = locale;
    }

    /**
     * @param sections the sections of the document, in order
     * @return the chunks, in order
     */
    List<ChunkText> pack(List<Section> sections)
    {
        List<ChunkText> result = new ArrayList<>();
        List<Section> group = new ArrayList<>();
        for (Section section : sections) {
            if (!group.isEmpty() && canJoin(group.get(0), section) && fits(with(group, section))) {
                group.add(section);
            } else {
                render(group).ifPresent(result::add);
                group = new ArrayList<>(List.of(section));
                if (!fits(group)) {
                    result.addAll(split(section));
                    group = new ArrayList<>();
                }
            }
        }
        render(group).ifPresent(result::add);
        return result;
    }

    private static boolean canJoin(Section first, Section section)
    {
        // See the class description for sections without heading of their own.
        if (section.heading() == null) {
            return false;
        }
        List<Heading> parent = first.heading() == null ? first.path()
            : first.path().subList(0, first.path().size() - 1);
        return section.path().size() >= parent.size()
            && section.path().subList(0, parent.size()).equals(parent);
    }

    private static List<Section> with(List<Section> group, Section section)
    {
        List<Section> result = new ArrayList<>(group);
        result.add(section);
        return result;
    }

    private boolean fits(List<Section> group)
    {
        return render(group).map(chunk -> chunk.content().length() <= this.maxSize).orElse(true);
    }

    /**
     * @return the chunk of the given sections, or nothing when there are none or they contain only headings
     */
    private Optional<ChunkText> render(List<Section> group)
    {
        if (group.isEmpty()) {
            return Optional.empty();
        }
        List<Heading> commonPath = getCommonPath(group);
        List<Part> parts = new ArrayList<>();
        boolean hasContent = false;
        for (Section section : group) {
            if (section.heading() != null && section.path().size() > commonPath.size()) {
                parts.add(section.heading());
            }
            parts.addAll(section.parts());
            hasContent |= !section.parts().isEmpty();
        }
        if (!hasContent) {
            return Optional.empty();
        }
        return Optional.of(toChunk(getBreadcrumb(commonPath), parts));
    }

    private static List<Heading> getCommonPath(List<Section> group)
    {
        List<Heading> result = group.get(0).path();
        for (Section section : group) {
            int length = 0;
            while (length < result.size() && length < section.path().size()
                && result.get(length).equals(section.path().get(length))) {
                length++;
            }
            result = result.subList(0, length);
        }
        return result;
    }

    /**
     * Split a section that does not fit into a chunk on its own: as many blocks as fit go into one chunk, a block
     * that does not fit by itself is split into several chunks. Each chunk after the first one starts with a lead, the
     * end of the chunk before (see {@link #getLead(List, int, TextSplitter)}), as far as it fits.
     */
    private List<ChunkText> split(Section section)
    {
        String breadcrumb = getBreadcrumb(section.path());
        int maxLength = Math.max(1, this.maxSize - withBreadcrumb(breadcrumb, "").length());
        // The overlap is limited by the space the breadcrumb leaves.
        int maxOverlap = Math.min(this.overlap, (maxLength - 1) / 2);
        TextSplitter textSplitter = new TextSplitter(maxLength, maxOverlap, this.locale);
        BlockSplitter blockSplitter = new BlockSplitter(this.renderer, textSplitter, maxLength, maxOverlap);

        List<ChunkText> result = new ArrayList<>();
        // The parts of the chunk being filled, and those of the chunk before, which the lead is taken from.
        List<Part> pending = new ArrayList<>();
        List<Part> previous = List.of();
        for (Part part : section.parts()) {
            if (!pending.isEmpty() && join(pending).length() + EMPTY_LINE.length() + part.text().length()
                > maxLength) {
                result.add(toChunk(breadcrumb, pending));
                previous = pending;
                pending = new ArrayList<>();
            }
            if (part.text().length() > maxLength) {
                Part lead = getLead(previous, maxOverlap, textSplitter);
                List<Piece> pieces = blockSplitter.split(part, lead);
                pieces.forEach(piece -> result.add(
                    new ChunkText(withBreadcrumb(breadcrumb, piece.text()), piece.start(), piece.end())));
                // The last piece of a split table, list or code block is no text to take a lead from.
                previous = pieces.isEmpty() || !isText(part) ? List.of()
                    : List.of(toPart(pieces.get(pieces.size() - 1)));
            } else {
                if (pending.isEmpty()) {
                    // The lead and the block have to fit together.
                    Part lead = getLead(previous,
                        Math.min(maxOverlap, maxLength - EMPTY_LINE.length() - part.text().length()), textSplitter);
                    if (lead != null) {
                        pending.add(lead);
                    }
                }
                pending.add(part);
            }
        }
        if (!pending.isEmpty()) {
            result.add(toChunk(breadcrumb, pending));
        }
        return result;
    }

    /**
     * Take the lead of the next chunk from the end of a chunk: its last text blocks that fit as a whole, but never all
     * of its blocks; otherwise the end of its last text block (see
     * {@link TextSplitter#getOverlapStart(String, int, int, int)}). A table, list or code block gives no lead: cut out
     * of its rendered text, the lead could be bare markup, and their own pieces already repeat rows, items or lines.
     *
     * @param parts the parts of the chunk
     * @param length the maximum length of the lead
     * @return the lead, or {@code null} for none
     */
    private static Part getLead(List<Part> parts, int length, TextSplitter textSplitter)
    {
        int first = parts.size();
        int leadLength = -EMPTY_LINE.length();
        while (first > 1 && isText(parts.get(first - 1))
            && leadLength + EMPTY_LINE.length() + parts.get(first - 1).text().length() <= length) {
            leadLength += EMPTY_LINE.length() + parts.get(first - 1).text().length();
            first--;
        }
        Part result = null;
        if (first < parts.size()) {
            List<Part> wholeParts = parts.subList(first, parts.size());
            result = new Part(null, join(wholeParts), wholeParts.get(0).start(),
                wholeParts.get(wholeParts.size() - 1).end());
        } else if (!parts.isEmpty() && isText(parts.get(parts.size() - 1))) {
            Part last = parts.get(parts.size() - 1);
            String text = last.text();
            int leadStart = textSplitter.getOverlapStart(text, 0, text.length(), length);
            String lead = text.substring(leadStart).strip();
            if (!lead.isEmpty()) {
                int offset = text.indexOf(lead, leadStart);
                result = new Part(null, lead, last.start() + offset, last.start() + offset + lead.length());
            }
        }
        return result;
    }

    private static Part toPart(Piece piece)
    {
        return new Part(null, piece.text(), piece.start(), piece.end());
    }

    private static boolean isText(Part part)
    {
        return !BlockSplitter.isStructured(part.block());
    }

    private static ChunkText toChunk(String breadcrumb, List<Part> parts)
    {
        return new ChunkText(withBreadcrumb(breadcrumb, join(parts)), parts.get(0).start(),
            parts.get(parts.size() - 1).end());
    }

    private static String join(List<Part> parts)
    {
        return parts.stream().map(Part::text).collect(Collectors.joining(EMPTY_LINE));
    }

    private static String withBreadcrumb(String breadcrumb, String text)
    {
        return breadcrumb.isEmpty() ? text : breadcrumb + EMPTY_LINE + text;
    }

    private String getBreadcrumb(List<Heading> path)
    {
        List<String> elements = new ArrayList<>(this.documentBreadcrumb);
        path.forEach(heading -> elements.add(heading.title()));
        int maxLength = this.maxSize / 4;
        boolean shortened = true;
        while (String.join(BREADCRUMB_SEPARATOR, elements).length() > maxLength && shortened) {
            shortened = shorten(elements);
        }
        String breadcrumb = String.join(BREADCRUMB_SEPARATOR, elements);
        // Very long elements are cut, so that the text of a chunk keeps at least three quarters of the maximum size.
        return breadcrumb.length() > maxLength
            ? StringUtils.removeEnd(breadcrumb.substring(0, Math.max(0, maxLength - ELLIPSIS.length())).stripTrailing(),
                BREADCRUMB_SEPARATOR.strip()).stripTrailing() + ELLIPSIS
            : breadcrumb;
    }

    /**
     * Replace the element after the first one by an ellipsis, or remove the element after the ellipsis.
     *
     * @return {@code false} when the breadcrumb cannot be shortened any more
     */
    private static boolean shorten(List<String> elements)
    {
        if (elements.size() > 2 && !ELLIPSIS.equals(elements.get(1))) {
            elements.set(1, ELLIPSIS);
            return true;
        }
        if (elements.size() > 3) {
            elements.remove(2);
            return true;
        }
        return false;
    }
}
