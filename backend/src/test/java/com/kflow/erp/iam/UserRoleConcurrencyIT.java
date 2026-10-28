package com.kflow.erp.iam;

import com.kflow.erp.iam.api.IamFailure;
import com.kflow.erp.iam.application.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static com.kflow.erp.iam.api.IamFailure.Code.*;

/** Real PostgreSQL roots and observed wait edges; latches select schedules, never substitute for locks. */
class UserRoleConcurrencyIT extends UserRoleTestSupport {
    RevokeUserRole revoke(UserRoleView g,long v){return new RevokeUserRole(company,g.grantId(),v,"manual reason");}
    Map<String,Map<String,Object>> observe(){
        var roots=new ConcurrentHashMap<String,Map<String,Object>>();
        doAnswer(c->{c.callRealMethod();roots.putIfAbsent(Thread.currentThread().getName(),physical());return null;}).when(userRoles).requireWritableTransaction();
        doAnswer(c->{c.callRealMethod();roots.putIfAbsent(Thread.currentThread().getName(),physical());return null;}).when(roleStore).requireWritableTransaction();return roots;
    }
    void distinct(Map<String,Map<String,Object>> roots,String a,String b){assertThat(roots.get(a).get("pid")).isNotEqualTo(roots.get(b).get("pid"));assertThat(roots.get(a).get("xid")).isNotEqualTo(roots.get(b).get("xid"));}
    void code(Object outcome,IamFailure.Code code){assertThat(outcome).isInstanceOfSatisfying(IamFailure.class,f->assertThat(f.code()).isEqualTo(code));}
    void unique(Object outcome){code(outcome,USER_ROLE_ALREADY_GRANTED);var p=pg((Throwable)outcome);assertThat(p.getSQLState()).isEqualTo("23505");assertThat(p.getServerErrorMessage().getConstraint()).isEqualTo("uq_iam_user_role_unrevoked");}
    boolean exists(UUID id){return jdbc.queryForObject("select count(*) from iam_user_role where id=?",Integer.class,id)==1;}
    String log(Map<String,Object> id){return sql(id).toLowerCase(Locale.ROOT);}
    void evidence(String label,Map<String,Map<String,Object>> roots){System.out.println("I3 "+label+" roots="+roots);roots.forEach((n,id)->System.out.println("I3 "+label+" "+n+" PID/XID="+id+"\n"+sql(id)));}

    @ParameterizedTest @ValueSource(booleans={true,false})
    void t03_t04_t05_partialUniqueWaitsForActualInsertCommitOrRollback(boolean commit){
        var u=user("unique");var r=role("R");var ca=grant(u,r);var cb=grant(u,r);var parents=baseline();var before=roleRow(r.roleId());var roots=observe();var held=gate();var release=gate();
        doAnswer(c->{c.callRealMethod();if(thread("A")){assertThat(exists(ca.grantId())).isTrue();held.countDown();await(release);if(!commit)throw new IllegalStateException("A confirmed INSERT rollback");}return null;}).when(userRoles).flush();
        var a=job("A",()->service.grantRole(ca));await(held);assertThat(exists(ca.grantId())).isFalse();var b=job("B",()->service.grantRole(cb));blocked("insert into iam_user_role",roots.get("A"));assertThat(b.isDone()).isFalse();release.countDown();var ra=result(a);var rb=result(b);distinct(roots,"A","B");
        if(commit){assertThat(ra).isInstanceOf(UserRoleView.class);unique(rb);assertThat(unrevoked(r.roleId())).containsExactly(ca.grantId());assertThat(exists(cb.grantId())).isFalse();}
        else{assertThat(ra).isInstanceOf(RuntimeException.class);assertThat(rb).isInstanceOf(UserRoleView.class);assertThat(unrevoked(r.roleId())).containsExactly(cb.grantId());assertThat(exists(ca.grantId())).isFalse();}
        for(String n:List.of("A","B"))assertThat(log(roots.get(n))).contains("from iam_role","for share","from iam_user","insert into iam_user_role");unchanged(parents);assertThat(roleRow(r.roleId())).isEqualTo(before);evidence("T03/T04/T05 UNIQUE commit="+commit,roots);
    }

    @ParameterizedTest @ValueSource(booleans={true,false})
    void t10_sameGrantRevokeWaitsThenUsesLatestVersion(boolean commit){
        var r=role("R");var g=service.grantRole(grant(user("revoke"),r));var before=grantRow(g.grantId());var parents=baseline();var roleBefore=roleRow(r.roleId());var roots=observe();var held=gate();var release=gate();
        doAnswer(c->{c.callRealMethod();if(thread("A")){held.countDown();await(release);if(!commit)throw new IllegalStateException("revoke rollback");}return null;}).when(userRoles).flush();
        var a=job("A",()->service.revokeUserRole(revoke(g,0)));await(held);assertThat(grantRow(g.grantId())).isEqualTo(before);var b=job("B",()->service.revokeUserRole(revoke(g,0)));blocked("iam_user_role",roots.get("A"));release.countDown();var ra=result(a);var rb=result(b);distinct(roots,"A","B");
        if(commit){assertThat(ra).isInstanceOf(UserRoleView.class);code(rb,VERSION_CONFLICT);}else{assertThat(ra).isInstanceOf(RuntimeException.class);assertThat(rb).isInstanceOf(UserRoleView.class);}
        assertThat(grantRow(g.grantId()).get("version")).isEqualTo(1L);assertThat(grantRow(g.grantId()).get("revoke_kind")).isEqualTo("MANUAL");unchanged(parents);assertThat(roleRow(r.roleId())).isEqualTo(roleBefore);evidence("T10 same grant commit="+commit,roots);
    }

    @ParameterizedTest @ValueSource(booleans={true,false})
    void t09_t10_twoFreshNoopsKeepAllStoredValues(boolean firstCommits){
        var r=role("R");var g=service.grantRole(grant(user("noop"),r));var revoked=service.revokeUserRole(revoke(g,0));var before=grantRow(g.grantId());var roots=observe();var held=gate();var release=gate();
        doAnswer(c->{var v=c.callRealMethod();if(thread("A")){held.countDown();await(release);if(!firstCommits)throw new IllegalStateException("noop rollback");}return v;}).when(userRoles).lockUserRole(any(),any());
        var a=job("A",()->service.revokeUserRole(revoke(revoked,1)));await(held);var b=job("B",()->service.revokeUserRole(revoke(revoked,1)));blocked("iam_user_role",roots.get("A"));release.countDown();assertThat(result(a)).isInstanceOf(firstCommits?UserRoleView.class:RuntimeException.class);assertThat(result(b)).isEqualTo(revoked);assertThat(grantRow(g.grantId())).isEqualTo(before);distinct(roots,"A","B");
        for(var id:roots.values())assertThat(log(id)).doesNotContain("update iam_user_role");evidence("T09/T10 fresh noop firstCommit="+firstCommits,roots);
    }

    @ParameterizedTest @ValueSource(booleans={true,false})
    void t10_differentGrantCommitsWhileFirstGrantLockIsHeld(boolean firstCommits){
        var r=role("R");var ga=service.grantRole(grant(user("first"),r));var gb=service.grantRole(grant(user("second"),r));var roots=observe();var held=gate();var release=gate();
        doAnswer(c->{c.callRealMethod();if(thread("A")){held.countDown();await(release);if(!firstCommits)throw new IllegalStateException("first rollback");}return null;}).when(userRoles).flush();
        var a=job("A",()->service.revokeUserRole(revoke(ga,0)));await(held);var b=job("B",()->service.revokeUserRole(revoke(gb,0)));assertThat(result(b)).isInstanceOf(UserRoleView.class);assertThat(a.isDone()).isFalse();assertThat(grantRow(gb.grantId()).get("version")).isEqualTo(1L);assertThat(jdbc.queryForObject("select cardinality(pg_blocking_pids(?))",Integer.class,roots.get("B").get("pid"))).isZero();release.countDown();
        assertThat(result(a)).isInstanceOf(firstCommits?UserRoleView.class:RuntimeException.class);assertThat(grantRow(ga.grantId()).get("version")).isEqualTo(firstCommits?1L:0L);distinct(roots,"A","B");evidence("T10 different grant independent firstCommit="+firstCommits,roots);
    }

    @Test void t11_twoActualGrantsShareTheSameRoleConcurrently(){
        var r=role("R");var ca=grant(user("share1"),r);var cb=grant(user("share2"),r);var before=roleRow(r.roleId());var parents=baseline();var roots=observe();var both=new CountDownLatch(2);var release=gate();
        doAnswer(c->{var v=c.callRealMethod();if(thread("A")||thread("B")){assertThat(c.getArgument(1,UUID.class)).isEqualTo(r.roleId());both.countDown();await(release);}return v;}).when(userRoles).lockRoleShare(any(),any());
        var a=job("A",()->service.grantRole(ca));var b=job("B",()->service.grantRole(cb));await(both);distinct(roots,"A","B");for(var id:roots.values()){assertThat(log(id)).contains("from iam_role","for share").doesNotContain("for key share");assertThat(jdbc.queryForObject("select cardinality(pg_blocking_pids(?))",Integer.class,id.get("pid"))).isZero();}
        release.countDown();assertThat(result(a)).isInstanceOf(UserRoleView.class);assertThat(result(b)).isInstanceOf(UserRoleView.class);assertThat(unrevoked(r.roleId())).containsExactlyInAnyOrder(ca.grantId(),cb.grantId());unchanged(parents);assertThat(roleRow(r.roleId())).isEqualTo(before);evidence("T11 same Role actual SHARE coexist",roots);
    }

    @ParameterizedTest @ValueSource(booleans={true,false})
    void t12_twoRoleShareHoldersBlockBareNonKeyWriterButNotOtherRole(boolean grantsCommit){
        var r=role("R");var other=role("Q");var before=roleRow(r.roleId());var otherBefore=roleRow(other.roleId());var ca=grant(user("holder1"),r);var cb=grant(user("holder2"),r);var roots=observe();var both=new CountDownLatch(2);var releaseA=gate();var releaseB=gate();
        doAnswer(c->{var v=c.callRealMethod();if(thread("A")||thread("B")){both.countDown();await(thread("A")?releaseA:releaseB);}return v;}).when(userRoles).lockRoleShare(any(),any());
        doAnswer(c->{c.callRealMethod();if(!grantsCommit&&(thread("A")||thread("B")))throw new IllegalStateException("grant holder rollback");return null;}).when(userRoles).flush();
        var a=job("A",()->service.grantRole(ca));var b=job("B",()->service.grantRole(cb));await(both);
        var bare=job("bare",()->tx(()->{roots.put("bare",physical());jdbc.update("update iam_role set version=version+1,status='INACTIVE' where id=?",r.roleId());throw new IllegalStateException("isolated bare writer rollback");}));var firstWait=blocked("update iam_role set version",null);var reported=Arrays.asList(firstWait.get("blockers").toString().replace("{","").replace("}","").split(","));boolean aFirst=reported.contains(roots.get("A").get("pid").toString());assertThat(reported).containsAnyOf(roots.get("A").get("pid").toString(),roots.get("B").get("pid").toString());
        var unrelated=job("other",()->tx(()->{roots.put("other",physical());jdbc.update("update iam_role set version=version+1 where id=?",other.roleId());throw new IllegalStateException("other writer rollback");}));assertThat(result(unrelated)).isInstanceOf(RuntimeException.class);assertThat(bare.isDone()).isFalse();
        // PostgreSQL may expose only the currently awaited MultiXact member, not every SHARE holder at once.
        (aFirst?releaseA:releaseB).countDown();assertThat(result(aFirst?a:b)).isInstanceOf(grantsCommit?UserRoleView.class:RuntimeException.class);var secondWait=blocked("update iam_role set version",roots.get(aFirst?"B":"A"));assertThat(secondWait.get("pid")).isEqualTo(firstWait.get("pid"));assertThat(bare.isDone()).isFalse();(aFirst?releaseB:releaseA).countDown();assertThat(result(aFirst?b:a)).isInstanceOf(grantsCommit?UserRoleView.class:RuntimeException.class);assertThat(result(bare)).isInstanceOf(RuntimeException.class);
        assertThat(log(roots.get("bare"))).contains("update iam_role set version").doesNotContain("for update","for share");for(String n:List.of("A","B"))assertThat(log(roots.get(n))).contains("for share").doesNotContain("for key share");assertThat(roleRow(r.roleId())).isEqualTo(before);assertThat(roleRow(other.roleId())).isEqualTo(otherBefore);assertThat(unrevoked(r.roleId())).hasSize(grantsCommit?2:0);distinct(roots,"A","B");distinct(roots,"A","bare");distinct(roots,"B","bare");evidence("T12 BOTH SHARE holders must end; grantCommit="+grantsCommit,roots);
    }

    @ParameterizedTest @ValueSource(booleans={true,false})
    void t13_t14_grantFirstThenDeactivateUsesExactPostLockSet(boolean grantCommits){
        var r=role("R");var old=service.grantRole(grant(user("old"),r));var command=grant(user("new"),r);var before=grantRow(old.grantId());var parents=baseline();var roots=observe();var held=gate();var release=gate();var selected=new AtomicReference<List<UUID>>();
        doAnswer(c->{var v=c.callRealMethod();if(thread("D"))selected.set(new ArrayList<>((List<UUID>)v));return v;}).when(userRoles).unrevokedIds(any(),eq(r.roleId()));
        doAnswer(c->{c.callRealMethod();if(thread("G")){held.countDown();await(release);if(!grantCommits)throw new IllegalStateException("grant rollback");}return null;}).when(userRoles).flush();
        var g=job("G",()->service.grantRole(command));await(held);var d=job("D",()->roles.deactivateRole(change(r,r.version())));blocked("iam_role",roots.get("G"));release.countDown();assertThat(result(g)).isInstanceOf(grantCommits?UserRoleView.class:RuntimeException.class);assertThat(result(d)).isInstanceOf(RoleView.class);distinct(roots,"G","D");
        var expected=new HashSet<>(Set.of(old.grantId()));if(grantCommits)expected.add(command.grantId());assertThat(selected.get()).containsExactlyInAnyOrderElementsOf(expected);assertThat(unrevoked(r.roleId())).isEmpty();assertThat(roleRow(r.roleId()).get("status")).isEqualTo("INACTIVE");assertThat(roleRow(r.roleId()).get("version")).isEqualTo(r.version()+1);assertThat(exists(command.grantId())).isEqualTo(grantCommits);
        for(UUID id:expected){assertThat(grantRow(id).get("version")).isEqualTo(1L);assertThat(grantRow(id).get("revoke_kind")).isEqualTo("ROLE_DEACTIVATION");}assertThat(grantRow(old.grantId()).get("grant_reason")).isEqualTo(before.get("grant_reason"));String s=log(roots.get("D"));assertThat(s.indexOf("for update")).isLessThan(s.indexOf("revoked_at is null"));unchanged(parents);evidence("T13/T14 G first commit="+grantCommits+" exact S="+selected.get(),roots);
    }

    @ParameterizedTest @ValueSource(booleans={true,false})
    void t15_t16_deactivateFirstGrantReadsFreshCommittedState(boolean deactivationCommits){
        var r=role("R");var old=service.grantRole(grant(user("old"),r));var command=grant(user("new"),r);var oldRow=grantRow(old.grantId());var before=roleRow(r.roleId());var parents=baseline();var roots=observe();var held=gate();var release=gate();
        doAnswer(c->{c.callRealMethod();if(thread("D")){held.countDown();await(release);if(!deactivationCommits)throw new IllegalStateException("deactivation full flush rollback");}return null;}).when(roleStore).flush();
        var d=job("D",()->roles.deactivateRole(change(r,r.version())));await(held);assertThat(roleRow(r.roleId())).isEqualTo(before);assertThat(grantRow(old.grantId())).isEqualTo(oldRow);var g=job("G",()->service.grantRole(command));blocked("iam_role",roots.get("D"));release.countDown();var rd=result(d);var rg=result(g);distinct(roots,"D","G");
        if(deactivationCommits){assertThat(rd).isInstanceOf(RoleView.class);code(rg,ROLE_INACTIVE);assertThat(exists(command.grantId())).isFalse();assertThat(unrevoked(r.roleId())).isEmpty();assertThat(grantRow(old.grantId()).get("version")).isEqualTo(1L);assertThat(log(roots.get("G"))).doesNotContain("insert into iam_user_role");}else{assertThat(rd).isInstanceOf(RuntimeException.class);assertThat(rg).isInstanceOf(UserRoleView.class);assertThat(roleRow(r.roleId())).isEqualTo(before);assertThat(grantRow(old.grantId())).isEqualTo(oldRow);assertThat(unrevoked(r.roleId())).containsExactlyInAnyOrder(old.grantId(),command.grantId());}unchanged(parents);evidence("T15/T16 D first commit="+deactivationCommits,roots);
    }

    record WholeSet(RoleView role,RoleView other,Set<UUID> targets,Set<UUID> historical,Map<UUID,Map<String,Object>> grants,Map<String,List<Map<String,Object>>> parents,List<Map<String,Object>> roleRows){}
    WholeSet wholeSet(){
        var r=role("R");var q=role("Q");var u1=user("set1");var u2=user("set2");var u3=user("set3");var u4=user("set4");var h1=service.grantRole(grant(u1,r));service.revokeUserRole(new RevokeUserRole(company,h1.grantId(),0L,"old first manual reason"));var h2=service.grantRole(grant(u2,r));service.revokeUserRole(new RevokeUserRole(company,h2.grantId(),0L,"old second manual reason"));users.enableUser(change(u2,0));
        var g1=service.grantRole(grant(u1,r));var g2=service.grantRole(grant(u2,r));var g3=service.grantRole(grant(u3,r));service.grantRole(grant(u1,q));service.grantRole(grant(u4,q));users.disableUser(change(u2,1));terminations.terminateEmployee(end(u3));assertThat(userRow(u2.userId()).get("account_status")).isEqualTo("DISABLED");assertThat(employeeRow(u3.employeeId()).get("employment_status")).isEqualTo("TERMINATED");
        var grants=new LinkedHashMap<UUID,Map<String,Object>>();rows("iam_user_role").forEach(row->grants.put((UUID)row.get("id"),row));return new WholeSet(r,q,Set.of(g1.grantId(),g2.grantId(),g3.grantId()),Set.of(h1.grantId(),h2.grantId()),grants,baseline(),rows("iam_role"));
    }
    void unchangedWhole(WholeSet f){unchanged(f.parents());assertThat(rows("iam_role")).isEqualTo(f.roleRows());assertThat(rows("iam_user_role")).containsExactlyElementsOf(f.grants().values());}
    Set<UUID> changedGrants(WholeSet f){var changed=new HashSet<UUID>();for(var e:f.grants().entrySet())if(!grantRow(e.getKey()).equals(e.getValue()))changed.add(e.getKey());return changed;}
    void committedWhole(WholeSet f){
        assertThat(changedGrants(f)).isEqualTo(f.targets());assertThat(unrevoked(f.role().roleId())).isEmpty();unchanged(f.parents());
        for(var e:f.grants().entrySet()){var after=grantRow(e.getKey());if(!f.targets().contains(e.getKey()))assertThat(after).isEqualTo(e.getValue());else{assertThat(after.get("version")).isEqualTo(((Number)e.getValue().get("version")).longValue()+1);assertThat(after.get("revoke_kind")).isEqualTo("ROLE_DEACTIVATION");assertThat(after.get("revoke_reason")).isEqualTo("state reason");assertThat(after.get("revoked_at")).isEqualTo(java.sql.Timestamp.from(now));for(String immutable:List.of("id","company_id","user_id","role_id","granted_at","grant_reason"))assertThat(after.get(immutable)).isEqualTo(e.getValue().get(immutable));}}
        var expectedRole=new LinkedHashMap<>(f.roleRows().stream().filter(row->row.get("id").equals(f.role().roleId())).findFirst().orElseThrow());expectedRole.put("status","INACTIVE");expectedRole.put("version",f.role().version()+1);expectedRole.put("last_change_kind","DEACTIVATE");expectedRole.put("last_change_reason","state reason");expectedRole.put("updated_at",java.sql.Timestamp.from(now));assertThat(roleRow(f.role().roleId())).isEqualTo(expectedRole);assertThat(roleRow(f.other().roleId())).isEqualTo(f.roleRows().stream().filter(row->row.get("id").equals(f.other().roleId())).findFirst().orElseThrow());
    }
    @Test void t17_exactWholeSetIncludesDisabledAndTerminatedAndPreservesHistoryAndNonTargets(){
        var f=wholeSet();var roots=observe();var selected=new ArrayList<List<UUID>>();var locked=new ArrayList<UUID>();doAnswer(c->{var v=(List<UUID>)c.callRealMethod();selected.add(List.copyOf(v));return v;}).when(userRoles).unrevokedIds(eq(company),eq(f.role().roleId()));doAnswer(c->{locked.add(c.getArgument(1));return c.callRealMethod();}).when(userRoles).lockUserRole(any(),any());
        var result=roles.deactivateRole(change(f.role(),f.role().version()));assertThat(result.version()).isEqualTo(f.role().version()+1);assertThat(selected.getFirst()).containsExactlyInAnyOrderElementsOf(f.targets());assertThat(locked).containsExactlyElementsOf(selected.getFirst());assertThat(locked).containsExactlyElementsOf(jdbc.queryForList("select id from iam_user_role where role_id=? and revoke_kind='ROLE_DEACTIVATION' order by id",UUID.class,f.role().roleId()));committedWhole(f);evidence("T17 exact S="+f.targets()+" historical="+f.historical(),roots);
    }
    @Test void t18_partialFlushConfirmedAbortRestoresWholeSetAndAllParents(){
        var f=wholeSet();var roots=observe();var calls=new AtomicInteger();var partial=new AtomicReference<UUID>();
        doAnswer(c->{if(calls.incrementAndGet()==2){var changed=changedGrants(f);assertThat(changed).hasSize(1);partial.set(changed.iterator().next());assertThat(f.targets()).contains(partial.get());var id=physical();assertThat(log(id).lines().filter(line->line.contains("update iam_user_role set")).count()).isEqualTo(1);for(UUID target:f.targets())if(!target.equals(partial.get()))assertThat(grantRow(target)).isEqualTo(f.grants().get(target));System.out.println("I3 T18 actual partial dirty/flush one="+partial.get()+" S="+f.targets()+" PID/XID="+id);throw new IllegalStateException("confirmed partial set rollback");}return c.callRealMethod();}).when(userRoles).lockUserRole(any(),any());
        assertThatThrownBy(()->roles.deactivateRole(change(f.role(),f.role().version()))).isInstanceOf(RuntimeException.class).hasRootCauseMessage("confirmed partial set rollback");assertThat(calls.get()).isEqualTo(2);assertThat(partial.get()).isNotNull();unchangedWhole(f);evidence("T18 all values restored after partial flush",roots);
    }
    @Test void t19_fullSetAndRoleFlushedThenConfirmedAbortRestoresEveryValue(){
        var f=wholeSet();var roots=observe();var flushed=new AtomicBoolean();doAnswer(c->{c.callRealMethod();committedWhole(f);flushed.set(true);var id=physical();assertThat(log(id).lines().filter(line->line.contains("update iam_user_role set")).count()).isEqualTo(f.targets().size());assertThat(log(id).lines().filter(line->line.contains("update iam_role set")).count()).isEqualTo(1);System.out.println("I3 T19 full grants and Role flushed S="+f.targets()+" PID/XID="+id);throw new IllegalStateException("confirmed full set rollback");}).when(roleStore).flush();
        assertThatThrownBy(()->roles.deactivateRole(change(f.role(),f.role().version()))).isInstanceOf(RuntimeException.class).hasRootCauseMessage("confirmed full set rollback");assertThat(flushed).isTrue();unchangedWhole(f);evidence("T19 full set confirmed abort restores exact baseline",roots);
    }

    @ParameterizedTest @CsvSource({"true,true","true,false","false,true","false,false"})
    void t20_activationAndInactiveGrantBothOrdersCommitOrRollback(boolean activationFirst,boolean activationCommits){
        var u=user("activate");var active=role("R");var old=service.grantRole(grant(u,active));var r=roles.deactivateRole(change(active,active.version()));var oldRow=grantRow(old.grantId());var before=roleRow(r.roleId());var command=grant(u,r);var roots=observe();var held=gate();var release=gate();
        doAnswer(c->{c.callRealMethod();if(thread("A")){if(activationFirst){held.countDown();await(release);}if(!activationCommits)throw new IllegalStateException("activation rollback");}return null;}).when(roleStore).flush();doAnswer(c->{var v=c.callRealMethod();if(thread("G")&&!activationFirst){held.countDown();await(release);}return v;}).when(userRoles).lockRoleShare(any(),any());
        Future<Object> a,g;if(activationFirst){a=job("A",()->roles.activateRole(change(r,r.version())));await(held);g=job("G",()->service.grantRole(command));blocked("iam_role",roots.get("A"));}else{g=job("G",()->service.grantRole(command));await(held);a=job("A",()->roles.activateRole(change(r,r.version())));blocked("iam_role",roots.get("G"));}release.countDown();var ra=result(a);var rg=result(g);distinct(roots,"A","G");assertThat(ra).isInstanceOf(activationCommits?RoleView.class:RuntimeException.class);
        if(activationFirst&&activationCommits){assertThat(rg).isInstanceOf(UserRoleView.class);assertThat(unrevoked(r.roleId())).containsExactly(command.grantId());}else{code(rg,ROLE_INACTIVE);assertThat(exists(command.grantId())).isFalse();assertThat(log(roots.get("G"))).doesNotContain("insert into iam_user_role");assertThat(unrevoked(r.roleId())).isEmpty();}if(activationCommits){assertThat(roleRow(r.roleId()).get("status")).isEqualTo("ACTIVE");assertThat(roleRow(r.roleId()).get("version")).isEqualTo(r.version()+1);}else assertThat(roleRow(r.roleId())).isEqualTo(before);assertThat(grantRow(old.grantId())).isEqualTo(oldRow);evidence("T20 activationFirst="+activationFirst+" activationCommit="+activationCommits,roots);
    }
    @ParameterizedTest @ValueSource(booleans={true,false})
    void t20_activeActivationNoopStillLocksButNeverRestoresPastGrant(boolean noopCommits){
        var u=user("noopactivate");var r=role("R");var old=service.grantRole(grant(u,r));service.revokeUserRole(revoke(old,0));var oldRow=grantRow(old.grantId());var before=roleRow(r.roleId());var command=grant(u,r);var roots=observe();var held=gate();var release=gate();
        doAnswer(c->{var v=c.callRealMethod();if(thread("A")){held.countDown();await(release);if(!noopCommits)throw new IllegalStateException("noop activation rollback");}return v;}).when(roleStore).lockRole(any(),any());var a=job("A",()->roles.activateRole(change(r,r.version())));await(held);var g=job("G",()->service.grantRole(command));blocked("iam_role",roots.get("A"));release.countDown();assertThat(result(a)).isInstanceOf(noopCommits?RoleView.class:RuntimeException.class);assertThat(result(g)).isInstanceOf(UserRoleView.class);assertThat(roleRow(r.roleId())).isEqualTo(before);assertThat(grantRow(old.grantId())).isEqualTo(oldRow);assertThat(unrevoked(r.roleId())).containsExactly(command.grantId());assertThat(log(roots.get("A"))).doesNotContain("update iam_role");evidence("T20 active noop commit="+noopCommits,roots);
    }
    @Test void t20_zeroGrantRoleDeactivatesNormally(){var r=role("EMPTY");var before=baseline();var value=roles.deactivateRole(change(r,r.version()));assertThat(value.status()).isEqualTo("INACTIVE");assertThat(value.version()).isEqualTo(r.version()+1);assertThat(unrevoked(r.roleId())).isEmpty();unchanged(before);}

    @Test void t22_lockWithoutUpdateAllowsImmediateCommittedDuplicateDecision(){
        var u=user("preflush");var r=role("R");var old=service.grantRole(grant(u,r));var command=grant(u,r);var before=grantRow(old.grantId());var roots=observe();var held=gate();var release=gate();doAnswer(c->{var v=c.callRealMethod();if(thread("R")){held.countDown();await(release);}return v;}).when(userRoles).lockUserRole(any(),any());
        var revoke=job("R",()->service.revokeUserRole(revoke(old,0)));await(held);assertThat(log(roots.get("R"))).doesNotContain("update iam_user_role");var g=job("G",()->service.grantRole(command));unique(result(g));assertThat(revoke.isDone()).isFalse();assertThat(grantRow(old.grantId())).isEqualTo(before);assertThat(exists(command.grantId())).isFalse();release.countDown();assertThat(result(revoke)).isInstanceOf(UserRoleView.class);assertThat(grantRow(old.grantId()).get("version")).isEqualTo(1L);assertThat(unrevoked(r.roleId())).isEmpty();evidence("T22 grant duplicate before old revoke UPDATE",roots);
    }
    @ParameterizedTest @ValueSource(booleans={true,false})
    void t22_flushedRevokeForcesUniqueRecheckAfterCommitOrRollback(boolean revokeCommits){
        var u=user("postflush");var r=role("R");var old=service.grantRole(grant(u,r));var command=grant(u,r);var before=grantRow(old.grantId());var roots=observe();var held=gate();var release=gate();doAnswer(c->{c.callRealMethod();if(thread("R")){held.countDown();await(release);if(!revokeCommits)throw new IllegalStateException("revoke rollback");}return null;}).when(userRoles).flush();
        var revoke=job("R",()->service.revokeUserRole(revoke(old,0)));await(held);var g=job("G",()->service.grantRole(command));blocked("insert into iam_user_role",roots.get("R"));assertThat(g.isDone()).isFalse();release.countDown();var rr=result(revoke);var rg=result(g);
        if(revokeCommits){assertThat(rr).isInstanceOf(UserRoleView.class);assertThat(rg).isInstanceOf(UserRoleView.class);assertThat(unrevoked(r.roleId())).containsExactly(command.grantId());assertThat(grantRow(old.grantId()).get("version")).isEqualTo(1L);assertThat(grantRow(old.grantId()).get("revoke_kind")).isEqualTo("MANUAL");}else{assertThat(rr).isInstanceOf(RuntimeException.class);unique(rg);assertThat(grantRow(old.grantId())).isEqualTo(before);assertThat(exists(command.grantId())).isFalse();assertThat(unrevoked(r.roleId())).containsExactly(old.grantId());}distinct(roots,"R","G");evidence("T22 UNIQUE recheck after flushed revoke commit="+revokeCommits,roots);
    }
    @ParameterizedTest @CsvSource({"true,true","true,false","false,true","false,false"})
    void t24_revokeAndDeactivateBothOrdersCommitOrRollback(boolean revokeFirst,boolean firstCommits){
        var r=role("R");var g=service.grantRole(grant(user("target"),r));var other=service.grantRole(grant(user("other"),r));var before=grantRow(g.grantId());var otherBefore=grantRow(other.grantId());var roleBefore=roleRow(r.roleId());var parents=baseline();var roots=observe();var held=gate();var release=gate();var selected=new AtomicReference<List<UUID>>();
        doAnswer(c->{var v=c.callRealMethod();if(thread("D"))selected.set(new ArrayList<>((List<UUID>)v));return v;}).when(userRoles).unrevokedIds(any(),eq(r.roleId()));doAnswer(c->{c.callRealMethod();if(thread("R")&&revokeFirst){held.countDown();await(release);if(!firstCommits)throw new IllegalStateException("first revoke rollback");}return null;}).when(userRoles).flush();doAnswer(c->{c.callRealMethod();if(thread("D")&&!revokeFirst){held.countDown();await(release);if(!firstCommits)throw new IllegalStateException("first deactivation rollback");}return null;}).when(roleStore).flush();
        Future<Object> revoke,d;if(revokeFirst){revoke=job("R",()->service.revokeUserRole(revoke(g,0)));await(held);d=job("D",()->roles.deactivateRole(change(r,r.version())));blocked("iam_role",roots.get("R"));}else{d=job("D",()->roles.deactivateRole(change(r,r.version())));await(held);revoke=job("R",()->service.revokeUserRole(revoke(g,0)));blocked("iam_role",roots.get("D"));}release.countDown();var rr=result(revoke);var rd=result(d);distinct(roots,"R","D");
        if(revokeFirst){assertThat(rr).isInstanceOf(firstCommits?UserRoleView.class:RuntimeException.class);assertThat(rd).isInstanceOf(RoleView.class);assertThat(selected.get()).containsExactlyInAnyOrderElementsOf(firstCommits?Set.of(other.grantId()):Set.of(g.grantId(),other.grantId()));assertThat(grantRow(g.grantId()).get("revoke_kind")).isEqualTo(firstCommits?"MANUAL":"ROLE_DEACTIVATION");assertThat(unrevoked(r.roleId())).isEmpty();}
        else if(firstCommits){assertThat(rd).isInstanceOf(RoleView.class);code(rr,VERSION_CONFLICT);assertThat(grantRow(g.grantId()).get("revoke_kind")).isEqualTo("ROLE_DEACTIVATION");assertThat(unrevoked(r.roleId())).isEmpty();}
        else{assertThat(rd).isInstanceOf(RuntimeException.class);assertThat(rr).isInstanceOf(UserRoleView.class);assertThat(roleRow(r.roleId())).isEqualTo(roleBefore);assertThat(grantRow(other.grantId())).isEqualTo(otherBefore);assertThat(grantRow(g.grantId()).get("revoke_kind")).isEqualTo("MANUAL");assertThat(unrevoked(r.roleId())).containsExactly(other.grantId());}
        assertThat(grantRow(g.grantId()).get("version")).isEqualTo(1L);for(String f:List.of("id","company_id","role_id","user_id","granted_at","grant_reason"))assertThat(grantRow(g.grantId()).get(f)).isEqualTo(before.get(f));unchanged(parents);evidence("T24 revokeFirst="+revokeFirst+" firstCommit="+firstCommits+" S="+selected.get(),roots);
    }
}
