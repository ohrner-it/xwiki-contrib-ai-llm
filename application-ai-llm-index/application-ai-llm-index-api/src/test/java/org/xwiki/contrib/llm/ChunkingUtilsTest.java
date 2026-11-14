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
package org.xwiki.contrib.llm;

import java.util.List;
import java.util.Map;

import javax.inject.Named;
import javax.inject.Provider;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.mockito.Mock;
import org.xwiki.component.manager.ComponentLookupException;
import org.xwiki.component.manager.ComponentManager;
import org.xwiki.test.LogLevel;
import org.xwiki.test.junit5.LogCaptureExtension;
import org.xwiki.test.junit5.mockito.ComponentTest;
import org.xwiki.test.junit5.mockito.InjectComponentManager;
import org.xwiki.test.junit5.mockito.InjectMockComponents;
import org.xwiki.test.junit5.mockito.MockComponent;
import org.xwiki.test.mockito.MockitoComponentManager;

import com.xpn.xwiki.XWikiContext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Component test for {@link ChunkingUtils}.
 *
 * @version $Id$
 */
@ComponentTest
class ChunkingUtilsTest
{
    private static final String WIKI = "mywiki";

    private static final String COLLECTION_ID = "collection_1";

    private static final String DOCUMENT_ID = "Space.Page";

    private static final String DOCUMENT_URL = "https://wiki.example/xwiki/bin/view/Space/Page";

    private static final String LANGUAGE = "de";

    private static final String STORE_HINT = "xwiki";

    private static final String METHOD_HINT = "test";

    @RegisterExtension
    private LogCaptureExtension logCapture = new LogCaptureExtension(LogLevel.ERROR);

    @InjectMockComponents
    private ChunkingUtils chunkingUtils;

    @InjectComponentManager
    private MockitoComponentManager componentManager;

    @MockComponent
    @Named("context")
    private Provider<ComponentManager> contextComponentManagerProvider;

    @MockComponent
    private CollectionManager collectionManager;

    @MockComponent
    private Provider<Chunk> chunkProvider;

    @MockComponent
    private Provider<XWikiContext> contextProvider;

    @Mock
    private XWikiContext xWikiContext;

    @Mock
    private Collection collection;

    @Mock(name = "Space.Page")
    private Document document;

    private ChunkingMethod chunkingMethod;

    @BeforeEach
    void setUp() throws Exception
    {
        this.chunkingMethod = this.componentManager.registerMockComponent(ChunkingMethod.class, METHOD_HINT);
        when(this.contextComponentManagerProvider.get()).thenReturn(this.componentManager);
        when(this.chunkProvider.get()).thenAnswer(invocation -> new Chunk());
        when(this.contextProvider.get()).thenReturn(this.xWikiContext);
        when(this.xWikiContext.getWikiId()).thenReturn(WIKI);

        when(this.collection.getID()).thenReturn(COLLECTION_ID);
        when(this.collection.getChunkingMethod()).thenReturn(METHOD_HINT);
        when(this.collection.getChunkingMaxSize()).thenReturn(100);
        when(this.collection.getChunkingOverlapOffset()).thenReturn(10);
        when(this.collection.getDocumentStoreHint()).thenReturn(STORE_HINT);
        when(this.collectionManager.getCollection(COLLECTION_ID)).thenReturn(this.collection);

        when(this.document.getID()).thenReturn(DOCUMENT_ID);
        when(this.document.getCollection()).thenReturn(COLLECTION_ID);
        when(this.document.getURL()).thenReturn(DOCUMENT_URL);
        when(this.document.getLanguage()).thenReturn(LANGUAGE);
    }

    @Test
    void chunkDocumentTurnsTheTextsOfTheCollectionsChunkingMethodIntoChunks() throws Exception
    {
        when(this.chunkingMethod.chunk(this.collection, this.document))
            .thenReturn(List.of(new ChunkText("First text", 0, 10), new ChunkText("Second text", 8, 19)));

        List<Chunk> chunks = this.chunkingUtils.chunkDocument(this.collection, this.document);

        assertEquals(2, chunks.size());
        assertChunk(chunks.get(0), 0, "First text", 0, 10);
        assertChunk(chunks.get(1), 1, "Second text", 8, 19);
    }

    @Test
    void chunkDocumentWithoutCollectionReturnsTheChunksByIndex() throws Exception
    {
        when(this.chunkingMethod.chunk(this.collection, this.document))
            .thenReturn(List.of(new ChunkText("First text", 0, 10), new ChunkText("Second text", 8, 19)));

        Map<Integer, Chunk> chunks = this.chunkingUtils.chunkDocument(this.document);

        assertEquals(List.of(0, 1), chunks.keySet().stream().sorted().toList());
        assertChunk(chunks.get(0), 0, "First text", 0, 10);
        assertChunk(chunks.get(1), 1, "Second text", 8, 19);
    }

    @Test
    void chunkDocumentWithAChunkingMethodThatIsNotInstalledFails() throws Exception
    {
        when(this.collection.getChunkingMethod()).thenReturn("missing");

        IndexException exception = assertThrows(IndexException.class,
            () -> this.chunkingUtils.chunkDocument(this.collection, this.document));

        assertEquals("The chunking method [missing] of the collection [collection_1] is not installed",
            exception.getMessage());
    }

    @Test
    void chunkDocumentWithAChunkingMethodThatCannotBeLoadedFails() throws Exception
    {
        ComponentManager componentManager = mock();
        when(this.contextComponentManagerProvider.get()).thenReturn(componentManager);
        when(componentManager.hasComponent(ChunkingMethod.class, METHOD_HINT)).thenReturn(true);
        ComponentLookupException cause = new ComponentLookupException("Failed to initialize");
        when(componentManager.getInstance(ChunkingMethod.class, METHOD_HINT)).thenThrow(cause);

        IndexException exception = assertThrows(IndexException.class,
            () -> this.chunkingUtils.chunkDocument(this.collection, this.document));

        assertEquals("The chunking method [test] of the collection [collection_1] could not be loaded",
            exception.getMessage());
        assertSame(cause, exception.getCause());
    }

    @Test
    void chunkDocumentWithoutCollectionReturnsNoChunksWhenChunkingFails()
    {
        when(this.collection.getChunkingMethod()).thenReturn("missing");

        assertEquals(Map.of(), this.chunkingUtils.chunkDocument(this.document));
        assertEquals("Error while chunking the document [Space.Page]: [The chunking method [missing] of the collection "
            + "[collection_1] is not installed]", this.logCapture.getMessage(0));
    }

    @Test
    void chunkDocumentRejectsAMaximumSizeBelowTen() throws Exception
    {
        when(this.collection.getChunkingMaxSize()).thenReturn(9);
        when(this.collection.getChunkingOverlapOffset()).thenReturn(0);

        IndexException exception = assertThrows(IndexException.class,
            () -> this.chunkingUtils.chunkDocument(this.collection, this.document));

        assertEquals("The maximum chunk size must be at least 10 characters", exception.getMessage());
        verify(this.chunkingMethod, never()).chunk(any(), any());
    }

    @Test
    void chunkDocumentRejectsANegativeOverlap() throws Exception
    {
        when(this.collection.getChunkingOverlapOffset()).thenReturn(-1);

        IndexException exception = assertThrows(IndexException.class,
            () -> this.chunkingUtils.chunkDocument(this.collection, this.document));

        assertEquals("The overlap offset must not be negative", exception.getMessage());
        verify(this.chunkingMethod, never()).chunk(any(), any());
    }

    @Test
    void chunkDocumentRejectsAnOverlapOfHalfTheMaximumSize() throws Exception
    {
        when(this.collection.getChunkingOverlapOffset()).thenReturn(50);

        IndexException exception = assertThrows(IndexException.class,
            () -> this.chunkingUtils.chunkDocument(this.collection, this.document));

        assertEquals("The overlap offset must be smaller than half of the maximum chunk size",
            exception.getMessage());
        verify(this.chunkingMethod, never()).chunk(any(), any());
    }

    private static void assertChunk(Chunk chunk, int index, String content, int posFirstChar, int posLastChar)
    {
        assertEquals(index, chunk.getChunkIndex());
        assertEquals(content, chunk.getContent());
        assertEquals(posFirstChar, chunk.getPosFirstChar());
        assertEquals(posLastChar, chunk.getPosLastChar());
        assertEquals(DOCUMENT_ID, chunk.getDocumentID());
        assertEquals(COLLECTION_ID, chunk.getCollection());
        assertEquals(DOCUMENT_URL, chunk.getDocumentURL());
        assertEquals(LANGUAGE, chunk.getLanguage());
        assertEquals(WIKI, chunk.getWiki());
        assertEquals(STORE_HINT, chunk.getStoreHint());
        assertEquals("mywiki_collection%5F1_Space.Page_" + index, chunk.getId());
    }
}
