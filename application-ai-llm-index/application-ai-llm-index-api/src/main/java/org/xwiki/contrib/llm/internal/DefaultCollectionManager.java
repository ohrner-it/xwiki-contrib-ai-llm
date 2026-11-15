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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.inject.Inject;
import javax.inject.Provider;
import javax.inject.Singleton;

import org.apache.commons.lang3.exception.ExceptionUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.solr.client.solrj.SolrServerException;
import org.slf4j.Logger;
import org.xwiki.component.annotation.Component;
import org.xwiki.contrib.llm.Collection;
import org.xwiki.contrib.llm.CollectionManager;
import org.xwiki.contrib.llm.HybridSearchParameters;
import org.xwiki.contrib.llm.IndexException;
import org.xwiki.contrib.llm.SolrConnector;
import org.xwiki.contrib.llm.authorization.AuthorizationManager;
import org.xwiki.contrib.llm.openai.Context;
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.model.reference.DocumentReferenceResolver;
import org.xwiki.model.reference.WikiReference;
import org.xwiki.query.Query;
import org.xwiki.query.QueryException;
import org.xwiki.query.QueryManager;
import org.xwiki.rendering.syntax.Syntax;
import org.xwiki.user.group.GroupException;
import org.xwiki.user.group.GroupManager;
import org.xwiki.user.group.WikiTarget;

import com.xpn.xwiki.XWikiContext;
import com.xpn.xwiki.XWikiException;
import com.xpn.xwiki.doc.XWikiDocument;
/**
 * Implementation of a {@code CollectionManager} component.
 *
 * @version $Id$
 */
@Component
@Singleton
public class DefaultCollectionManager implements CollectionManager
{
    /**
     * The pages of the context wiki carrying a collections object. Only the page of a collection is a collection,
     * see {@link #getCollectionId(String, WikiReference)}.
     */
    private static final String COLLECTIONS_QUERY = "select distinct doc.fullName from XWikiDocument doc, "
        + "BaseObject obj where doc.fullName = obj.name and obj.className = :className";

    @Inject
    protected Provider<XWikiContext> contextProvider;

    @Inject
    private QueryManager queryManager;

    @Inject
    private Provider<DefaultCollection> collectionProvider;

    @Inject
    private SolrConnector solrConnector;

    @Inject
    private Logger logger;

    @Inject
    private GroupManager groupManager;

    @Inject
    private DocumentReferenceResolver<String> documentReferenceResolver;

    @Override
    public DefaultCollection createCollection(String id) throws IndexException
    {
        XWikiContext context = this.contextProvider.get();
        DocumentReference documentReference = getDocumentReference(id);
        try {
            XWikiDocument xdocument = context.getWiki().getDocument(documentReference, context);
            if (!xdocument.isNew()) {
                throw new IndexException(String.format("Failed to create collection [%s], "
                                                     + "an xwiki document with the same reference [%s] already exists.",
                                                        id, documentReference));
            }

            xdocument.setDefaultLocale(context.getLocale());
            xdocument.setSyntax(Syntax.XWIKI_2_1);
            DefaultCollection newCollection = collectionProvider.get();
            newCollection.initialize(xdocument);
            newCollection.setID(id);
            newCollection.setTitle(id);
            return newCollection;
        } catch (XWikiException e) {
            throw new IndexException(String.format("Failed to create collection [%s]", id), e);
        }
    }

    @Override
    public List<String> getCollections() throws IndexException
    {
        WikiReference wikiReference = this.contextProvider.get().getWikiReference();
        try {
            Query query = this.queryManager.createQuery(COLLECTIONS_QUERY, Query.HQL);
            query.bindValue("className", Collection.XCLASS_FULLNAME);
            List<String> pages = query.execute();
            return pages.stream()
                .map(fullName -> getCollectionId(fullName, wikiReference))
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        } catch (QueryException e) {
            throw new IndexException("Failed retrieving collection list:", e);
        }
    }

    /**
     * @param fullName the full name of a page carrying a collections object, relative to the queried wiki
     * @param wikiReference the queried wiki
     * @return the id of the collection the page is the page of, which is the name of its space, see
     *     {@link DefaultCollection#getDocumentReference(String, String)}, or {@code null} when the page is not the
     *     page of a collection: a collections object on any other page, for instance on a copy of the collection
     *     page or on the template page, is not a collection
     */
    private String getCollectionId(String fullName, WikiReference wikiReference)
    {
        DocumentReference reference = this.documentReferenceResolver.resolve(fullName, wikiReference);
        String collectionId = reference.getLastSpaceReference().getName();
        if (reference.equals(DefaultCollection.getDocumentReference(wikiReference.getName(), collectionId))) {
            return collectionId;
        }
        return null;
    }
    
    @Override
    public DefaultCollection getCollection(String id) throws IndexException
    {
        XWikiContext context = contextProvider.get();
        try {
            DocumentReference documentReference = getDocumentReference(id);
            XWikiDocument xwikiDoc = context.getWiki().getDocument(documentReference, context);
            if (!xwikiDoc.isNew()) {
                DefaultCollection collection = this.collectionProvider.get();
                collection.initialize(xwikiDoc);
                return collection;
            } else {
                return null;
            }
        } catch (XWikiException e) {
            throw new IndexException(String.format("Failed to get collection with name [%s]:", id), e);
        }
    }

    @Override
    public void deleteCollection(String id, boolean deleteDocuments) throws IndexException
    {
        try {
            Collection collection = getCollection(id);
            if (deleteDocuments) {
                var documentStore = collection.getDocumentStore();
                for (String docID : documentStore.getDocumentNames(0, -1)) {
                    var document = documentStore.getDocument(docID);
                    if (document != null) {
                        documentStore.deleteDocument(document);
                    } else {
                        this.logger.warn("Skipping the deletion of document [{}] of collection [{}] as its page "
                            + "does not exist", docID, id);
                    }
                }
            }
            XWikiContext context = contextProvider.get();
            DocumentReference documentReference = getDocumentReference(id);
            XWikiDocument xdocument = context.getWiki().getDocument(documentReference, context);
            context.getWiki().deleteDocument(xdocument, context);
        } catch (Exception e) {
            throw new IndexException(String.format("Failed to delete collection [%s]", id), e);
        }
    }

    @Override
    public DocumentReference getDocumentReference(String id)
    {
        return DefaultCollection.getDocumentReference(this.contextProvider.get().getWikiId(), id);
    }

    @Override
    public void clearIndexCore() throws IndexException
    {
        String wiki = this.contextProvider.get().getWikiId();
        try {
            this.solrConnector.clearIndexCore(wiki);
        } catch (SolrServerException e) {
            throw new IndexException(String.format("Failed to clear the index of wiki [%s]", wiki), e);
        }
    }

    @Override
    public List<Context> search(String solrQuery, int limit, boolean includeVector) throws IndexException
    {
        try {
            return solrConnector.search(solrQuery, limit, includeVector);
        } catch (SolrServerException e) {
            throw new IndexException("Failed to perform simple search", e);
        }
    }

    @Override
    public List<Context> similaritySearch(String textQuery,
                                                List<String> collections,
                                                int limit) throws IndexException
    {
        return hybridSearch(textQuery, collections, limit, 0);
    }

    @Override
    public List<Context> hybridSearch(String textQuery, List<String> collections, HybridSearchParameters parameters)
        throws IndexException
    {
        Map<String, DefaultCollection> collectionMap = getAccessibleCollections(collections);

        Map<String, AuthorizationManager> authorizationManagerMap = getAuthorizationManagerMap(collectionMap);

        // Get the embedding model for each collection for which an authorization manager was found
        Map<String, String> collectionEmbeddingModelMap = collectionMap.entrySet().stream()
            .filter(entry -> authorizationManagerMap.containsKey(entry.getKey()))
            .collect(Collectors.toMap(Map.Entry::getKey, entry -> entry.getValue().getEmbeddingModel()));

        if (collectionEmbeddingModelMap.isEmpty()) {
            return List.of();
        }

        // The rights check of a collection depends on its document store: only the chunks that have been indexed from
        // the current document store of each collection are searched.
        Map<String, String> collectionStoreHintMap = collectionMap.entrySet().stream()
            .filter(entry -> authorizationManagerMap.containsKey(entry.getKey()))
            .collect(Collectors.toMap(Map.Entry::getKey, entry -> entry.getValue().getDocumentStoreHint()));

        try {
            List<Context> semanticResults = this.solrConnector.similaritySearch(textQuery, collectionEmbeddingModelMap,
                collectionStoreHintMap, parameters.limitSemanticSimilarity(), parameters.locale());
            List<Context> keywordResults = this.solrConnector.keywordSearch(textQuery,
                collectionStoreHintMap, parameters.limitKeywordSearch(), parameters.locale());

            // Authorize before merging, so that a duplicate in a document the user cannot view cannot hide the
            // viewable one.
            Map<String, Map<String, Boolean>> viewableDocuments = getViewableDocuments(
                Stream.concat(semanticResults.stream(), keywordResults.stream()).toList(), authorizationManagerMap);
            // The minimum applies before the fusion: a chunk below it can still come from the keyword search.
            List<Context> viewableSemanticResults = filterViewable(semanticResults, viewableDocuments).stream()
                .filter(context -> context.similarityScore() >= parameters.minSemanticScore())
                .toList();
            List<Context> viewableKeywordResults = filterViewable(keywordResults, viewableDocuments);

            if (parameters.limitSemanticSimilarity() > 0 && parameters.limitKeywordSearch() > 0) {
                return ReciprocalRankFusion.fuse(viewableSemanticResults, viewableKeywordResults);
            }
            Set<String> uniqueContent = new HashSet<>();
            return Stream.concat(viewableSemanticResults.stream(), viewableKeywordResults.stream())
                .filter(context -> uniqueContent.add(context.content()))
                .toList();
        } catch (SolrServerException e) {
            throw new IndexException("Failed to perform similarity search", e);
        }
    }

    private Map<String, AuthorizationManager> getAuthorizationManagerMap(Map<String, DefaultCollection> collectionMap)
    {
        return collectionMap.entrySet().stream()
            .flatMap(entry -> {
                try {
                    return Stream.of(Map.entry(entry.getKey(), entry.getValue().getAuthorizationManager()));
                } catch (IndexException | RuntimeException e) {
                    this.logger.warn("Failed to get authorization manager for collection [{}], excluding it from the"
                        + " similarity search: [{}]", entry.getKey(), ExceptionUtils.getRootCauseMessage(e));
                    return Stream.empty();
                }
            })
            .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    private Map<String, DefaultCollection> getAccessibleCollections(List<String> collections)
    {
        // The caller may name a collection several times: each collection is looked up and searched once.
        return collections.stream()
            .distinct()
            .flatMap(name -> {
                try {
                    DefaultCollection collection = getCollection(name);
                    return collection == null ? Stream.empty() : Stream.of(Map.entry(name, collection));
                } catch (IndexException e) {
                    this.logger.warn("Failed to get collection [{}], excluding it from the similarity search: [{}]",
                        forLog(name), ExceptionUtils.getRootCauseMessage(e));
                    return Stream.empty();
                }
            })
            .filter(entry -> hasAccess(entry.getValue()))
            .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    /**
     * Check which documents of the results the user can view, with one check per collection: an authorization check
     * could, e.g., call an external service where the overhead per call is high.
     *
     * @return for each collection of the results that has an authorization manager, whether each of its documents is
     *     viewable
     */
    private Map<String, Map<String, Boolean>> getViewableDocuments(List<Context> results,
        Map<String, AuthorizationManager> authorizationManagerForCollection)
    {
        Map<String, Map<String, Boolean>> viewableDocuments = new HashMap<>();
        results.stream()
            .collect(Collectors.groupingBy(Context::collectionId,
                Collectors.mapping(Context::documentId, Collectors.toSet())))
            .forEach((collectionName, documentIds) -> {
                AuthorizationManager authorizationManager = authorizationManagerForCollection.get(collectionName);
                if (authorizationManager == null) {
                    this.logger.warn("Authorization manager for collection [{}] not found, skipping", collectionName);
                } else {
                    viewableDocuments.put(collectionName, authorizationManager.canView(documentIds));
                }
            });
        return viewableDocuments;
    }

    private static List<Context> filterViewable(List<Context> results,
        Map<String, Map<String, Boolean>> viewableDocuments)
    {
        return results.stream()
            .filter(context -> viewableDocuments.getOrDefault(context.collectionId(), Map.of())
                .getOrDefault(context.documentId(), false))
            .toList();
    }

    /**
     * @param requestedName a collection name taken from a request
     * @return the name without line breaks, so that it cannot forge a log line
     */
    private static String forLog(String requestedName)
    {
        return StringUtils.replaceChars(requestedName, "\r\n", "  ");
    }

    @Override
    public boolean hasAccess(Collection collection)
    {
        if (collection.getAllowGuests()) {
            return true;
        }
        java.util.Collection<DocumentReference> userGroups = fetchCrtUserGroups();
        if (userGroups.isEmpty()) {
            return false;
        }
    
        java.util.Collection<DocumentReference> allowedGroupReferences = 
                convertAllowedGroupsToReferences(collection.getQueryGroups());
    
        return allowedGroupReferences.stream().anyMatch(userGroups::contains);
    }
    

    private java.util.Collection<DocumentReference> fetchCrtUserGroups()
    {
        DocumentReference documentUserReference = contextProvider.get().getUserReference();
        java.util.Collection<DocumentReference> userGroups = new ArrayList<>();
        try {
            userGroups = groupManager.getGroups(documentUserReference, WikiTarget.ENTITY_AND_CURRENT, true);
        } catch (GroupException e) {
            this.logger.warn("Failed to get groups for user [{}]: [{}]", documentUserReference,
                ExceptionUtils.getRootCauseMessage(e));
        }
        return userGroups;
    }
    
    private java.util.Collection<DocumentReference> convertAllowedGroupsToReferences(String stringGroups)
    {
        return Stream.of(StringUtils.split(stringGroups, ','))
            .map(String::trim)
            .map(this.documentReferenceResolver::resolve)
            .collect(Collectors.toList());
    }

    @Override
    public List<String> filterCollectionbasedOnUserAccess(List<String> collections)
    {
        List<String> collectionsUserHasAccessTo = new ArrayList<>();
        for (String collection : collections) {
            try {
                if (this.hasAccess(this.getCollection(collection))) {
                    collectionsUserHasAccessTo.add(collection);
                }
            } catch (IndexException e) {
                this.logger.warn("Failed to check access to collection [{}], excluding it: [{}]", forLog(collection),
                    ExceptionUtils.getRootCauseMessage(e));
            }
        }
        return collectionsUserHasAccessTo;
    }

}
