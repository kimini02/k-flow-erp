package com.kflow.erp.iam.application;
import com.kflow.erp.iam.domain.Role;
import java.util.*;
/** IAM-internal persistence boundary. No cross-module Published Role port. */
public interface RoleStore {
    void requireWritableTransaction();
    void boundRootWaits(int timeoutSeconds);
    void add(Role role);
    void flush();
    Role lockRole(UUID company,UUID role);
    Optional<RoleView> findRole(UUID company,UUID role);
    Optional<RoleView> findByCode(UUID company,String code);
    List<RoleListItem> list(UUID company,String status,int offset,int limit);
    RuntimeException translate(RuntimeException failure);
}
