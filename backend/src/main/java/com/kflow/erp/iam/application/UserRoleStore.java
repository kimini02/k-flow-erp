package com.kflow.erp.iam.application;
import com.kflow.erp.iam.domain.UserRole;
import java.util.*;
/** IAM-private persistence cooperation; not a cross-module Published Port. */
public interface UserRoleStore {
    record UserFacts(UUID companyId,UUID userId,UUID employeeId,String status) {}
    record RoleFacts(UUID companyId,UUID roleId,String status) {}
    record UserRoleRoute(UUID companyId,UUID grantId,UUID roleId) {}
    void requireWritableTransaction();
    void boundRootWaits(int seconds);
    UserFacts findUserFacts(UUID company,UUID user);
    UserFacts lockUserShare(UUID company,UUID user);
    RoleFacts lockRoleShare(UUID company,UUID role);
    Optional<UserRoleRoute> findRoute(UUID company,UUID grant);
    UserRole lockUserRole(UUID company,UUID grant);
    List<UUID> unrevokedIds(UUID company,UUID role);
    void add(UserRole grant);
    void flush();
    Optional<UserRoleView> findUserRole(UUID company,UUID grant);
    List<UserRoleView> listByUser(UUID company,UUID user,String state,int offset,int limit);
    List<UserRoleView> listByRole(UUID company,UUID role,String state,int offset,int limit);
    RuntimeException translate(RuntimeException failure);
}
