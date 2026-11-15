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
import java.util.Locale;

import org.xwiki.component.annotation.Role;
import org.xwiki.contrib.llm.openai.Context;
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.stability.Unstable;

/**
 * This interface manages collections.
 * 
 * @version $Id$
 */
@Role
public interface CollectionManager
{
    /**
     * Creates a new collection.
     *
     * @param id the id of the collection
     * @return the created collection
     */
    Collection createCollection(String id) throws IndexException;
    
    /**
     * Lists all collections of the current wiki. A collection is the page the extension creates for it; a collections
     * object on any other page is not a collection.
     *
     * @return the ids of all collections
     */
    List<String> getCollections() throws IndexException;
    
    /**
     * Gets a collection by name.
     *
     * @param id the id of the collection
     * @return the collection with the given name
     */
    Collection getCollection(String id) throws IndexException;
    
    /**
     * Deletes a collection.
     * @param id the id of the collection
     * @param deleteDocuments if true, deletes all documents in the collection
     */
    void deleteCollection(String id, boolean deleteDocuments) throws IndexException;

    /**
     * @param id the id of the collection
     * @return the document reference of the collection
     */
    DocumentReference getDocumentReference(String id);

    /**
    * @param collection the collection to check access to
    * @return {@code true} if the user has access to query a collection, {@code false} otherwise.
    */
    boolean hasAccess(Collection collection);

    /**
     * Remove every chunk indexed for the current wiki. The chunks of the other wikis are kept.
     *
     * @throws IndexException when the chunks could not be removed
     */
    void clearIndexCore() throws IndexException;

    /**
     * @param solrQuery the solr query to use for the search
     * @param limit the maximum number of results to return
     * @param includeVector if true, the vector field of the document will be included in the results
     * @return the results of the search within the aillm core
     */
    List<Context> search(String solrQuery, int limit, boolean includeVector) throws IndexException;

    /**
     * @param textQuery the text query
     * @param collections the collections to search in
     * @param limit the maximum number of results to return
     * @return the context chunks matching the query
     */
    List<Context> similaritySearch(String textQuery, List<String> collections, int limit) throws IndexException;

    /**
     * Perform a hybrid semantic similarity and keyword-based search.
     *
     * @param textQuery the text query
     * @param collections the collections to search in
     * @param limitSemanticSimilarity the maximum number of results to return from a semantic similarity search
     * @param limitKeywordSearch the maximum number of results to return for the keyword search
     * @return the context chunks matching the query
     */
    default List<Context> hybridSearch(String textQuery, List<String> collections, int limitSemanticSimilarity,
        int limitKeywordSearch) throws IndexException
    {
        return hybridSearch(textQuery, collections, limitSemanticSimilarity, limitKeywordSearch, null);
    }

    /**
     * Perform a hybrid semantic similarity and keyword-based search, optionally restricted to chunks stored in a
     * given content language, as {@link #hybridSearch(String, List, HybridSearchParameters)} without minimum semantic
     * score.
     *
     * @param textQuery the text query
     * @param collections the collections to search in
     * @param limitSemanticSimilarity the maximum number of results to return from a semantic similarity search
     * @param limitKeywordSearch the maximum number of results to return for the keyword search
     * @param locale the content language the returned chunks must be indexed under (exact match on the stored
     *     language field), or {@code null} for the version of each document for the locale of the request, see
     *     {@link CollectionManager#hybridSearch(String, List, HybridSearchParameters)}
     * @return the context chunks matching the query
     * @since 0.10
     */
    @Unstable
    default List<Context> hybridSearch(String textQuery, List<String> collections, int limitSemanticSimilarity,
        int limitKeywordSearch, Locale locale) throws IndexException
    {
        return hybridSearch(textQuery, collections,
            new HybridSearchParameters(limitSemanticSimilarity, limitKeywordSearch, locale, 0));
    }

    /**
     * Perform a hybrid semantic similarity and keyword-based search. Only chunks of documents the current user may view
     * are returned, each content once. Results of the semantic search below the minimum semantic score are left out
     * before the results are merged.
     *
     * <p>Without a content language, the search returns, of each document indexed in several languages, only the
     * language version for the locale of the current request, as {@code XWikiDocument#getTranslatedDocument} chooses
     * it: the translation in that locale, otherwise in a more general locale of it ({@code de} for {@code de_AT}),
     * otherwise the default version (see {@link Document#isTranslation()} and
     * {@link Document#getTranslationLocales()}). Chunks indexed before this version of the application are returned in
     * every language until they are indexed again, and so are all versions when the request has no locale.</p>
     *
     * <p>When both searches are performed (both limits greater than 0), the results are merged by reciprocal rank
     * fusion, as the scores of the two searches are on unrelated scales: a chunk scores {@code 1 / (60 + rank)} for
     * each search that found it, and the results are ordered by that sum, which is their similarity score. When only
     * one search is performed, the results keep the order and score of that search: the cosine similarity as
     * {@code (1 + cos) / 2} for the semantic search, the BM25 score for the keyword search.</p>
     *
     * @param textQuery the text query
     * @param collections the collections to search in
     * @param parameters the limits, the content language and the minimum semantic score of the search
     * @return the context chunks matching the query
     * @throws IndexException if the search fails
     * @since 1.0.1
     */
    @Unstable
    List<Context> hybridSearch(String textQuery, List<String> collections, HybridSearchParameters parameters)
        throws IndexException;

    /**
     * @param collections the collections to filter
     * @return a list of collections that the user has access to
     */
    List<String> filterCollectionbasedOnUserAccess(List<String> collections);
    
}
