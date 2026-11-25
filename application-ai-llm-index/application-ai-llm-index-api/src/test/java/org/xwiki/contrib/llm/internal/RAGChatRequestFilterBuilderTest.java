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

import javax.inject.Named;
import javax.inject.Provider;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.mockito.Mock;
import org.xwiki.component.manager.ComponentManager;
import org.xwiki.contrib.llm.ChatRequestFilter;
import org.xwiki.contrib.llm.CollectionManager;
import org.xwiki.contrib.llm.HybridSearchParameters;
import org.xwiki.contrib.llm.openai.ChatCompletionRequest;
import org.xwiki.contrib.llm.openai.ChatCompletionResult;
import org.xwiki.contrib.llm.openai.ChatMessage;
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.test.LogLevel;
import org.xwiki.test.junit5.LogCaptureExtension;
import org.xwiki.test.junit5.mockito.ComponentTest;
import org.xwiki.test.junit5.mockito.InjectMockComponents;
import org.xwiki.test.junit5.mockito.MockComponent;

import com.xpn.xwiki.XWikiContext;
import com.xpn.xwiki.objects.BaseObject;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit test for {@link RAGChatRequestFilterBuilder}.
 *
 * @version $Id$
 */
@ComponentTest
class RAGChatRequestFilterBuilderTest
{
    private static final String QUESTION = "How do I install zsh?";

    private static final List<String> COLLECTIONS = List.of("handbook");

    private static final String MIN_SEMANTIC_SCORE = "minSemanticScore";

    private static final DocumentReference FILTER_PAGE = new DocumentReference("xwiki", "AI", "Filter");

    @RegisterExtension
    private LogCaptureExtension logCapture = new LogCaptureExtension(LogLevel.ERROR);

    @InjectMockComponents
    private RAGChatRequestFilterBuilder builder;

    @MockComponent
    @Named("context")
    private Provider<ComponentManager> componentManagerProvider;

    @Mock
    private ComponentManager componentManager;

    @Mock
    private CollectionManager collectionManager;

    @Mock
    private Provider<XWikiContext> contextProvider;

    @Mock
    private XWikiContext context;

    @Mock
    private BaseObject object;

    @BeforeEach
    void setUp() throws Exception
    {
        when(this.componentManagerProvider.get()).thenReturn(this.componentManager);
        when(this.componentManager.getInstance(CollectionManager.class)).thenReturn(this.collectionManager);
        when(this.componentManager.getInstance(XWikiContext.TYPE_PROVIDER)).thenReturn(this.contextProvider);
        when(this.contextProvider.get()).thenReturn(this.context);
        when(this.context.getWikiId()).thenReturn("xwiki");

        doReturnCollections();
        when(this.object.getIntValue("maxResults")).thenReturn(3);
        when(this.object.getIntValue("maxKeywordResults")).thenReturn(7);
        when(this.object.getDocumentReference()).thenReturn(FILTER_PAGE);
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    private void doReturnCollections()
    {
        when(this.object.getListValue("collections")).thenReturn((List) COLLECTIONS);
    }

    private void processQuestion(ChatRequestFilter filter) throws Exception
    {
        ChatRequestFilter next = mock();
        when(next.process(any())).thenReturn(new ChatCompletionResult(null, null, 0, null, List.of(), null));
        filter.setNext(next);
        filter.process(ChatCompletionRequest.builder().messages(List.of(new ChatMessage("user", QUESTION))).build());
    }

    @Test
    void filterSearchesWithTheConfiguredMinimumSemanticScore() throws Exception
    {
        when(this.object.getDoubleValue(MIN_SEMANTIC_SCORE)).thenReturn(0.5);

        List<ChatRequestFilter> filters = this.builder.build(this.object);

        assertEquals(1, filters.size());
        processQuestion(filters.get(0));
        verify(this.collectionManager).hybridSearch(QUESTION, COLLECTIONS, new HybridSearchParameters(3, 7, null, 0.5));
    }

    @Test
    void filterWithoutMinimumSemanticScoreSearchesWithoutMinimum() throws Exception
    {
        List<ChatRequestFilter> filters = this.builder.build(this.object);

        processQuestion(filters.get(0));
        verify(this.collectionManager).hybridSearch(QUESTION, COLLECTIONS, new HybridSearchParameters(3, 7, null, 0));
    }

    @Test
    void invalidMinimumSemanticScoreBuildsNoFilter()
    {
        when(this.object.getDoubleValue(MIN_SEMANTIC_SCORE)).thenReturn(1.5);
        // BaseObjectReference cannot be created without the platform; the reference is only logged.
        when(this.object.getReference()).thenReturn(null);

        assertEquals(List.of(), this.builder.build(this.object));

        assertEquals(1, this.logCapture.size());
        assertEquals("Invalid configuration of the RAG chat request filter [null]: [The minimum semantic score must be"
            + " between 0 and 1, got [1.5]]", this.logCapture.getMessage(0));
    }

    @Test
    void filterWithOnePositiveLimitKeepsTheConfiguredLimits() throws Exception
    {
        when(this.object.getIntValue("maxResults")).thenReturn(5);
        when(this.object.getIntValue("maxKeywordResults")).thenReturn(-10);

        processQuestion(this.builder.build(this.object).get(0));

        verify(this.collectionManager).hybridSearch(QUESTION, COLLECTIONS, new HybridSearchParameters(5, -10, null, 0));
    }

    @Test
    void filterWithoutPositiveLimitsSearchesWithTheDefaultLimitsAndTheConfiguredMinimum() throws Exception
    {
        when(this.object.getIntValue("maxResults")).thenReturn(0);
        when(this.object.getIntValue("maxKeywordResults")).thenReturn(-1);
        when(this.object.getDoubleValue(MIN_SEMANTIC_SCORE)).thenReturn(0.5);

        processQuestion(this.builder.build(this.object).get(0));

        verify(this.collectionManager).hybridSearch(QUESTION, COLLECTIONS, new HybridSearchParameters(3, 7, null, 0.5));
    }
}
