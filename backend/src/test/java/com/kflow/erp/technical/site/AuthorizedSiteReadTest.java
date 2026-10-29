package com.kflow.erp.technical.site;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.kflow.erp.iam.api.*;
import com.kflow.erp.organization.api.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;

class AuthorizedSiteReadTest {
  final ActionAuthorization action = mock(ActionAuthorization.class);
  final PrincipalAccessQueries principals = mock(PrincipalAccessQueries.class);
  final OrganizationReferenceQueries owner = mock(OrganizationReferenceQueries.class);
  final RequestPrincipal principal = mock(RequestPrincipal.class);
  final UUID company = UUID.randomUUID(), site = UUID.randomUUID();
  final AuthorizedSiteRead facade = new AuthorizedSiteRead(action, principals, owner);

  @BeforeEach
  void setup() {
    when(principals.requireCurrent()).thenReturn(principal);
    when(principal.companyId()).thenReturn(company);
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {"bad", "1-1-1-1-1", "00000000-0000-1000-8000-000000000001"})
  void t01_t09_deniedActionPrecedesEvenInputParsing(String id) {
    doThrow(new ActionAuthorizationFailure(ActionAuthorizationFailure.Reason.DENIED))
        .when(action)
        .requireCurrent(Action.SITE_READ);
    assertThatThrownBy(() -> facade.read(id, true)).isInstanceOf(ActionAuthorizationFailure.class);
    verifyNoInteractions(owner, principals);
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {"bad", "1-1-1-1-1", " "})
  void t09_authorizedMalformedIdIs400WithoutLookup(String id) {
    fail(400, () -> facade.read(id, false));
    verifyNoInteractions(owner);
  }

  @Test
  void t06_queryPayloadRejectedAfterAction() {
    fail(400, () -> facade.read(site.toString(), true));
    verify(action).requireCurrent(Action.SITE_READ);
    verifyNoInteractions(owner);
  }

  @Test
  void t06_noContextRejectsDirectInvocation() {
    when(principals.requireCurrent()).thenThrow(new IllegalStateException());
    fail(403, () -> facade.read(site.toString(), false));
    verifyNoInteractions(owner);
  }

  @ParameterizedTest
  @ValueSource(strings = {"ACTIVE", "INACTIVE"})
  void t02_t08_sameReferenceFourFieldsInStrictOrder(String status) {
    var ref = new SiteReference(site, company, "S1", "site", status, 999L);
    when(owner.findSite(company, site)).thenReturn(Optional.of(ref));
    assertThat(facade.read(site.toString(), false))
        .isEqualTo(new SiteDetail(site, "S1", "site", status));
    var order = inOrder(action, principals, owner);
    order.verify(action).requireCurrent(Action.SITE_READ);
    order.verify(principals).requireCurrent();
    order.verify(owner).findSite(company, site);
    order.verify(action).requireSiteRead(company);
    verifyNoMoreInteractions(owner);
  }

  @Test
  void t09_uuidVersionOneIsNotArtificiallyRejected() {
    UUID id = UUID.fromString("12345678-1234-1234-8123-123456789abc");
    when(owner.findSite(company, id)).thenReturn(Optional.empty());
    fail(404, () -> facade.read(id.toString().toUpperCase(Locale.ROOT), false));
    verify(owner).findSite(company, id);
  }

  @Test
  void t09_absentIs404() {
    when(owner.findSite(company, site)).thenReturn(Optional.empty());
    fail(404, () -> facade.read(site.toString(), false));
  }

  @ParameterizedTest
  @ValueSource(strings = {"id", "company", "code", "name", "status", "nullOptional"})
  void t10_damagedOwnerProjectionIs503(String damage) {
    var ref =
        new SiteReference(
            damage.equals("id") ? UUID.randomUUID() : site,
            damage.equals("company") ? UUID.randomUUID() : company,
            damage.equals("code") ? null : "S1",
            damage.equals("name") ? "" : "site",
            damage.equals("status") ? "BROKEN" : "ACTIVE",
            0L);
    when(owner.findSite(company, site))
        .thenReturn(damage.equals("nullOptional") ? null : Optional.of(ref));
    fail(503, () -> facade.read(site.toString(), false));
    verify(action, never()).requireSiteRead(any());
  }

  @Test
  void t10_singletonAndStorageFailuresAreNot404() {
    when(owner.findSite(company, site))
        .thenThrow(new OrganizationFailure(OrganizationFailure.Code.COMPANY_CONFIGURATION_INVALID));
    fail(503, () -> facade.read(site.toString(), false));
    doThrow(new IllegalStateException("private SQL diagnostic"))
        .when(owner)
        .findSite(company, site);
    fail(503, () -> facade.read(site.toString(), false));
  }

  static void fail(int status, Runnable run) {
    assertThatThrownBy(run::run)
        .isInstanceOfSatisfying(
            SiteReadFailure.class,
            e -> {
              assertThat(e.status()).isEqualTo(status);
              assertThat(e.getMessage()).isEqualTo("Site read rejected");
              assertThat(e.getCause()).isNull();
            });
  }
}
