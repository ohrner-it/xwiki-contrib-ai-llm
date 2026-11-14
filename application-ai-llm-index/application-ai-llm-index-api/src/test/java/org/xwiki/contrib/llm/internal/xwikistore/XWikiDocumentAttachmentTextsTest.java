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

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import javax.inject.Provider;

import org.apache.commons.io.IOUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.mockito.Mock;
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.test.LogLevel;
import org.xwiki.test.junit5.LogCaptureExtension;
import org.xwiki.test.junit5.mockito.ComponentTest;
import org.xwiki.test.junit5.mockito.InjectMockComponents;
import org.xwiki.test.junit5.mockito.MockComponent;

import com.xpn.xwiki.XWikiContext;
import com.xpn.xwiki.XWikiException;
import com.xpn.xwiki.doc.XWikiAttachment;
import com.xpn.xwiki.doc.XWikiDocument;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit test for {@link XWikiDocumentAttachmentTexts}, with the real text extraction.
 *
 * @version $Id$
 */
@ComponentTest
class XWikiDocumentAttachmentTextsTest
{
    @RegisterExtension
    private LogCaptureExtension logCapture = new LogCaptureExtension(LogLevel.WARN);

    @InjectMockComponents
    private XWikiDocumentAttachmentTexts attachmentTexts;

    @MockComponent
    private Provider<XWikiContext> contextProvider;

    @Mock
    private XWikiContext context;

    @BeforeEach
    void setUp()
    {
        when(this.contextProvider.get()).thenReturn(this.context);
    }

    @Test
    void textsOfTheAttachmentsInTheOrderOfThePage() throws Exception
    {
        XWikiDocument document = document(attachment("b.txt", "Text B"), attachment("a.txt", "Text A"));

        Map<String, String> texts = this.attachmentTexts.get(document);

        assertEquals(List.of("b.txt", "a.txt"), List.copyOf(texts.keySet()));
        assertEquals("Text B", texts.get("b.txt").strip());
        assertEquals("Text A", texts.get("a.txt").strip());
    }

    @Test
    void attachmentWithoutTextIsLeftOut() throws Exception
    {
        XWikiDocument document = document(attachment("blank.txt", "  \n "), attachment("a.txt", "Text A"));

        assertEquals(List.of("a.txt"), List.copyOf(this.attachmentTexts.get(document).keySet()));
    }

    @Test
    void attachmentWhoseTextCannotBeExtractedIsLoggedAndLeftOut() throws Exception
    {
        XWikiAttachment broken = mock();
        when(broken.getFilename()).thenReturn("broken.bin");
        when(broken.getContentInputStream(this.context)).thenThrow(new XWikiException());
        XWikiDocument document = document(broken, attachment("a.txt", "Text A"));

        assertEquals(List.of("a.txt"), List.copyOf(this.attachmentTexts.get(document).keySet()));
        assertEquals("Failed to extract the text of the attachment [broken.bin] of document [wiki:Space.Page]: "
            + "[XWikiException: Error number 0 in 0]", this.logCapture.getMessage(0));
    }

    private XWikiAttachment attachment(String fileName, String text) throws Exception
    {
        XWikiAttachment attachment = mock();
        when(attachment.getFilename()).thenReturn(fileName);
        when(attachment.getContentInputStream(this.context))
            .thenReturn(IOUtils.toInputStream(text, StandardCharsets.UTF_8));
        return attachment;
    }

    private static XWikiDocument document(XWikiAttachment... attachments)
    {
        XWikiDocument document = mock();
        when(document.getDocumentReference()).thenReturn(new DocumentReference("wiki", "Space", "Page"));
        when(document.getAttachmentList()).thenReturn(List.of(attachments));
        return document;
    }
}
