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

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Provider;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.xwiki.component.annotation.Component;
import org.xwiki.component.annotation.InstantiationStrategy;
import org.xwiki.component.descriptor.ComponentInstantiationStrategy;
import org.xwiki.contrib.llm.Chunk;
import org.xwiki.contrib.llm.Document;
import org.xwiki.contrib.llm.IndexException;
import org.xwiki.contrib.llm.internal.xwikistore.XWikiDocumentIndexableXDOMBuilder.ObjectSection;
import org.xwiki.contrib.llm.internal.xwikistore.XWikiDocumentIndexableXDOMBuilder.PropertyValue;
import org.xwiki.localization.ContextualLocalizationManager;
import org.xwiki.mail.GeneralMailConfiguration;
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.model.reference.EntityReferenceSerializer;
import org.xwiki.rendering.block.XDOM;
import org.xwiki.rendering.syntax.Syntax;
import org.xwiki.rendering.syntax.SyntaxType;

import com.xpn.xwiki.XWikiContext;
import com.xpn.xwiki.doc.XWikiDocument;
import com.xpn.xwiki.objects.BaseObject;
import com.xpn.xwiki.objects.classes.BaseClass;
import com.xpn.xwiki.objects.classes.EmailClass;
import com.xpn.xwiki.objects.classes.PasswordClass;
import com.xpn.xwiki.objects.classes.PropertyClass;

/**
 * A {@link Document} that exposes an arbitrary {@link XWikiDocument} for indexing in the LLM application.
 *
 * <p>The indexed text is the rendered title, the content, the objects and the attachments of the page. For the
 * objects, only what is both content and safe to disclose to every reader of the page is indexed:</p>
 * <ul>
 * <li>objects of the classes {@code XWiki.XWikiRights}, {@code XWiki.XWikiGlobalRights} and
 * {@code XWiki.XWikiPreferences} are never indexed, they hold rights and configuration, not content;</li>
 * <li>an object whose class cannot be loaded is never indexed, as its property types are unknown;</li>
 * <li>only properties that the class defines are indexed, a value stored on the object without a matching class
 * property is skipped;</li>
 * <li>password properties ({@link PasswordClass} or the {@code Password} class type) are never indexed;</li>
 * <li>email properties ({@link EmailClass} or the {@code Email} class type) are not indexed when the wiki obfuscates
 * email addresses ({@link GeneralMailConfiguration#shouldObfuscate()});</li>
 * <li>an object heading is only emitted when at least one of its properties is indexed, so an object that
 * contributes no text leaves no trace in the index.</li>
 * </ul>
 * <p>The password and email rules are the ones the platform's Solr metadata extractor applies to object properties.
 * The other rules are stricter: anything whose type is unknown is left out.</p>
 *
 * @version $Id$
 * @since 0.4
 */
@Component(roles = XWikiDocumentDocument.class)
@InstantiationStrategy(ComponentInstantiationStrategy.PER_LOOKUP)
public class XWikiDocumentDocument implements Document
{
    /**
     * The classes whose objects are never indexed, as local (wiki-less) references: they hold rights and
     * configuration, not content.
     */
    private static final Set<String> EXCLUDED_CLASSES =
        Set.of("XWiki.XWikiRights", "XWiki.XWikiGlobalRights", "XWiki.XWikiPreferences");

    private static final String PASSWORD_CLASS_TYPE = "Password";

    private static final String EMAIL_CLASS_TYPE = "Email";

    private static final String CHANGING_DOCUMENTS_MESSAGE =
        "This XWiki document store doesn't support changing documents.";

    private static final String DELIMITER = "\n\n";

    private static final String VIEW_ACTION = "view";

    private String collection;
    private XWikiDocument xWikiDocument;

    @Inject
    private Provider<XWikiContext> xWikiContextProvider;

    @Inject
    @Named("withparameters")
    private EntityReferenceSerializer<String> parametersEntityReferenceSerializer;

    @Inject
    @Named("compactwiki")
    private EntityReferenceSerializer<String> compactEntityReferenceSerializer;

    @Inject
    @Named("local")
    private EntityReferenceSerializer<String> localEntityReferenceSerializer;

    @Inject
    private GeneralMailConfiguration generalMailConfiguration;

    @Inject
    private Logger logger;

    @Inject
    private ContextualLocalizationManager localizationManager;

    @Inject
    private XWikiDocumentIndexableXDOMBuilder xdomBuilder;

    @Inject
    private XWikiDocumentAttachmentTexts attachmentTexts;

    @Inject
    private XWikiDocumentStoreHelper storeHelper;

    /**
     * Initialize this document. This method may be called once.
     *
     * @param collection the collection to which the document belongs
     * @param document the document that shall be wrapped
     * @throws IllegalStateException if the document is already initialized
     */
    public void initialize(String collection, XWikiDocument document)
    {
        if (this.xWikiDocument != null) {
            throw new IllegalStateException("This document is already initialized.");
        }

        this.collection = collection;
        this.xWikiDocument = document;
    }

    @Override
    public XWikiDocument getXWikiDocument() throws IndexException
    {
        throw new UnsupportedOperationException("The internal document is protected");
    }

    @Override
    public String getID()
    {
        return this.parametersEntityReferenceSerializer.serialize(this.xWikiDocument.getDocumentReferenceWithLocale());
    }

    @Override
    public String getTitle()
    {
        return this.xWikiDocument.getRenderedTitle(Syntax.PLAIN_1_0, this.xWikiContextProvider.get());
    }

    /**
     * {@inheritDoc}
     *
     * <p>See {@link XWikiDocumentStoreHelper#getBreadcrumb(DocumentReference, String)}.</p>
     */
    @Override
    public List<String> getBreadcrumb()
    {
        return this.storeHelper.getBreadcrumb(this.xWikiDocument.getDocumentReference(), getTitle());
    }

    @Override
    public String getLanguage()
    {
        return getRealLocale().toString();
    }

    /**
     * {@inheritDoc}
     *
     * <p>For a translation row the returned view URL pins the language via a {@code language=} query parameter,
     * URL-encoded because a locale's variant segment can carry URL metacharacters. Default rows keep the bare
     * view URL, which follows the wiki's default language even when that default changes. The URL is baked into
     * the chunks at indexing time, so already-indexed chunks keep the URL they were indexed with until their
     * collection is reindexed.</p>
     */
    @Override
    public String getURL()
    {
        Locale locale = this.xWikiDocument.getLocale();
        if (locale != null && !Locale.ROOT.equals(locale)) {
            return this.xWikiDocument.getExternalURL(VIEW_ACTION,
                "language=" + URLEncoder.encode(locale.toString(), StandardCharsets.UTF_8),
                this.xWikiContextProvider.get());
        }
        return this.xWikiDocument.getExternalURL(VIEW_ACTION, this.xWikiContextProvider.get());
    }

    @Override
    public String getCollection()
    {
        return this.collection;
    }

    @Override
    public String getMimetype()
    {
        return "text/" + this.xWikiDocument.getSyntax().getType().getId();
    }

    @Override
    public String getContent()
    {
        // FIXME: refactor this to return an input stream that iteratively returns all of these values instead of a
        //  string.
        XWikiContext context = this.xWikiContextProvider.get();
        return Stream.of(
            formatHeading(1, this.xWikiDocument.getRenderedTitle(this.xWikiDocument.getSyntax(), context))
                + this.xWikiDocument.getContent(),
                getFormattedXObjects(context),
                getFormattedAttachments()
            )
            .filter(StringUtils::isNotBlank)
            .collect(Collectors.joining(DELIMITER));
    }

    /**
     * {@inheritDoc}
     *
     * <p>The indexed body of a page is built by {@link XWikiDocumentIndexableXDOMBuilder} from its content, the objects
     * and properties selected as for {@link #getContent()}, and its attachments. It is computed in the language of the
     * page, and with the wiki of the page and the page as context, so that the macros of the wiki of the page are
     * found and links get their labels from the page.</p>
     */
    @Override
    public XDOM getIndexableXDOM()
    {
        XWikiContext context = this.xWikiContextProvider.get();
        String previousWiki = context.getWikiId();
        XWikiDocument previousDocument = context.getDoc();
        Locale previousLocale = context.getLocale();
        try {
            context.setLocale(getRealLocale());
            // The headings of objects are translations of this application, which may be installed only on the wiki
            // of the collection, the context wiki: the objects are selected before switching to the wiki of the page.
            List<IndexableObject> objects = getIndexableObjects(context);

            context.setWikiId(this.xWikiDocument.getDocumentReference().getWikiReference().getName());
            context.setDoc(this.xWikiDocument);
            List<ObjectSection> objectSections =
                objects.stream().map(object -> getObjectSection(object, context)).toList();
            return this.xdomBuilder.build(this.xWikiDocument, objectSections,
                this.attachmentTexts.get(this.xWikiDocument));
        } finally {
            context.setWikiId(previousWiki);
            context.setDoc(previousDocument);
            context.setLocale(previousLocale);
        }
    }

    private Locale getRealLocale()
    {
        Locale locale = this.xWikiDocument.getLocale();
        return locale != null && !Locale.ROOT.equals(locale) ? locale : this.xWikiDocument.getDefaultLocale();
    }

    private static ObjectSection getObjectSection(IndexableObject indexableObject, XWikiContext context)
    {
        BaseObject xObject = indexableObject.object();
        List<PropertyValue> properties = indexableObject.properties().stream()
            .map(xProperty -> new PropertyValue(xProperty.getTranslatedPrettyName(context),
                xObject.getStringValue(xProperty.getName()), xProperty))
            .toList();
        return new ObjectSection(indexableObject.heading(), properties);
    }

    private String getFormattedAttachments()
    {
        return this.attachmentTexts.get(this.xWikiDocument).entrySet().stream()
            .map(attachment -> formatHeading(2, attachment.getKey()) + attachment.getValue())
            .collect(Collectors.joining(DELIMITER));
    }

    /**
     * An object of the page and its properties whose values are indexed.
     *
     * @param object the object
     * @param heading the heading of the object
     * @param properties the properties of the class of the object that may be indexed and whose value is not blank;
     *     never empty
     */
    private record IndexableObject(BaseObject object, String heading, List<PropertyClass> properties)
    {
    }

    /**
     * @return the objects of the page whose properties may be indexed, see the class description, in the order of
     *     the page; objects without any such property are left out
     */
    private List<IndexableObject> getIndexableObjects(XWikiContext context)
    {
        // The platform's mail obfuscation setting is read once per document, not once per property.
        boolean obfuscateEmails = this.generalMailConfiguration.shouldObfuscate();
        return this.xWikiDocument.getXObjects().values().stream().flatMap(List::stream)
            .filter(Objects::nonNull)
            .filter(xObject -> !isExcludedClass(xObject))
            .flatMap(xObject -> getIndexableObject(xObject, context, obfuscateEmails))
            .toList();
    }

    private String getFormattedXObjects(XWikiContext context)
    {
        return getIndexableObjects(context).stream()
            .map(indexableObject -> formatXObject(indexableObject, context))
            .collect(Collectors.joining(DELIMITER));
    }

    /**
     * @param xObject the object to check
     * @return {@code true} when the object belongs to one of {@link #EXCLUDED_CLASSES} or has no class reference at
     *     all, {@code false} when its properties may be indexed
     */
    private boolean isExcludedClass(BaseObject xObject)
    {
        String className = this.localEntityReferenceSerializer.serialize(xObject.getXClassReference());
        return className == null || EXCLUDED_CLASSES.contains(className);
    }

    /**
     * Select the properties of a single object that are indexed: only properties defined by the object's class are
     * considered, and only those whose type may be indexed and whose value is not blank.
     *
     * @return the object with its indexed properties as a single-element stream, or an empty stream when nothing of
     *     the object is indexed
     */
    private Stream<IndexableObject> getIndexableObject(BaseObject xObject, XWikiContext context,
        boolean obfuscateEmails)
    {
        BaseClass xClass = xObject.getXClass(context);
        if (xClass == null) {
            this.logger.debug("Skipping an object of class [{}] in document [{}] as its class cannot be loaded.",
                xObject.getXClassReference(), this.xWikiDocument.getDocumentReference());
            return Stream.empty();
        }

        Collection<?> fieldList = xClass.getFieldList();
        List<PropertyClass> properties = fieldList.stream()
            .filter(PropertyClass.class::isInstance)
            .map(PropertyClass.class::cast)
            .filter(xProperty -> isIndexableProperty(xProperty, obfuscateEmails))
            .filter(xProperty -> xObject.safeget(xProperty.getName()) != null)
            .filter(xProperty -> StringUtils.isNotBlank(xObject.getStringValue(xProperty.getName())))
            .toList();

        return properties.isEmpty() ? Stream.empty()
            : Stream.of(new IndexableObject(xObject, getObjectHeading(xObject), properties));
    }

    private String getObjectHeading(BaseObject xObject)
    {
        return this.localizationManager.getTranslationPlain("aiLLM.index.objectHeading",
            this.compactEntityReferenceSerializer.serialize(xObject.getXClassReference(),
                xObject.getDocumentReference()));
    }

    private String formatXObject(IndexableObject indexableObject, XWikiContext context)
    {
        BaseObject xObject = indexableObject.object();
        String objectProperties = indexableObject.properties().stream()
            .map(xProperty -> formatHeading(2, xProperty.getTranslatedPrettyName(context))
                + xObject.getStringValue(xProperty.getName()))
            .collect(Collectors.joining(DELIMITER));
        return formatHeading(1, indexableObject.heading()) + objectProperties;
    }

    /**
     * @param propertyClass the class of the property
     * @param obfuscateEmails whether the wiki obfuscates email addresses
     * @return {@code false} for password properties and, when email addresses are obfuscated, for email properties;
     *     {@code true} otherwise
     */
    private static boolean isIndexableProperty(PropertyClass propertyClass, boolean obfuscateEmails)
    {
        String classType = propertyClass.getClassType();
        boolean isPassword = propertyClass instanceof PasswordClass || PASSWORD_CLASS_TYPE.equals(classType);
        boolean isEmail = propertyClass instanceof EmailClass || EMAIL_CLASS_TYPE.equals(classType);
        return !isPassword && !(isEmail && obfuscateEmails);
    }

    private String formatHeading(int level, String content)
    {
        // This currently only supports XWiki and Markdown.
        if (this.xWikiDocument.getSyntax().getType().equals(SyntaxType.XWIKI)) {
            String delimiter = StringUtils.repeat('=', level);
            return "%s %s %s\n\n".formatted(delimiter, content, delimiter);
        } else {
            String start = StringUtils.repeat('#', level);
            return start + " " + content + DELIMITER;
        }
    }

    @Override
    public void setID(String id) throws IndexException
    {
        throw new UnsupportedOperationException(CHANGING_DOCUMENTS_MESSAGE);
    }

    @Override
    public void setTitle(String title) throws IndexException
    {
        throw new UnsupportedOperationException(CHANGING_DOCUMENTS_MESSAGE);
    }

    @Override
    public void setLanguage(String lang) throws IndexException
    {
        throw new UnsupportedOperationException(CHANGING_DOCUMENTS_MESSAGE);
    }

    @Override
    public void setURL(String url) throws IndexException
    {
        throw new UnsupportedOperationException(CHANGING_DOCUMENTS_MESSAGE);
    }

    @Override
    public void setCollection(String collection) throws IndexException
    {
        throw new UnsupportedOperationException(CHANGING_DOCUMENTS_MESSAGE);
    }

    @Override
    public void setMimetype(String mimetype) throws IndexException
    {
        throw new UnsupportedOperationException(CHANGING_DOCUMENTS_MESSAGE);
    }

    @Override
    public void setContent(String content) throws IndexException
    {
        throw new UnsupportedOperationException(CHANGING_DOCUMENTS_MESSAGE);
    }

    @Override
    public List<Chunk> chunkDocument()
    {
        throw new UnsupportedOperationException("Use ChunkingUtils to chunk documents");
    }
}
