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
import java.util.Locale;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.apache.commons.lang3.LocaleUtils;
import org.apache.commons.lang3.StringUtils;
import org.xwiki.component.annotation.Component;
import org.xwiki.contrib.llm.ChunkText;
import org.xwiki.contrib.llm.ChunkingMethod;
import org.xwiki.contrib.llm.Collection;
import org.xwiki.contrib.llm.Document;

/**
 * Splits a document along its structure and gives each chunk the context it comes from: sections are kept together
 * as far as they fit, large sections are split between blocks and large blocks at row, item, line, sentence or word
 * boundaries, and each chunk starts with a breadcrumb made of the document's breadcrumb
 * ({@link Document#getBreadcrumb()}) and the headings above the chunk. See {@link SectionPacker} for the rules.
 * <p>
 * The positions of a chunk are offsets in the text of the document as defined by {@link DocumentSections}.
 *
 * @version $Id$
 * @since 1.0.2
 */
@Component
@Named(SectionsChunkingMethod.NAME)
@Singleton
public class SectionsChunkingMethod implements ChunkingMethod
{
    /**
     * The hint of this chunking method.
     */
    public static final String NAME = "sections";

    @Inject
    private IndexTextRenderer renderer;

    @Override
    public List<ChunkText> chunk(Collection collection, Document document)
    {
        SectionPacker packer = new SectionPacker(this.renderer, document.getBreadcrumb(),
            collection.getChunkingMaxSize(), collection.getChunkingOverlapOffset(), getLocale(document));
        return packer.pack(DocumentSections.of(document.getIndexableXDOM(), this.renderer));
    }

    /**
     * @return the language of the document, used to find the ends of sentences; the rules of the root locale, which
     *     work for most languages, when the language is unknown
     */
    private static Locale getLocale(Document document)
    {
        String language = document.getLanguage();
        try {
            return StringUtils.isBlank(language) ? Locale.ROOT : LocaleUtils.toLocale(language);
        } catch (IllegalArgumentException e) {
            return Locale.ROOT;
        }
    }
}
