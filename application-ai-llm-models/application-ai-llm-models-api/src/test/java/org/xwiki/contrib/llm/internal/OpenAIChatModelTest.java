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

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Flow;

import javax.inject.Named;
import javax.inject.Provider;

import org.apache.commons.io.IOUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.xwiki.contrib.llm.GPTAPIConfig;
import org.xwiki.contrib.llm.GPTAPIConfigProvider;
import org.xwiki.contrib.llm.openai.ChatCompletionChunk;
import org.xwiki.contrib.llm.openai.ChatCompletionRequest;
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.model.reference.ObjectReference;
import org.xwiki.test.annotation.ComponentList;
import org.xwiki.test.junit5.mockito.ComponentTest;
import org.xwiki.test.junit5.mockito.InjectComponentManager;
import org.xwiki.test.junit5.mockito.MockComponent;
import org.xwiki.test.mockito.MockitoComponentManager;
import org.xwiki.user.UserReferenceSerializer;
import org.xwiki.user.group.GroupManager;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xpn.xwiki.XWikiContext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Component test for {@link OpenAIChatModel}.
 *
 * @version $Id$
 */
@ComponentTest
@ComponentList({ RequestHelper.class })
class OpenAIChatModelTest
{
    private static final String SERVER_NAME = "provider";

    private static final String PROVIDER_MODEL = "gpt-5";

    private static final String REASONING_EFFORT = "reasoning_effort";

    private static final String LOW = "low";

    private static final String HIGH = "high";

    private static final ObjectReference OBJECT_REFERENCE = new ObjectReference("AI.Models.Code.ModelsClass[0]",
        new DocumentReference("xwiki", List.of("AI", "Models"), "GPT5"));

    private static final String COMPLETION_RESPONSE = """
        {"id":"chatcmpl-1","object":"chat.completion","created":1,"model":"gpt-5",
        "choices":[{"index":0,"message":{"role":"assistant","content":"Hello"},"finish_reason":"stop"}]}
        """;

    private static final String COMPLETION_STREAM = """
        data: {"id":"chatcmpl-1","object":"chat.completion.chunk","created":1,"model":"gpt-5",\
        "choices":[{"index":0,"delta":{"role":"assistant","content":"Hello"},"finish_reason":null}]}

        data: [DONE]

        """;

    @MockComponent
    private HttpClientFactory httpClientFactory;

    @MockComponent
    private GroupManager groupManager;

    @MockComponent
    @Named("document")
    private UserReferenceSerializer<DocumentReference> userReferenceSerializer;

    @MockComponent
    private GPTAPIConfigProvider configProvider;

    // Required by the constructor of the model, not used by the tested code.
    @MockComponent
    private Provider<XWikiContext> contextProvider;

    @InjectComponentManager
    private MockitoComponentManager componentManager;

    @Mock
    private HttpClient httpClient;

    @Mock
    private HttpResponse<Object> httpResponse;

    @Mock
    private GPTAPIConfig config;

    @BeforeEach
    void setUp() throws Exception
    {
        when(this.httpClientFactory.createHttpClient()).thenReturn(this.httpClient);
        when(this.httpClient.send(any(HttpRequest.class), any())).thenReturn(this.httpResponse);
        when(this.httpResponse.statusCode()).thenReturn(200);
        when(this.config.getURL()).thenReturn("https://llm.example.org/v1/");
        when(this.configProvider.getConfigObjects("xwiki")).thenReturn(Map.of(SERVER_NAME, this.config));
    }

    @Test
    void configuredReasoningEffortReplacesTheRequestValue() throws Exception
    {
        when(this.httpResponse.body()).thenReturn(COMPLETION_RESPONSE);

        createModel(LOW).process(request(HIGH));

        assertEquals(LOW, sentRequest().path(REASONING_EFFORT).asText());
    }

    @Test
    void requestReasoningEffortIsForwardedWithoutConfiguredValue() throws Exception
    {
        when(this.httpResponse.body()).thenReturn(COMPLETION_RESPONSE);

        createModel(null).process(request(HIGH));

        assertEquals(HIGH, sentRequest().path(REASONING_EFFORT).asText());
    }

    @Test
    void reasoningEffortIsOmittedWhenNeitherConfiguredNorRequested() throws Exception
    {
        when(this.httpResponse.body()).thenReturn(COMPLETION_RESPONSE);

        createModel(null).process(request(null));

        JsonNode sentRequest = sentRequest();
        assertEquals(PROVIDER_MODEL, sentRequest.get("model").asText());
        assertFalse(sentRequest.has(REASONING_EFFORT));
    }

    @Test
    void configuredReasoningEffortIsSentWhenStreaming() throws Exception
    {
        when(this.config.getCanStream()).thenReturn(true);
        when(this.httpResponse.body()).thenReturn(IOUtils.toInputStream(COMPLETION_STREAM, StandardCharsets.UTF_8));
        List<ChatCompletionChunk> chunks = new ArrayList<>();

        createModel(LOW).processStreaming(request(null), chunks::add);

        assertEquals(1, chunks.size());
        assertEquals(LOW, sentRequest().path(REASONING_EFFORT).asText());
    }

    private OpenAIChatModel createModel(String reasoningEffort) throws Exception
    {
        ModelConfiguration modelConfiguration = new ModelConfiguration();
        modelConfiguration.setServerName(SERVER_NAME);
        modelConfiguration.setModel(PROVIDER_MODEL);
        modelConfiguration.setObjectReference(OBJECT_REFERENCE);
        modelConfiguration.setReasoningEffort(reasoningEffort);
        return new OpenAIChatModel(modelConfiguration, this.componentManager);
    }

    private static ChatCompletionRequest request(String reasoningEffort)
    {
        return ChatCompletionRequest.builder()
            .model("AI.Models.GPT5")
            .messages(List.of())
            .reasoningEffort(reasoningEffort)
            .build();
    }

    /**
     * @return the JSON body of the request sent to the provider
     */
    private JsonNode sentRequest() throws Exception
    {
        ArgumentCaptor<HttpRequest> requestCaptor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(this.httpClient).send(requestCaptor.capture(), any());

        Flow.Subscriber<ByteBuffer> bufferSubscriber = mock();
        doAnswer(invocation -> {
            Flow.Subscription subscription = invocation.getArgument(0);
            subscription.request(Long.MAX_VALUE);
            return null;
        }).when(bufferSubscriber).onSubscribe(any());
        requestCaptor.getValue().bodyPublisher().orElseThrow().subscribe(bufferSubscriber);
        ArgumentCaptor<ByteBuffer> bufferCaptor = ArgumentCaptor.forClass(ByteBuffer.class);
        verify(bufferSubscriber).onNext(bufferCaptor.capture());

        return new ObjectMapper().readTree(StandardCharsets.UTF_8.decode(bufferCaptor.getValue()).toString());
    }
}
