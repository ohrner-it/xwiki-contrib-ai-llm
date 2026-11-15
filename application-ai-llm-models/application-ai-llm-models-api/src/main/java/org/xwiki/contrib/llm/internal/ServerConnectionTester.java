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

import java.io.IOException;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;

import javax.inject.Inject;
import javax.inject.Singleton;
import javax.net.ssl.SSLException;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.xwiki.component.annotation.Component;
import org.xwiki.contrib.llm.GPTAPIConfig;
import org.xwiki.contrib.llm.internal.ServerTestResult.Outcome;

import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Tests the connection to an AI server by requesting its model list ({@code GET models}, part of the OpenAI API and
 * offered by the OpenAI-compatible servers), which checks the URL and the token without generating anything.
 *
 * @version $Id$
 * @since 1.0.2
 */
@Component(roles = ServerConnectionTester.class)
@Singleton
public class ServerConnectionTester
{
    private static final String MODELS_PATH = "models";

    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    @Inject
    private RequestHelper requestHelper;

    @Inject
    private Logger logger;

    /**
     * Test the connection to a server. Neither the token nor the answer of the server is part of the result or the
     * log, as the answer to a rejected token can contain a part of it.
     *
     * @param config the configuration of the server
     * @return the result of the test
     */
    public ServerTestResult test(GPTAPIConfig config)
    {
        if (StringUtils.isBlank(config.getURL())) {
            return new ServerTestResult(Outcome.INVALID_URL, null, null);
        }

        ServerTestResult result;
        try {
            result = evaluate(
                this.requestHelper.get(config, MODELS_PATH, TIMEOUT, HttpResponse.BodyHandlers.ofString()));
        } catch (IllegalArgumentException e) {
            // Not logged: the message of the exception quotes the URL, which can contain credentials.
            result = new ServerTestResult(Outcome.INVALID_URL, null, null);
        } catch (InvalidTokenException e) {
            result = new ServerTestResult(Outcome.INVALID_TOKEN, null, null);
        } catch (IOException e) {
            Outcome outcome = getConnectionFailureOutcome(e);
            // The exceptions of the network layer of the HTTP client name the host at most, never the headers.
            this.logger.debug("Testing the AI server [{}] failed with outcome [{}].", config.getName(), outcome, e);
            result = new ServerTestResult(outcome, null, null);
        }
        return result;
    }

    private static Outcome getConnectionFailureOutcome(IOException e)
    {
        Outcome outcome;
        if (e instanceof HttpTimeoutException) {
            outcome = Outcome.TIMEOUT;
        } else if (e instanceof ConnectException) {
            outcome = Outcome.UNREACHABLE;
        } else if (e instanceof SSLException) {
            outcome = Outcome.TLS_ERROR;
        } else {
            outcome = Outcome.CONNECTION_ERROR;
        }
        return outcome;
    }

    private static ServerTestResult evaluate(HttpResponse<String> response)
    {
        int status = response.statusCode();
        Outcome outcome;
        Integer modelCount = null;
        if (status == HttpURLConnection.HTTP_OK) {
            modelCount = countModels(response.body());
            outcome = modelCount != null ? Outcome.SUCCESS : Outcome.INVALID_RESPONSE;
        } else if (status == HttpURLConnection.HTTP_UNAUTHORIZED || status == HttpURLConnection.HTTP_FORBIDDEN) {
            outcome = Outcome.CREDENTIALS_REJECTED;
        } else if (status == HttpURLConnection.HTTP_NOT_FOUND) {
            outcome = Outcome.NOT_FOUND;
        } else {
            outcome = Outcome.HTTP_ERROR;
        }
        return new ServerTestResult(outcome, status, modelCount);
    }

    /**
     * @return the number of entries in the {@code data} array of an OpenAI model list, {@code null} if the body is no
     *     such list
     */
    private static Integer countModels(String body)
    {
        try {
            JsonNode data = new ObjectMapper().readTree(body).path("data");
            return data.isArray() ? data.size() : null;
        } catch (JacksonException e) {
            return null;
        }
    }
}
