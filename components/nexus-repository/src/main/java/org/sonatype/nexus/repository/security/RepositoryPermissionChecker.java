/*
 * Sonatype Nexus (TM) Open Source Version
 * Copyright (c) 2008-present Sonatype, Inc.
 * All rights reserved. Includes the third-party code listed at http://links.sonatype.com/products/nexus/oss/attributions.
 *
 * This program and the accompanying materials are made available under the terms of the Eclipse Public License Version 1.0,
 * which accompanies this distribution and is available at http://www.eclipse.org/legal/epl-v10.html.
 *
 * Sonatype Nexus (TM) Professional Version is available from Sonatype, Inc. "Sonatype" and "Sonatype Nexus" are trademarks
 * of Sonatype, Inc. Apache Maven is a trademark of the Apache Software Foundation. M2eclipse is a trademark of the
 * Eclipse Foundation. All other trademarks are the property of their respective owners.
 */
package org.sonatype.nexus.repository.security;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.sonatype.nexus.repository.Repository;
import org.sonatype.nexus.security.SecurityHelper;
import org.sonatype.nexus.selector.SelectorConfiguration;
import org.sonatype.nexus.selector.SelectorManager;

import org.apache.shiro.authz.Permission;
import org.apache.shiro.subject.Subject;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.util.Collections.singletonList;
import static org.sonatype.nexus.security.BreadActions.BROWSE;
import static org.sonatype.nexus.security.BreadActions.READ;

/**
 * Repository permission checker.
 *
 * @since 3.10
 */
@Named
@Singleton
public class RepositoryPermissionChecker
{
  private final SecurityHelper securityHelper;

  private final SelectorManager selectorManager;

  @Inject
  public RepositoryPermissionChecker(final SecurityHelper securityHelper, final SelectorManager selectorManager) {
    this.securityHelper = checkNotNull(securityHelper);
    this.selectorManager = checkNotNull(selectorManager);
  }

  /**
   * WARNING: This method should _only_ be used to check a single repository to prevent performance problems with large
   * numbers of content selectors. Use userCanBrowseRepositories instead to check multiple repositories.
   *
   * @return true if the user can browse the repository or if the user has a content selector granting access
   */
  public boolean userCanBrowseRepository(final Repository repository) {
    return userHasRepositoryViewPermissionTo(BROWSE, repository) || userHasAnyContentSelectorAccessTo(repository);
  }

  private boolean userHasRepositoryViewPermissionTo(final String action, final Repository repository) {
    return securityHelper.anyPermitted(new RepositoryViewPermission(repository, action));
  }

  /**
   * @since 3.13
   * @param repositories to test against browse permissions and content selector permissions
   * @return the repositories which the user has access to browse
   */
  public List<Repository> userCanBrowseRepositories(final Repository... repositories) {
    if (repositories.length == 0) {
      return Collections.emptyList();
    }
    Subject subject = securityHelper.subject();
    Permission[] permissions = Arrays.stream(repositories).map(r -> new RepositoryViewPermission(r, BROWSE))
        .toArray(Permission[]::new);
    boolean[] results = securityHelper.isPermitted(subject, permissions);

    List<Repository> permittedRepositories = new ArrayList<>();
    List<Repository> filteredRepositories = new ArrayList<>();

    for (int i = 0; i < results.length; i++) {
      if (results[i]) {
        permittedRepositories.add(repositories[i]);
      }
      else {
        filteredRepositories.add(repositories[i]);
      }
    }

    if (!filteredRepositories.isEmpty()) {
      permittedRepositories.addAll(subjectHasAnyContentSelectorAccessTo(subject, filteredRepositories));
    }

    return permittedRepositories;
  }

  private List<Repository> subjectHasAnyContentSelectorAccessTo(final Subject subject,
                                                                final List<Repository> repositories)
  {
    List<String> repositoryNames = repositories.stream().map(r -> r.getName()).collect(Collectors.toList());
    List<String> formats = repositories.stream().map(r -> r.getFormat().getValue()).distinct()
        .collect(Collectors.toList());
    List<SelectorConfiguration> selectors = selectorManager.browseActive(repositoryNames, formats);

    if (selectors.isEmpty()) {
      return Collections.emptyList();
    }

    List<Repository> permittedRepositories = new ArrayList<>();
    for (Repository repository : repositories) {
      Permission[] permissions = selectors.stream()
          .map(s -> new RepositoryContentSelectorPermission(s, repository, singletonList(BROWSE)))
          .toArray(Permission[]::new);
      if (securityHelper.anyPermitted(subject, permissions)) {
        permittedRepositories.add(repository);
      }
    }

    return permittedRepositories;
  }

  private boolean userHasAnyContentSelectorAccessTo(final Repository repository) {
    Subject subject = securityHelper.subject(); //Getting the subject a single time improves performance
    return selectorManager.browse().stream()
        .anyMatch(selector -> securityHelper.anyPermitted(subject,
            new RepositoryContentSelectorPermission(selector, repository, singletonList(BROWSE))));
  }
}
