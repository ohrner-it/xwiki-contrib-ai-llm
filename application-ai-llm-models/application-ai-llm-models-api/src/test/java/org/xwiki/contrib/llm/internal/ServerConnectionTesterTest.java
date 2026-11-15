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
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.stream.Stream;

import javax.net.ssl.SSLHandshakeException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.xwiki.contrib.llm.GPTAPIConfig;
import org.xwiki.contrib.llm.internal.ServerTestResult.Outcome;
import org.xwiki.test.annotation.ComponentList;
import org.xwiki.test.junit5.mockito.ComponentTest;
import org.xwiki.test.junit5.mockito.InjectMockComponents;
import org.xwiki.test.junit5.mockito.MockComponent;

import com.sun.net.httpserver.HttpServer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Component test for {@link ServerConnectionTester}.
 *
 * @version $Id$
 */
@ComponentTest
@ComponentList({ RequestHelper.class })
class ServerConnectionTesterTest
{
    private static final String URL = "https://llm.example.org/v1/";

    private static final String TOKEN = "sk-test-token";

    @InjectMockComponents
    private ServerConnectionTester tester;

    @MockComponent
    private HttpClientFactory httpClientFactory;

    @Mock
    private HttpClient httpClient;

    @Mock
    private HttpResponse<Object> httpResponse;

    @Mock
    private GPTAPIConfig config;

    @BeforeEach
    void setUp()
    {
        when(this.httpClientFactory.createHttpClient()).thenReturn(this.httpClient);
        when(this.config.getURL()).thenReturn(URL);
        when(this.config.getToken()).thenReturn(TOKEN);
    }

    @Test
    void listedModelsMeanSuccess() throws Exception
    {
        respond(200, "{\"object\":\"list\",\"data\":[{\"id\":\"model-a\"},{\"id\":\"model-b\"}]}");

        ServerTestResult result = this.tester.test(this.config);

        assertEquals(new ServerTestResult(Outcome.SUCCESS, 200, 2), result);
        assertTrue(result.isSuccess());
    }

    @Test
    void listsTheModelsWithTheTokenAndATimeout() throws Exception
    {
        respond(200, "{\"data\":[]}");

        this.tester.test(this.config);

        ArgumentCaptor<HttpRequest> requestCaptor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(this.httpClient).send(requestCaptor.capture(), any());
        HttpRequest request = requestCaptor.getValue();
        assertEquals("GET", request.method());
        assertEquals(URL + "models", request.uri().toString());
        assertEquals("Bearer " + TOKEN, request.headers().firstValue("Authorization").orElseThrow());
        assertEquals(Duration.ofSeconds(30), request.timeout().orElseThrow());
    }

    @ParameterizedTest
    @ValueSource(ints = { 401, 403 })
    void rejectedToken(int status) throws Exception
    {
        respond(status, "{\"error\":{\"message\":\"Incorrect API key provided: sk-tes***\"}}");

        ServerTestResult result = this.tester.test(this.config);

        assertEquals(new ServerTestResult(Outcome.CREDENTIALS_REJECTED, status, null), result);
        assertFalse(result.isSuccess());
    }

    @Test
    void missingModelListMeansWrongURL() throws Exception
    {
        respond(404, "Not Found");

        assertEquals(new ServerTestResult(Outcome.NOT_FOUND, 404, null), this.tester.test(this.config));
    }

    @Test
    void otherStatusIsReported() throws Exception
    {
        respond(502, "Bad Gateway");

        assertEquals(new ServerTestResult(Outcome.HTTP_ERROR, 502, null), this.tester.test(this.config));
    }

    @ParameterizedTest
    @ValueSource(strings = { "<html><body>Welcome</body></html>", "{\"object\":\"list\"}", "{\"data\":{}}" })
    void answerWithoutModelListIsInvalid(String body) throws Exception
    {
        respond(200, body);

        assertEquals(new ServerTestResult(Outcome.INVALID_RESPONSE, 200, null), this.tester.test(this.config));
    }

    static Stream<Arguments> connectionFailures()
    {
        return Stream.of(
            Arguments.of(new HttpConnectTimeoutException("connect timed out"), Outcome.TIMEOUT),
            Arguments.of(new HttpTimeoutException("request timed out"), Outcome.TIMEOUT),
            Arguments.of(new ConnectException("Connection refused"), Outcome.UNREACHABLE),
            Arguments.of(new SSLHandshakeException("PKIX path building failed"), Outcome.TLS_ERROR),
            Arguments.of(new IOException("Connection reset"), Outcome.CONNECTION_ERROR)
        );
    }

    @ParameterizedTest
    @MethodSource("connectionFailures")
    void connectionFailure(IOException exception, Outcome expectedOutcome) throws Exception
    {
        when(this.httpClient.send(any(HttpRequest.class), any())).thenThrow(exception);

        assertEquals(new ServerTestResult(expectedOutcome, null, null), this.tester.test(this.config));
    }

    @ParameterizedTest
    @ValueSource(strings = { " ", "llm.example.org/v1/", "https://llm example.org/v1/", "ftp://llm.example.org/" })
    void invalidURL(String url)
    {
        when(this.config.getURL()).thenReturn(url);

        assertEquals(new ServerTestResult(Outcome.INVALID_URL, null, null), this.tester.test(this.config));
        verifyNoInteractions(this.httpClient);
    }

    @ParameterizedTest
    @ValueSource(strings = { "sk-test-token\r", "sk-test-token\u200B" })
    void tokenThatCannotBeSent(String token)
    {
        when(this.config.getToken()).thenReturn(token);

        assertEquals(new ServerTestResult(Outcome.INVALID_TOKEN, null, null), this.tester.test(this.config));
        verifyNoInteractions(this.httpClient);
    }

    @Test
    void refusedConnectionWithRealClient() throws Exception
    {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        when(this.httpClientFactory.createHttpClient()).thenReturn(new HttpClientFactory().createHttpClient());
        when(this.config.getURL()).thenReturn("http://localhost:" + closedPort + "/v1/");

        assertEquals(new ServerTestResult(Outcome.UNREACHABLE, null, null), this.tester.test(this.config));
    }

    @Test
    void httpsToPlainHttpServerWithRealClient() throws Exception
    {
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.start();
        try {
            when(this.httpClientFactory.createHttpClient()).thenReturn(new HttpClientFactory().createHttpClient());
            when(this.config.getURL()).thenReturn("https://localhost:" + server.getAddress().getPort() + "/v1/");

            assertEquals(new ServerTestResult(Outcome.TLS_ERROR, null, null), this.tester.test(this.config));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void missingURL()
    {
        when(this.config.getURL()).thenReturn(null);

        assertEquals(new ServerTestResult(Outcome.INVALID_URL, null, null), this.tester.test(this.config));
        verifyNoInteractions(this.httpClient);
    }

    private void respond(int status, String body) throws Exception
    {
        when(this.httpClient.send(any(HttpRequest.class), any())).thenReturn(this.httpResponse);
        when(this.httpResponse.statusCode()).thenReturn(status);
        when(this.httpResponse.body()).thenReturn(body);
    }
}
