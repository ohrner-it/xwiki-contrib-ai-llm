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
package org.xwiki.contrib.llm;

import java.util.List;

import org.xwiki.component.annotation.Role;
import org.xwiki.stability.Unstable;

/**
 * A way of splitting the content of a document into chunks for indexing. The hint of the component is the value of
 * the chunking method setting of a collection ({@link Collection#getChunkingMethod()}), so extensions can provide
 * further chunking methods that collections can then select.
 * <p>
 * Use {@link ChunkingUtils#chunkDocument(Collection, Document)} rather than calling
 * {@link #chunk(Collection, Document)} directly. Implementations must be thread-safe.
 *
 * @version $Id$
 * @since 1.0.1
 */
@Role
@Unstable
public interface ChunkingMethod
{
    /**
     * Split the content of the given document into chunks.
     *
     * @param collection the collection the document is indexed for, providing the chunking settings; its maximum
     *     chunk size is at least 10 characters and its overlap offset is at least 0 and smaller than half the maximum
     *     chunk size
     * @param document the document to split
     * @return the texts of the chunks, in the order of the document
     * @throws IndexException if the document cannot be split
     */
    List<ChunkText> chunk(Collection collection, Document document) throws IndexException;
}
