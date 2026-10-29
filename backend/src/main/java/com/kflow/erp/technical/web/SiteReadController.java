package com.kflow.erp.technical.web;

import com.kflow.erp.iam.api.ActionAuthorizationFailure;
import com.kflow.erp.technical.site.*;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
public class SiteReadController {
  private final AuthorizedSiteRead sites;

  public SiteReadController(AuthorizedSiteRead sites) {
    this.sites = sites;
  }

  @GetMapping("/api/organization/sites/{siteId}")
  public ResponseEntity<SiteDetail> find(@PathVariable String siteId, HttpServletRequest request) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(sites.read(siteId, request.getQueryString() != null));
  }

  @ExceptionHandler({SiteReadFailure.class, ActionAuthorizationFailure.class})
  public ResponseEntity<Map<String, Object>> rejected(RuntimeException failure) {
    int status = failure instanceof SiteReadFailure f ? f.status() : 403;
    String code =
        switch (status) {
          case 400 -> "INVALID_REQUEST";
          case 404 -> "NOT_FOUND";
          case 503 -> "SERVICE_UNAVAILABLE";
          default -> "ACCESS_DENIED";
        };
    return ResponseEntity.status(status)
        .cacheControl(CacheControl.noStore())
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(
            Map.of(
                "type",
                "urn:kflow:problem:" + code.toLowerCase(java.util.Locale.ROOT).replace('_', '-'),
                "status",
                status,
                "code",
                code));
  }
}
