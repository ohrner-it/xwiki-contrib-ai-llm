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

/**
 * Thrown when the configured token of an AI server cannot be sent in an HTTP header because it contains characters
 * that are not allowed there, e.g. a line break or a zero-width space copied along with it. Unlike the exception of
 * the HTTP client, it does not quote the token.
 *
 * @version $Id$
 * @since 1.0.2
 */
public class InvalidTokenException extends IOException
{
    private static final long serialVersionUID = 1L;

    /**
     * Create the exception.
     */
    public InvalidTokenException()
    {
        super("The configured token contains characters that are not allowed in an HTTP header.");
    }
}
