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
package org.xwiki.contrib.llm.openai;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Unit tests for {@link ChatCompletionRequest}.
 *
 * @version $Id$
 */
class ChatCompletionRequestTest
{
    private static final String MODEL = "gpt-5";

    private static final String LOW = "low";

    @Test
    void readsReasoningEffort() throws Exception
    {
        // Default configuration, as the mapper of the REST server (RestletJacksonContextResolver in XWiki): it fails on
        // unknown properties.
        ObjectMapper objectMapper = JsonMapper.builder().build();

        ChatCompletionRequest request = objectMapper.readValue(
            "{\"model\":\"gpt-5\",\"messages\":[],\"reasoning_effort\":\"low\"}", ChatCompletionRequest.class);

        assertEquals(LOW, request.reasoningEffort());
    }

    @Test
    void butKeepsReasoningEffort()
    {
        ChatCompletionRequest request = ChatCompletionRequest.builder().model(MODEL).reasoningEffort(LOW).build();

        ChatCompletionRequest copy = request.but().model("other").build();

        assertEquals(LOW, copy.reasoningEffort());
    }
}
