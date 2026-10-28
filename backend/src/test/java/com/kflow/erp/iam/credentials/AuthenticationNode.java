package com.kflow.erp.iam.credentials;

/**
 * Test launcher only: the ordinary production application, no fixture HTTP endpoints or approval
 * adapters.
 */
public final class AuthenticationNode {
  public static void main(String[] args) {
    com.kflow.erp.KFlowErpApplication.main(args);
  }
}
