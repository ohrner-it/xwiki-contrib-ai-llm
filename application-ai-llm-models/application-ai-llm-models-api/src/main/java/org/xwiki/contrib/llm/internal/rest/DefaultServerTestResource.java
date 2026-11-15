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

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Provider;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import org.xwiki.component.annotation.Component;
import org.xwiki.contrib.llm.GPTAPIConfig;
import org.xwiki.contrib.llm.GPTAPIConfigProvider;
import org.xwiki.contrib.llm.GPTAPIException;
import org.xwiki.contrib.llm.internal.ServerConnectionTester;
import org.xwiki.contrib.llm.internal.ServerTestResult;
import org.xwiki.contrib.llm.rest.ServerTestResource;
import org.xwiki.contrib.llm.rest.ServerTestResponse;
import org.xwiki.csrf.CSRFToken;
import org.xwiki.localization.ContextualLocalizationManager;
import org.xwiki.model.reference.WikiReference;
import org.xwiki.rest.XWikiResource;
import org.xwiki.rest.XWikiRestException;
import org.xwiki.security.authorization.ContextualAuthorizationManager;
import org.xwiki.security.authorization.Right;

import com.xpn.xwiki.XWikiContext;

/**
 * Default implementation of {@link ServerTestResource}.
 *
 * @version $Id$
 * @since 1.0.1
 */
@Component
@Named("org.xwiki.contrib.llm.internal.rest.DefaultServerTestResource")
public class DefaultServerTestResource extends XWikiResource implements ServerTestResource
{
    private static final String TRANSLATION_PREFIX = "llm.config.test.outcome.";

    @Inject
    private ServerConnectionTester tester;

    @Inject
    private GPTAPIConfigProvider configProvider;

    @Inject
    private ContextualAuthorizationManager authorization;

    @Inject
    private CSRFToken csrfToken;

    @Inject
    private ContextualLocalizationManager localization;

    @Inject
    private Provider<XWikiContext> contextProvider;

    @Override
    public Response testServer(String wikiName, String serverName, String formToken) throws XWikiRestException
    {
        // The translations of the messages are registered in the wiki that holds the configuration.
        XWikiContext context = this.contextProvider.get();
        String currentWiki = context.getWikiId();
        try {
            context.setWikiId(wikiName);
            return testServerInWiki(wikiName, serverName, formToken);
        } finally {
            context.setWikiId(currentWiki);
        }
    }

    private Response testServerInWiki(String wikiName, String serverName, String formToken) throws XWikiRestException
    {
        if (!this.authorization.hasAccess(Right.ADMIN, new WikiReference(wikiName))) {
            return refusal(Response.Status.FORBIDDEN, "FORBIDDEN");
        }
        // The CSRF protection of the REST API only covers POST requests with a content type that a form can send, not
        // POST requests without a body like this one, so the form token is checked here.
        if (!this.csrfToken.isTokenValid(formToken)) {
            return refusal(Response.Status.FORBIDDEN, "INVALID_FORM_TOKEN");
        }

        GPTAPIConfig config;
        try {
            config = this.configProvider.getConfigObjects(wikiName).get(serverName);
        } catch (GPTAPIException e) {
            throw new XWikiRestException(
                String.format("Failed to read the AI server configurations of wiki [%s].", wikiName), e);
        }
        if (config == null) {
            return refusal(Response.Status.NOT_FOUND, "UNKNOWN_SERVER");
        }

        ServerTestResult result = this.tester.test(config);
        String outcome = result.outcome().name();
        // Every outcome message gets both parameters, even when it uses only one of them or none.
        String message = this.localization.getTranslationPlain(TRANSLATION_PREFIX + outcome, result.modelCount(),
            result.httpStatus());
        return Response.ok(new ServerTestResponse(result.isSuccess(), outcome, message), MediaType.APPLICATION_JSON)
            .build();
    }

    private Response refusal(Response.Status status, String outcome)
    {
        String message = this.localization.getTranslationPlain(TRANSLATION_PREFIX + outcome);
        return Response.status(status).entity(new ServerTestResponse(false, outcome, message))
            .type(MediaType.APPLICATION_JSON).build();
    }
}
