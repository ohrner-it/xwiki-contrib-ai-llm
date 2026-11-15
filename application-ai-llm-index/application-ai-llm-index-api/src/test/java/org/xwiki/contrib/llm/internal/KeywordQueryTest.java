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

import java.util.Locale;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Unit test for {@link KeywordQuery}. The expected queries follow from the Snowball stop word lists of Lucene.
 *
 * @version $Id$
 */
class KeywordQueryTest
{
    @Test
    void stopWordsAreLeftOutAndRepeatedTermsKeptOnce()
    {
        assertEquals("install zsh oh rise",
            KeywordQuery.toQuery("How do I install zsh and oh my zsh at rise?", null));
    }

    @Test
    void termsDifferingInCaseOnlyAreKeptOnceInTheirFirstSpelling()
    {
        assertEquals("Zsh", KeywordQuery.toQuery("Zsh zsh ZSH", null));
    }

    @Test
    void punctuationAroundATermIsIgnored()
    {
        assertEquals("authenticator LDAP", KeywordQuery.toQuery("How, the. authenticator? (LDAP) --- ...", null));
    }

    @Test
    void combiningMarksAndConnectorPunctuationArePartOfTheTerm()
    {
        assertEquals("हिंदी में लॉगिन कैसे करें", KeywordQuery.toQuery("हिंदी में लॉगिन कैसे करें?", null));
        assertEquals("cafe\u0301 _version_", KeywordQuery.toQuery("cafe\u0301, _version_.", null));
    }

    @Test
    void stopWordsWithApostrophesAreLeftOut()
    {
        assertEquals("panic", KeywordQuery.toQuery("Don't panic", null));
        assertEquals("panic fine", KeywordQuery.toQuery("Don\u2019t panic, it\u2019s fine", null));
    }

    @Test
    void termWithOtherCharactersInsideIsAPhrase()
    {
        assertEquals("\"zxqv-nothing-matches-this\" \"oh-my-zsh\"",
            KeywordQuery.toQuery("zxqv-nothing-matches-this oh-my-zsh", null));
    }

    @Test
    void querySyntaxIsNotPassedOn()
    {
        assertEquals("foo bar \"title:secret\" \"a*b\" regex",
            KeywordQuery.toQuery("-foo +bar AND || title:secret a*b /regex/ OR NOT", null));
    }

    @Test
    void quotesAndBackslashesInAPhraseAreEscaped()
    {
        assertEquals("\"a\\\"b\\\\c\"", KeywordQuery.toQuery("a\"b\\c", null));
    }

    @Test
    void stopWordsOfTheLanguageAndOfEnglishAreLeftOut()
    {
        assertEquals("installiere Software zsh",
            KeywordQuery.toQuery("Wie installiere ich die Software with the zsh AND?", Locale.GERMAN));
    }

    @Test
    void acronymIsNotAStopWord()
    {
        assertEquals("deploy WAR IT", KeywordQuery.toQuery("Wie deploy ich das WAR in der IT", Locale.GERMAN));
    }

    @Test
    void textInCapitalsHasNoAcronyms()
    {
        assertEquals("INSTALL ZSH", KeywordQuery.toQuery("HOW DO I INSTALL ZSH?", null));
    }

    @Test
    void termWithoutLetterOrDigitIsLeftOut()
    {
        assertEquals("zsh", KeywordQuery.toQuery("_ \u0301 zsh", null));
    }

    @Test
    void unicodeSpacesSeparateTerms()
    {
        assertEquals("foo bar \"安装\" \"软件\"", KeywordQuery.toQuery("foo\u00a0bar 安装\u3000软件", null));
    }

    @Test
    void languageWithoutStopWordListIsIgnored()
    {
        assertEquals("\"ルール\"", KeywordQuery.toQuery("the ルール", Locale.JAPANESE));
    }

    @Test
    void chineseOrJapaneseTermIsAPhrase()
    {
        assertEquals("\"安装软件\" zsh", KeywordQuery.toQuery("安装软件 zsh", null));
    }

    @Test
    void anyWhitespaceSeparatesTerms()
    {
        assertEquals("zsh setup guide", KeywordQuery.toQuery("zsh\nsetup\tguide", null));
    }

    @Test
    void textWithOnlyStopWordsGivesNoQuery()
    {
        assertEquals("", KeywordQuery.toQuery("How do I?", null));
    }

    @Test
    void blankTextGivesNoQuery()
    {
        assertEquals("", KeywordQuery.toQuery(" \n ", null));
    }

    @Test
    void numberOfTermsIsLimited()
    {
        String text = IntStream.range(0, 70).mapToObj(i -> "term" + i).collect(Collectors.joining(" "));
        String expected = IntStream.range(0, 64).mapToObj(i -> "term" + i).collect(Collectors.joining(" "));

        assertEquals(expected, KeywordQuery.toQuery(text, null));
    }
}
