package com.kflow.erp.iam.api;

/**
 * Already verified current HTTP request only; no arbitrary subject lookup or authority decision.
 */
public interface PrincipalAccessQueries {
  RequestPrincipal requireCurrent();
}
