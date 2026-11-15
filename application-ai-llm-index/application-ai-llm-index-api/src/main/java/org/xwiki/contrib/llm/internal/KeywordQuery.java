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

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.apache.lucene.analysis.CharArraySet;
import org.apache.lucene.analysis.WordlistLoader;
import org.apache.lucene.analysis.snowball.SnowballFilter;
import org.apache.lucene.util.IOUtils;

/**
 * Turns the text of a keyword search, typically a question in natural language, into a query for Solr's extended
 * DisMax parser: the terms of the text without stop words, so that function words like "how", "do" or "the" do not
 * make a chunk match.
 * <ul>
 * <li>The stop words are those of English, which questions often mix in, and those of the given language when Lucene
 * has a Snowball list for it. In a text that is not written in capitals throughout, a term in capitals of two or more
 * letters, e.g. "WAR" or "IT", is an acronym and not a stop word, except the boolean operators AND, OR and NOT: as
 * English stop words, they never reach the parser.</li>
 * <li>Characters other than letters, digits, combining marks and connector punctuation (e.g. "_") are removed at the
 * start and the end of a term, and a term without any letter or digit is left out. A term that still contains such
 * characters (e.g. {@code oh-my-zsh} or
 * {@code title:secret}) is quoted, so that it matches as a phrase, without query syntax; so is a term with Chinese or
 * Japanese characters, which the index splits into single characters.</li>
 * <li>Terms are kept once, in the spelling of their first occurrence, and their number is limited, below Solr's
 * maximum number of clauses.</li>
 * </ul>
 * The Snowball lists come from Lucene, which the platform's Solr API brings; it is deliberately not a declared
 * dependency, so that its version is always the one of the platform.
 *
 * @version $Id$
 * @since 1.0.1
 */
public final class KeywordQuery
{
    private static final int MAX_TERMS = 64;

    private static final Pattern WHITESPACE = Pattern.compile("\\s+", Pattern.UNICODE_CHARACTER_CLASS);

    private static final String QUOTE = "\"";

    private static final String NORWEGIAN = "norwegian_stop.txt";

    private static final Set<String> OPERATORS = Set.of("AND", "OR", "NOT");

    /**
     * The character types that belong to a word besides letters and digits: combining marks, e.g. the vowel signs of
     * Devanagari, and connector punctuation, e.g. "_".
     */
    private static final Set<Integer> OTHER_WORD_CHARACTER_TYPES = Set.of((int) Character.NON_SPACING_MARK,
        (int) Character.COMBINING_SPACING_MARK, (int) Character.ENCLOSING_MARK, (int) Character.CONNECTOR_PUNCTUATION);

    /**
     * The Snowball stop word list of each supported language.
     */
    private static final Map<String, String> STOP_WORD_FILES = Map.ofEntries(
        Map.entry("da", "danish_stop.txt"), Map.entry("de", "german_stop.txt"), Map.entry("en", "english_stop.txt"),
        Map.entry("es", "spanish_stop.txt"), Map.entry("fi", "finnish_stop.txt"), Map.entry("fr", "french_stop.txt"),
        Map.entry("hu", "hungarian_stop.txt"), Map.entry("id", "indonesian_stop.txt"),
        Map.entry("it", "italian_stop.txt"), Map.entry("nb", NORWEGIAN),
        Map.entry("nl", "dutch_stop.txt"), Map.entry("nn", NORWEGIAN), Map.entry("no", NORWEGIAN),
        Map.entry("pt", "portuguese_stop.txt"), Map.entry("ru", "russian_stop.txt"),
        Map.entry("sv", "swedish_stop.txt"));

    private static final Map<String, CharArraySet> STOP_WORDS = new ConcurrentHashMap<>();

    private KeywordQuery()
    {
    }

    /**
     * @param text the text to search for
     * @param language the language of the text, whose stop words are left out along with the English ones, or
     *     {@code null} when it is unknown; a language without a stop word list is ignored
     * @return the query, or an empty string when no term remains
     */
    public static String toQuery(String text, Locale language)
    {
        List<CharArraySet> stopWords = Stream.of(Locale.ENGLISH.getLanguage(),
                language != null ? language.getLanguage() : "")
            .distinct()
            .filter(STOP_WORD_FILES::containsKey)
            .map(KeywordQuery::getStopWords)
            .toList();

        boolean writtenInCapitals = text.codePoints().noneMatch(Character::isLowerCase);
        // The clause of each term, by the term in lower case.
        Map<String, String> clauses = new LinkedHashMap<>();
        for (String word : WHITESPACE.split(text.strip())) {
            String term = stripPunctuation(word);
            // Stop word lists use the ASCII apostrophe, keyboards often produce the typographic one.
            String key = term.toLowerCase(Locale.ROOT).replace('\u2019', '\'');
            boolean isStopWord = (writtenInCapitals || !isAcronym(term))
                && stopWords.stream().anyMatch(set -> set.contains(key));
            if (hasLetterOrDigit(term) && !isStopWord && clauses.size() < MAX_TERMS) {
                clauses.putIfAbsent(key, toClause(term));
            }
        }
        return String.join(" ", clauses.values());
    }

    private static CharArraySet getStopWords(String language)
    {
        return STOP_WORDS.computeIfAbsent(language, key -> {
            String file = STOP_WORD_FILES.get(key);
            try (InputStream stream = SnowballFilter.class.getResourceAsStream(file)) {
                return CharArraySet.unmodifiableSet(WordlistLoader.getSnowballWordSet(
                    IOUtils.requireResourceNonNull(stream, file), StandardCharsets.UTF_8));
            } catch (IOException e) {
                throw new UncheckedIOException("Failed to load the stop words of the language [%s]".formatted(key), e);
            }
        });
    }

    private static boolean isWordCharacter(int codePoint)
    {
        return Character.isLetterOrDigit(codePoint)
            || OTHER_WORD_CHARACTER_TYPES.contains(Character.getType(codePoint));
    }

    private static boolean hasLetterOrDigit(String term)
    {
        return term.codePoints().anyMatch(Character::isLetterOrDigit);
    }

    private static boolean isAcronym(String term)
    {
        return term.codePointCount(0, term.length()) >= 2 && !OPERATORS.contains(term)
            && term.codePoints().allMatch(Character::isUpperCase);
    }

    /**
     * @return the word without the characters other than word characters at its start and end
     */
    private static String stripPunctuation(String word)
    {
        int start = 0;
        int end = word.length();
        while (start < end && !isWordCharacter(word.codePointAt(start))) {
            start += Character.charCount(word.codePointAt(start));
        }
        while (end > start && !isWordCharacter(word.codePointBefore(end))) {
            end -= Character.charCount(word.codePointBefore(end));
        }
        return word.substring(start, end);
    }

    private static String toClause(String term)
    {
        boolean isPhrase = term.codePoints().anyMatch(codePoint -> !isWordCharacter(codePoint)
            || isSplitIntoCharacters(Character.UnicodeScript.of(codePoint)));
        if (!isPhrase) {
            return term;
        }
        return QUOTE + term.replace("\\", "\\\\").replace(QUOTE, "\\\"") + QUOTE;
    }

    private static boolean isSplitIntoCharacters(Character.UnicodeScript script)
    {
        return script == Character.UnicodeScript.HAN || script == Character.UnicodeScript.HIRAGANA
            || script == Character.UnicodeScript.KATAKANA;
    }
}
