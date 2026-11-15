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

import java.util.ArrayList;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.inject.Inject;
import javax.inject.Provider;
import javax.inject.Singleton;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.slf4j.Logger;
import org.xwiki.component.annotation.Component;
import org.xwiki.contrib.llm.Collection;
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.model.reference.DocumentReferenceResolver;
import org.xwiki.user.group.GroupException;
import org.xwiki.user.group.GroupManager;
import org.xwiki.user.group.WikiTarget;

import com.xpn.xwiki.XWikiContext;

/**
 * Checks whether the current user may query a collection: when it allows guests, or when the user is a member of
 * one of its query groups.
 *
 * @version $Id$
 * @since 1.0.2
 */
@Component(roles = CollectionAccessChecker.class)
@Singleton
public class CollectionAccessChecker
{
    @Inject
    private Provider<XWikiContext> contextProvider;

    @Inject
    private GroupManager groupManager;

    @Inject
    private DocumentReferenceResolver<String> documentReferenceResolver;

    @Inject
    private Logger logger;

    /**
     * @param collection the collection
     * @return whether the current user may query the collection
     */
    public boolean hasAccess(Collection collection)
    {
        if (collection.getAllowGuests()) {
            return true;
        }
        java.util.Collection<DocumentReference> userGroups = fetchCrtUserGroups();
        if (userGroups.isEmpty()) {
            return false;
        }

        java.util.Collection<DocumentReference> allowedGroupReferences =
            convertAllowedGroupsToReferences(collection.getQueryGroups());

        return allowedGroupReferences.stream().anyMatch(userGroups::contains);
    }

    private java.util.Collection<DocumentReference> fetchCrtUserGroups()
    {
        DocumentReference documentUserReference = this.contextProvider.get().getUserReference();
        java.util.Collection<DocumentReference> userGroups = new ArrayList<>();
        try {
            userGroups = this.groupManager.getGroups(documentUserReference, WikiTarget.ENTITY_AND_CURRENT, true);
        } catch (GroupException e) {
            this.logger.warn("Failed to get groups for user [{}]: [{}]", documentUserReference,
                ExceptionUtils.getRootCauseMessage(e));
        }
        return userGroups;
    }

    private java.util.Collection<DocumentReference> convertAllowedGroupsToReferences(String stringGroups)
    {
        return Stream.of(StringUtils.split(stringGroups, ','))
            .map(String::trim)
            .map(this.documentReferenceResolver::resolve)
            .collect(Collectors.toList());
    }
}
