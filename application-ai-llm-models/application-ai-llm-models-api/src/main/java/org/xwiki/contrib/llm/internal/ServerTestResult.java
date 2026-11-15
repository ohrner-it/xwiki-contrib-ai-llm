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

/**
 * The result of testing the connection to an AI server.
 *
 * @param outcome what the test found out
 * @param httpStatus the HTTP status of the answer of the server, {@code null} when it did not answer
 * @param modelCount the number of models the server offers, {@code null} unless the test succeeded
 * @version $Id$
 * @since 1.0.1
 */
public record ServerTestResult(Outcome outcome, Integer httpStatus, Integer modelCount)
{
    /**
     * What a server test found out.
     */
    public enum Outcome
    {
        /**
         * The server answered the model list request with a model list.
         */
        SUCCESS,

        /**
         * The server rejected the token (HTTP 401 or 403).
         */
        CREDENTIALS_REJECTED,

        /**
         * The server has no model list at the configured URL (HTTP 404).
         */
        NOT_FOUND,

        /**
         * The server answered with another error status.
         */
        HTTP_ERROR,

        /**
         * The server answered, but not with an OpenAI-compatible model list.
         */
        INVALID_RESPONSE,

        /**
         * No connection could be established, e.g. because the host is unknown or refuses the connection.
         */
        UNREACHABLE,

        /**
         * The server did not answer in time.
         */
        TIMEOUT,

        /**
         * The TLS handshake failed, e.g. because of an untrusted certificate.
         */
        TLS_ERROR,

        /**
         * The configured URL is missing or not an absolute HTTP(S) URL.
         */
        INVALID_URL,

        /**
         * The configured token contains characters that cannot be sent in an HTTP header.
         */
        INVALID_TOKEN,

        /**
         * The connection failed for another reason.
         */
        CONNECTION_ERROR
    }

    /**
     * @return {@code true} if the server is reachable, accepted the token and lists models
     */
    public boolean isSuccess()
    {
        return this.outcome == Outcome.SUCCESS;
    }
}
