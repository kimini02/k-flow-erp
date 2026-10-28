package com.kflow.erp.iam.api;

/** Approved action identifiers; neither a URL nor a Role code. */
public enum Action {
  SITE_READ("organization.site.read"),
  GRANT_SITE_READER("iam.user-role.grant-site-reader");

  private final String id;

  Action(String id) {
    this.id = id;
  }

  public String id() {
    return id;
  }

  public static Action fromId(String id) {
    for (var action : values()) if (action.id.equals(id)) return action;
    throw new ActionAuthorizationFailure(ActionAuthorizationFailure.Reason.DENIED);
  }
}
