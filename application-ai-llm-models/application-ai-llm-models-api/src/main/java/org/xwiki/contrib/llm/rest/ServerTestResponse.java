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

import org.xwiki.stability.Unstable;

/**
 * The answer of {@link ServerTestResource}.
 *
 * @param success {@code true} if the server is reachable, accepted the token and lists models
 * @param outcome what the test found out: {@code SUCCESS}, {@code CREDENTIALS_REJECTED}, {@code NOT_FOUND},
 *     {@code HTTP_ERROR}, {@code INVALID_RESPONSE}, {@code UNREACHABLE}, {@code TIMEOUT}, {@code TLS_ERROR},
 *     {@code INVALID_URL}, {@code INVALID_TOKEN} or {@code CONNECTION_ERROR} when the test was performed, and
 *     {@code FORBIDDEN}, {@code INVALID_FORM_TOKEN} or {@code UNKNOWN_SERVER} when it was refused
 * @param message a message for the user describing the outcome and what to check, in the language of the user
 * @version $Id$
 * @since 1.0.2
 */
@Unstable
public record ServerTestResponse(boolean success, String outcome, String message)
{
}
