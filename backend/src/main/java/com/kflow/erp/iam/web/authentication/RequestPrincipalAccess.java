package com.kflow.erp.iam.web.authentication;

import com.kflow.erp.iam.api.*;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.*;

@Component
public class RequestPrincipalAccess implements PrincipalAccessQueries {
  private static final String KEY = RequestPrincipalAccess.class.getName() + ".principal";

  private record Binding(Thread owner, RequestPrincipal principal) {}

  static void publish(HttpServletRequest request, RequestPrincipal principal) {
    request.setAttribute(KEY, new Binding(Thread.currentThread(), principal));
  }

  static void clear(HttpServletRequest request) {
    request.removeAttribute(KEY);
  }

  public RequestPrincipal requireCurrent() {
    if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes a
        && a.getRequest().getDispatcherType() == DispatcherType.REQUEST
        && a.getRequest().getAttribute(KEY) instanceof Binding b
        && b.owner() == Thread.currentThread()) return b.principal();
    throw new IllegalStateException("No verified synchronous request principal");
  }
}
