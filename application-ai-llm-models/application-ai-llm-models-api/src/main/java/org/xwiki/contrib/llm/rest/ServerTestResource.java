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
package org.xwiki.contrib.llm.rest;

import javax.ws.rs.HeaderParam;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import org.xwiki.rest.XWikiRestException;
import org.xwiki.stability.Unstable;

/**
 * REST resource for testing the connection to a saved AI server configuration.
 *
 * @version $Id$
 * @since 1.0.1
 */
@Unstable
@Path("/wikis/{wikiName}/aiLLM/v1/servers/{serverName}/test")
public interface ServerTestResource
{
    /**
     * Test the connection to an AI server with its saved URL and token by requesting its model list. Requires wiki
     * administration right and the CSRF token of the user.
     *
     * @param wikiName the wiki in which the server is configured
     * @param serverName the name of the server configuration
     * @param formToken the CSRF token of the user, sent in the {@code XWiki-Form-Token} header
     * @return a {@link ServerTestResponse}: with status 200 when the test was performed, whatever its outcome, with
     *     status 403 without admin right or valid CSRF token, with status 404 when no server has the given name
     * @throws XWikiRestException if the server configurations cannot be read
     */
    @POST
    @Produces(MediaType.APPLICATION_JSON)
    Response testServer(@PathParam("wikiName") String wikiName, @PathParam("serverName") String serverName,
        @HeaderParam("XWiki-Form-Token") String formToken) throws XWikiRestException;
}
