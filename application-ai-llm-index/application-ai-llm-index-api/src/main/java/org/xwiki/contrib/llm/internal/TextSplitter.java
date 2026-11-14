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

import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.OptionalInt;

/**
 * Splits a text that is too long for one chunk into pieces of at most a maximum length, preferring to cut at the last
 * empty line, then line break, sentence end and space that keeps more than half of the new text that fits into the
 * piece, and cutting anywhere otherwise. Consecutive pieces overlap by up to the overlap length, see
 * {@link #getOverlapStart(String, int, int, int)}.
 *
 * @version $Id$
 * @since 1.0.2
 */
final class TextSplitter
{
    private static final List<String> SEPARATORS = List.of("\n\n", "\n");

    private static final char SPACE = ' ';

    private static final char NEW_LINE = '\n';

    /**
     * A piece of the text.
     *
     * @param text the text of the piece, without surrounding whitespace
     * @param start the offset of the piece in the text, surrounding whitespace included
     * @param end the offset after the piece in the text, surrounding whitespace included
     */
    record Piece(String text, int start, int end)
    {
    }

    private final int maxLength;

    private final int overlap;

    private final Locale locale;

    /**
     * @param maxLength the maximum length of a piece, at least 1
     * @param overlap the maximum length of the overlap between pieces, smaller than half the maximum length
     * @param locale the language of the text, for finding the ends of sentences
     */
    TextSplitter(int maxLength, int overlap, Locale locale)
    {
        this.maxLength = maxLength;
        this.overlap = overlap;
        this.locale = locale;
    }

    /**
     * @param text the text to split
     * @return the pieces of the text, in order; a text that fits is one piece
     */
    List<Piece> split(String text)
    {
        return split(text, 0);
    }

    /**
     * @param text the text to split
     * @param leadLength the length of the start of the text that repeats the end of the chunk before, including the
     *     separator after it: like the overlap of the other pieces, it does not count as new text, of which a piece
     *     keeps more than half of what fits beside it
     * @return the pieces of the text, in order; a text that fits is one piece
     */
    List<Piece> split(String text, int leadLength)
    {
        BreakIterator sentences = BreakIterator.getSentenceInstance(this.locale);
        sentences.setText(text);
        List<Piece> pieces = new ArrayList<>();
        int start = 0;
        // The length of the start of the piece that repeats the text before it.
        int repeated = leadLength;
        while (start < text.length()) {
            int end = Math.min(start + this.maxLength, text.length());
            if (end < text.length()) {
                end = findEnd(text, start, end, start + repeated + (this.maxLength - repeated) / 2, sentences);
            }
            String pieceText = text.substring(start, end).strip();
            if (!pieceText.isEmpty()) {
                pieces.add(new Piece(pieceText, start, end));
            }
            int nextStart = end < text.length() ? getOverlapStart(text, start, end, this.overlap, sentences) : end;
            repeated = end - nextStart;
            start = nextStart;
        }
        return pieces;
    }

    /**
     * @param minimum the offset the cut has to be after
     */
    private int findEnd(String text, int start, int end, int minimum, BreakIterator sentences)
    {
        String window = text.substring(start, end);
        for (String separator : SEPARATORS) {
            int separatorIndex = window.lastIndexOf(separator);
            if (separatorIndex >= 0 && start + separatorIndex + separator.length() > minimum) {
                return start + separatorIndex + separator.length();
            }
        }
        int sentenceEnd = sentences.isBoundary(end) ? end : sentences.preceding(end);
        if (sentenceEnd > minimum) {
            return sentenceEnd;
        }
        int spaceIndex = window.lastIndexOf(SPACE);
        return spaceIndex >= 0 && start + spaceIndex + 1 > minimum ? start + spaceIndex + 1 : end;
    }

    /**
     * Find where the overlap before a cut starts: at the first line start within the last {@code length} characters
     * before the cut (not counting whitespace before the cut), otherwise at the first sentence start, otherwise after
     * the first space; never within a word.
     *
     * @param text the text
     * @param start the start of the piece before the cut; the overlap starts after it
     * @param end the offset of the cut
     * @param length the maximum length of the overlap
     * @return the offset where the overlap starts, or {@code end} when there is none
     */
    int getOverlapStart(String text, int start, int end, int length)
    {
        BreakIterator sentences = BreakIterator.getSentenceInstance(this.locale);
        sentences.setText(text);
        return getOverlapStart(text, start, end, length, sentences);
    }

    private static int getOverlapStart(String text, int start, int end, int length, BreakIterator sentences)
    {
        int contentEnd = end;
        while (contentEnd > start && Character.isWhitespace(text.charAt(contentEnd - 1))) {
            contentEnd--;
        }
        int windowStart = Math.max(start + 1, contentEnd - length);
        return length > 0 && windowStart < contentEnd
            ? findOverlapStart(text, windowStart, contentEnd, sentences).orElse(end) : end;
    }

    private static OptionalInt findOverlapStart(String text, int windowStart, int contentEnd, BreakIterator sentences)
    {
        int lineStart = text.indexOf(NEW_LINE, windowStart - 1) + 1;
        // The text of the line, after the line breaks of an empty line and the indentation.
        while (lineStart > 0 && lineStart < contentEnd && Character.isWhitespace(text.charAt(lineStart))) {
            lineStart++;
        }
        if (lineStart > 0 && lineStart < contentEnd) {
            return OptionalInt.of(lineStart);
        }
        int sentenceStart = sentences.following(windowStart - 1);
        if (sentenceStart != BreakIterator.DONE && sentenceStart < contentEnd) {
            return OptionalInt.of(sentenceStart);
        }
        int space = text.indexOf(SPACE, windowStart - 1);
        return space >= 0 && space + 1 < contentEnd ? OptionalInt.of(space + 1) : OptionalInt.empty();
    }
}
