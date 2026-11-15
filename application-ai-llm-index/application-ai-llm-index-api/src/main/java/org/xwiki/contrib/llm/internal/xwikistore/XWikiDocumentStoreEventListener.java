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
package org.xwiki.contrib.llm.internal.xwikistore;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Provider;
import javax.inject.Singleton;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.slf4j.Logger;
import org.xwiki.bridge.event.DocumentCreatedEvent;
import org.xwiki.bridge.event.DocumentDeletedEvent;
import org.xwiki.bridge.event.DocumentUpdatedEvent;
import org.xwiki.component.annotation.Component;
import org.xwiki.contrib.llm.Collection;
import org.xwiki.contrib.llm.SolrConnector;
import org.xwiki.contrib.llm.internal.DefaultCollection;
import org.xwiki.index.TaskManager;
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.model.reference.EntityReferenceSerializer;
import org.xwiki.observation.event.AbstractLocalEventListener;
import org.xwiki.observation.event.Event;

import com.xpn.xwiki.XWikiContext;
import com.xpn.xwiki.XWikiException;
import com.xpn.xwiki.doc.XWikiDocument;
import com.xpn.xwiki.objects.BaseObject;

/**
 * Event listener for detecting collection updates related to the XWiki document store.
 *
 * @version $Id$
 * @since 0.4
 */
@Component
@Named(XWikiDocumentStoreEventListener.NAME)
@Singleton
public class XWikiDocumentStoreEventListener extends AbstractLocalEventListener
{
    /**
     * The name of the event listener.
     */
    public static final String NAME = "org.xwiki.contrib.llm.internal.xwikistore.XWikiDocumentStoreEventListener";

    @Inject
    private SolrConnector solrConnector;

    @Inject
    private TaskManager taskManager;

    @Inject
    private Provider<XWikiContext> contextProvider;

    @Inject
    private Logger logger;

    @Inject
    @Named("withparameters")
    private EntityReferenceSerializer<String> parametersEntityReferenceSerializer;

    /**
     * Default constructor.
     */
    public XWikiDocumentStoreEventListener()
    {
        super(NAME, new DocumentCreatedEvent(), new DocumentUpdatedEvent(), new DocumentDeletedEvent());
    }

    @Override
    public void processLocalEvent(Event event, Object source, Object data)
    {
        XWikiDocument document = (XWikiDocument) source;
        XWikiDocument originalDocument = document.getOriginalDocument();
        String wiki = document.getDocumentReference().getWikiReference().getName();

        BaseObject updatedObject = document.getXObject(Collection.XCLASS_REFERENCE);
        BaseObject originalObject = originalDocument.getXObject(Collection.XCLASS_REFERENCE);

        boolean isXWikiStore = isXWikiCollection(updatedObject);
        boolean wasXWikiStore = isXWikiCollection(originalObject);

        // Only handle the case of a collection update, creation and deletion are handled by the generic IndexWorker.
        if (wasXWikiStore && isXWikiStore) {
            List<?> previousSpaces = originalObject.getListValue(DefaultCollection.DOCUMENT_SPACE_FIELDNAME);
            List<?> currentSpaces = updatedObject.getListValue(DefaultCollection.DOCUMENT_SPACE_FIELDNAME);

            // Compare the two lists as sets to detect true changes that aren't just reordering.
            if (!CollectionUtils.isEqualCollection(previousSpaces, currentSpaces)) {
                this.taskManager.addTask(wiki, document.getId(), XWikiDocumentCollectionIndexingTaskConsumer.NAME);
            }
        }

        // If the document has been deleted, delete it from all collections where it was indexed, matching
        // collections by store.
        if (event instanceof DocumentDeletedEvent) {
            // On deletion, the event source is a blank document built on the locale-free reference; the actually
            // deleted document, including its locale, is available as the original document. Serialize it with the
            // "withparameters" serializer to match the chunk IDs written by XWikiDocumentDocument#getID().
            String documentId = this.parametersEntityReferenceSerializer
                .serialize(originalDocument.getDocumentReferenceWithLocale());
            this.solrConnector.deleteChunksByStoreHintAndDocId(XWikiDocumentStore.NAME, documentId);
        } else {
            // Queue an indexing task. The task itself will determine if this document is actually part of a collection.
            this.taskManager.addTask(wiki, document.getId(), XWikiDocumentDocumentIndexingTaskConsumer.NAME);
        }

        // A translation that is created or deleted changes the translation locales indexed with the other language
        // versions of the page (Document#getTranslationLocales()), so they are indexed again as well.
        XWikiDocument changedVersion = event instanceof DocumentDeletedEvent ? originalDocument : document;
        if (XWikiDocumentStoreHelper.isTranslation(changedVersion) && !(event instanceof DocumentUpdatedEvent)) {
            queueOtherVersions(changedVersion, wiki);
        }
    }

    private void queueOtherVersions(XWikiDocument changedVersion, String wiki)
    {
        DocumentReference reference = changedVersion.getDocumentReference();
        XWikiContext context = this.contextProvider.get();
        try {
            XWikiDocument defaultDocument = context.getWiki().getDocument(reference, context);
            // When the whole page is deleted, its translations are deleted before its default version: nothing to
            // index again.
            if (defaultDocument.isNew()) {
                return;
            }
            List<Locale> versions = new ArrayList<>(defaultDocument.getTranslationLocales(context));
            versions.add(Locale.ROOT);
            versions.stream()
                .filter(locale -> !locale.equals(changedVersion.getLocale()))
                .forEach(locale -> this.taskManager.addTask(wiki, new XWikiDocument(reference, locale).getId(),
                    XWikiDocumentDocumentIndexingTaskConsumer.NAME));
        } catch (XWikiException e) {
            this.logger.warn("Failed to queue the other language versions of [{}] for indexing: [{}]", reference,
                ExceptionUtils.getRootCauseMessage(e));
        }
    }

    private static boolean isXWikiCollection(BaseObject baseObject)
    {
        return baseObject != null
            && XWikiDocumentStore.NAME.equals(baseObject.getStringValue(DefaultCollection.DOCUMENT_STORE_FIELDNAME));
    }
}
