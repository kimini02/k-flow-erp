package com.kflow.erp.iam.application;
import com.kflow.erp.iam.domain.User;
import java.util.*;
/** IAM-internal persistence boundary, never exposed to other modules. */
public interface IamStore {
    void requireWritableTransaction();
    void boundRootWaits(int timeoutSeconds);
    void add(User user);
    void flush();
    User lockUser(UUID company,UUID user);
    Optional<UserView> findUser(UUID company,UUID user);
    Optional<UserView> findByEmployee(UUID company,UUID employee);
    List<UserListItem> list(UUID company,String status,int offset,int limit);
    RuntimeException translate(RuntimeException failure);
}
