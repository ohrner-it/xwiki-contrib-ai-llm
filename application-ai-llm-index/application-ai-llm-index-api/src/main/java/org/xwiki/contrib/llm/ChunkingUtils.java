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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Provider;
import javax.inject.Singleton;

import org.slf4j.Logger;
import org.xwiki.component.annotation.Component;
import org.xwiki.component.manager.ComponentLookupException;
import org.xwiki.component.manager.ComponentManager;

import com.xpn.xwiki.XWikiContext;

/**
 * Utility class used in chunking the documents: splits a document with the {@link ChunkingMethod} of its
 * collection.
 * 
 * @version $Id$
 */
@Component(roles = ChunkingUtils.class)
@Singleton
public class ChunkingUtils
{
    @Inject
    private Provider<Chunk> chunkProvider;

    @Inject
    private CollectionManager collectionManager;

    @Inject
    @Named("context")
    private Provider<ComponentManager> componentManagerProvider;

    @Inject
    private Logger logger;

    @Inject
    private Provider<XWikiContext> contextProvider;
    
    /**
     * This method is responsible for splitting the document into chunks.
     * 
     * @param document
     * @return a Map of chunks
     */
    public Map<Integer, Chunk> chunkDocument(Document document) 
    {
        Collection collection;
        try {
            collection = collectionManager.getCollection(document.getCollection());
            return chunkDocument(collection, document).stream()
                .collect(Collectors.toMap(Chunk::getChunkIndex, Function.identity()));
        } catch (IndexException e) {
            logger.error("Error while chunking the document [{}]: [{}]", document, e.getMessage());
        }
        return Map.of();
    }

    /**
     * Split the document of the given collection into chunks with the chunking method of the collection.
     *
     * @param collection the collection the document is part of
     * @param document the document whose content shall be chunked
     * @return a list of chunks
     * @throws IndexException if an error occurs while chunking the document, the chunking configuration is invalid
     *     or the chunking method of the collection is not installed
     */
    public List<Chunk> chunkDocument(Collection collection, Document document)
        throws IndexException
    {
        validateChunkSizeAndOffset(collection.getChunkingMaxSize(), collection.getChunkingOverlapOffset());

        List<ChunkText> texts = getChunkingMethod(collection).chunk(collection, document);

        XWikiContext context = this.contextProvider.get();
        List<Chunk> result = new ArrayList<>(texts.size());
        for (ChunkText text : texts) {
            Chunk chunk = this.chunkProvider.get();
            chunk.initialize(document.getID(),
                            document.getCollection(),
                            document.getURL(),
                            document.getLanguage(),
                            text.posFirstChar(), text.posLastChar(), text.content());
            chunk.setChunkIndex(result.size());
            chunk.setWiki(context.getWikiId());
            chunk.setStoreHint(collection.getDocumentStoreHint());
            chunk.computeId();
            result.add(chunk);
        }

        return result;
    }

    private ChunkingMethod getChunkingMethod(Collection collection) throws IndexException
    {
        String hint = collection.getChunkingMethod();
        ComponentManager componentManager = this.componentManagerProvider.get();
        if (!componentManager.hasComponent(ChunkingMethod.class, hint)) {
            throw new IndexException("The chunking method [%s] of the collection [%s] is not installed"
                .formatted(hint, collection.getID()));
        }
        try {
            return componentManager.getInstance(ChunkingMethod.class, hint);
        } catch (ComponentLookupException e) {
            throw new IndexException("The chunking method [%s] of the collection [%s] could not be loaded"
                .formatted(hint, collection.getID()), e);
        }
    }

    private static void validateChunkSizeAndOffset(int maxChunkSize, int offset) throws IndexException
    {
        // Validate parameters:
        // - maximum chunk size needs to be at least 10 as we need to have some content in the chunk
        // - overlap offset must not be negative and must be smaller than half of the maximum chunk size
        if (maxChunkSize < 10) {
            throw new IndexException("The maximum chunk size must be at least 10 characters");
        }

        if (offset < 0) {
            throw new IndexException("The overlap offset must not be negative");
        }

        if (offset >= maxChunkSize / 2) {
            throw new IndexException("The overlap offset must be smaller than half of the maximum chunk size");
        }
    }
}
