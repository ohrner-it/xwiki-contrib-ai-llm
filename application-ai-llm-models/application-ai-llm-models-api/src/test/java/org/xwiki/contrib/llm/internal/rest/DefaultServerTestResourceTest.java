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
package org.xwiki.contrib.llm.internal.rest;

import java.util.Map;

import javax.inject.Provider;
import javax.ws.rs.core.Response;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.xwiki.contrib.llm.GPTAPIConfig;
import org.xwiki.contrib.llm.GPTAPIConfigProvider;
import org.xwiki.contrib.llm.GPTAPIException;
import org.xwiki.contrib.llm.internal.ServerConnectionTester;
import org.xwiki.contrib.llm.internal.ServerTestResult;
import org.xwiki.contrib.llm.internal.ServerTestResult.Outcome;
import org.xwiki.contrib.llm.rest.ServerTestResponse;
import org.xwiki.csrf.CSRFToken;
import org.xwiki.localization.ContextualLocalizationManager;
import org.xwiki.model.reference.WikiReference;
import org.xwiki.rest.XWikiRestException;
import org.xwiki.security.authorization.ContextualAuthorizationManager;
import org.xwiki.security.authorization.Right;
import org.xwiki.test.annotation.BeforeComponent;
import org.xwiki.test.junit5.mockito.ComponentTest;
import org.xwiki.test.junit5.mockito.InjectMockComponents;
import org.xwiki.test.junit5.mockito.MockComponent;

import com.xpn.xwiki.XWikiContext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link DefaultServerTestResource}.
 *
 * @version $Id$
 */
@ComponentTest
class DefaultServerTestResourceTest
{
    private static final String WIKI = "subwiki";

    private static final String SERVER = "My Server";

    private static final String FORM_TOKEN = "form-token";

    private static final String MAIN_WIKI = "xwiki";

    @InjectMockComponents
    private DefaultServerTestResource resource;

    @MockComponent
    private ServerConnectionTester tester;

    @MockComponent
    private GPTAPIConfigProvider configProvider;

    @MockComponent
    private ContextualAuthorizationManager authorization;

    @MockComponent
    private CSRFToken csrfToken;

    @MockComponent
    private ContextualLocalizationManager localization;

    @MockComponent
    private Provider<XWikiContext> contextProvider;

    @Mock
    private GPTAPIConfig config;

    private final XWikiContext context = new XWikiContext();

    @BeforeComponent
    void beforeComponent()
    {
        // The resource reads the context when it is initialized.
        this.context.setWikiId(MAIN_WIKI);
        when(this.contextProvider.get()).thenReturn(this.context);
    }

    @BeforeEach
    void setUp() throws Exception
    {
        when(this.authorization.hasAccess(Right.ADMIN, new WikiReference(WIKI))).thenReturn(true);
        when(this.csrfToken.isTokenValid(FORM_TOKEN)).thenReturn(true);
        when(this.configProvider.getConfigObjects(WIKI)).thenReturn(Map.of(SERVER, this.config));
        when(this.localization.getTranslationPlain(any(), any(), any()))
            .then(invocation -> String.format("%s [%s, %s]", invocation.getArgument(0), invocation.getArgument(1),
                invocation.getArgument(2)));
    }

    @Test
    void successfulTest() throws Exception
    {
        when(this.tester.test(this.config)).thenReturn(new ServerTestResult(Outcome.SUCCESS, 200, 12));

        Response response = this.resource.testServer(WIKI, SERVER, FORM_TOKEN);

        assertEquals(200, response.getStatus());
        assertEquals(new ServerTestResponse(true, "SUCCESS", "llm.config.test.outcome.SUCCESS [12, 200]"),
            response.getEntity());
    }

    @Test
    void translatesInTheRequestedWiki() throws Exception
    {
        when(this.tester.test(this.config)).thenReturn(new ServerTestResult(Outcome.SUCCESS, 200, 1));
        // The translations of the application are registered in the wiki that holds the configuration.
        when(this.localization.getTranslationPlain(any(), any(), any()))
            .then(invocation -> "wiki " + this.context.getWikiId());

        Response response = this.resource.testServer(WIKI, SERVER, FORM_TOKEN);

        assertEquals("wiki " + WIKI, ((ServerTestResponse) response.getEntity()).message());
        assertEquals(MAIN_WIKI, this.context.getWikiId());
    }

    @Test
    void failedTest() throws Exception
    {
        when(this.tester.test(this.config)).thenReturn(new ServerTestResult(Outcome.CREDENTIALS_REJECTED, 401, null));

        Response response = this.resource.testServer(WIKI, SERVER, FORM_TOKEN);

        // The test request itself succeeded, the result tells that the server configuration did not.
        assertEquals(200, response.getStatus());
        assertEquals(new ServerTestResponse(false, "CREDENTIALS_REJECTED",
            "llm.config.test.outcome.CREDENTIALS_REJECTED [null, 401]"), response.getEntity());
    }

    @Test
    void requiresWikiAdminRight() throws Exception
    {
        when(this.authorization.hasAccess(Right.ADMIN, new WikiReference(WIKI))).thenReturn(false);
        when(this.localization.getTranslationPlain("llm.config.test.outcome.FORBIDDEN")).thenReturn("forbidden");

        Response response = this.resource.testServer(WIKI, SERVER, FORM_TOKEN);

        assertEquals(403, response.getStatus());
        assertEquals(new ServerTestResponse(false, "FORBIDDEN", "forbidden"), response.getEntity());
        verifyNoInteractions(this.tester, this.configProvider);
    }

    @Test
    void requiresValidFormToken() throws Exception
    {
        when(this.localization.getTranslationPlain("llm.config.test.outcome.INVALID_FORM_TOKEN"))
            .thenReturn("invalid token");

        Response response = this.resource.testServer(WIKI, SERVER, "forged");

        assertEquals(403, response.getStatus());
        assertEquals(new ServerTestResponse(false, "INVALID_FORM_TOKEN", "invalid token"), response.getEntity());
        verifyNoInteractions(this.tester, this.configProvider);
    }

    @Test
    void unknownServer() throws Exception
    {
        when(this.localization.getTranslationPlain("llm.config.test.outcome.UNKNOWN_SERVER")).thenReturn("unknown");

        Response response = this.resource.testServer(WIKI, "Unsaved Server", FORM_TOKEN);

        assertEquals(404, response.getStatus());
        assertEquals(new ServerTestResponse(false, "UNKNOWN_SERVER", "unknown"), response.getEntity());
        verifyNoInteractions(this.tester);
    }

    @Test
    void unreadableConfiguration() throws Exception
    {
        when(this.configProvider.getConfigObjects(WIKI)).thenThrow(new GPTAPIException("failed"));

        XWikiRestException exception =
            assertThrows(XWikiRestException.class, () -> this.resource.testServer(WIKI, SERVER, FORM_TOKEN));

        assertEquals("Failed to read the AI server configurations of wiki [subwiki].", exception.getMessage());
        verifyNoInteractions(this.tester);
    }
}
