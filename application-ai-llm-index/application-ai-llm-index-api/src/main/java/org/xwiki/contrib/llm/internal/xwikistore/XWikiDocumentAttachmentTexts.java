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

import java.util.LinkedHashMap;
import java.util.Map;

import javax.inject.Inject;
import javax.inject.Provider;
import javax.inject.Singleton;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.apache.tika.Tika;
import org.slf4j.Logger;
import org.xwiki.component.annotation.Component;

import com.xpn.xwiki.XWikiContext;
import com.xpn.xwiki.doc.XWikiAttachment;
import com.xpn.xwiki.doc.XWikiDocument;

/**
 * Extracts the text of the attachments of a wiki page for indexing.
 *
 * @version $Id$
 * @since 1.0.1
 */
@Component(roles = XWikiDocumentAttachmentTexts.class)
@Singleton
public class XWikiDocumentAttachmentTexts
{
    @Inject
    private Provider<XWikiContext> contextProvider;

    @Inject
    private Logger logger;

    private final Tika tika = new Tika();

    /**
     * Extract the text of the attachments of a page. An attachment whose text cannot be extracted is logged and left
     * out.
     *
     * @param document the page
     * @return the text of the attachments of the page that have text, by file name, in the order of the page
     */
    public Map<String, String> get(XWikiDocument document)
    {
        XWikiContext context = this.contextProvider.get();
        Map<String, String> result = new LinkedHashMap<>();
        for (XWikiAttachment attachment : document.getAttachmentList()) {
            try {
                String content = this.tika.parseToString(attachment.getContentInputStream(context));
                if (StringUtils.isNotBlank(content)) {
                    result.put(attachment.getFilename(), content);
                }
            } catch (Exception e) {
                this.logger.warn("Failed to extract the text of the attachment [{}] of document [{}]: [{}]",
                    attachment.getFilename(), document.getDocumentReference(), ExceptionUtils.getRootCauseMessage(e));
            }
        }
        return result;
    }
}
