package com.kflow.erp.technical.site;

/** Generic external classifications; never carries target or persistence diagnostics. */
public final class SiteReadFailure extends RuntimeException {
  private final int status;

  public SiteReadFailure(int status) {
    super("Site read rejected");
    this.status = status;
  }

  public int status() {
    return status;
  }
}
