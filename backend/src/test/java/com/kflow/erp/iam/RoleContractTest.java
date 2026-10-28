package com.kflow.erp.iam;
import com.kflow.erp.iam.api.IamFailure;
import com.kflow.erp.iam.application.*;
import com.kflow.erp.iam.domain.*;
import com.kflow.erp.iam.infrastructure.JpaRoleStore;
import jakarta.persistence.OptimisticLockException;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Modifier;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class RoleContractTest {
    @Test void t02_asciiBeforeRootUppercase() {
        Locale old=Locale.getDefault();try{Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            for(String value:List.of("i","a".repeat(32),"001","A-B","A_B"))assertThat(RoleValues.code("  "+value+"  ")).isEqualTo(value.toUpperCase(Locale.ROOT));
            for(String bad:Arrays.asList(null,"","a".repeat(33),"\tA","A\n","\u00a0A","Ａ","한글","a.b","a@b","a b","_AB","-AB","İ"))assertThatThrownBy(()->RoleValues.code(bad)).isInstanceOf(IamFailure.class);
        }finally{Locale.setDefault(old);}
    }
    @Test void t06_nameCodepointsAndExactNormalization() {
        for(String value:List.of("x","x".repeat(100),"😀".repeat(100),"Sales  Team","sales  team"))assertThat(RoleValues.name("  "+value+"  ")).isEqualTo(value);
        for(String bad:Arrays.asList(null,""," ","\u00a0","\u3000","a\t","a\n","a\u0085","\uD800","\uDC00","😀".repeat(101)))assertThatThrownBy(()->RoleValues.name(bad)).isInstanceOf(IamFailure.class);
    }
    @Test void t07_reasonCodepoints() {
        for(String valid:List.of("x","x".repeat(500),"😀".repeat(500)))assertThat(IamValues.reason("  "+valid+"  ")).isEqualTo(valid);
        for(String bad:Arrays.asList(null,""," ","\u00a0","\u3000","x\t","x\n","x\u0085","\uD800","\uDC00","😀".repeat(501)))assertThatThrownBy(()->IamValues.reason(bad)).isInstanceOf(IamFailure.class);
    }
    @Test void t25_t28_roleOnlyPublicSurfaceAndNoGrantFacts() {
        assertThat(Arrays.stream(RoleService.class.getDeclaredMethods()).filter(m->Modifier.isPublic(m.getModifiers())).map(java.lang.reflect.Method::getName)).containsExactlyInAnyOrder("createRole","renameRole","activateRole","deactivateRole");
        assertThat(Arrays.stream(RoleQueryService.class.getDeclaredMethods()).filter(m->Modifier.isPublic(m.getModifiers())).map(java.lang.reflect.Method::getName)).containsExactlyInAnyOrder("findRole","findRoleByCode","listRoles");
        assertThat(Arrays.stream(Role.class.getDeclaredFields()).map(java.lang.reflect.Field::getName)).containsExactlyInAnyOrder("id","companyId","code","name","status","version","creationReason","lastChangeReason","lastChangeKind","createdAt","updatedAt");
        assertThat(Arrays.stream(RoleListItem.class.getRecordComponents()).map(java.lang.reflect.RecordComponent::getName)).containsExactly("roleId","code","name","status","version");
        assertThat(Arrays.stream(CreateRole.class.getRecordComponents()).map(java.lang.reflect.RecordComponent::getName)).containsExactly("companyId","code","name","reason");
    }
    @Test void t20_optimisticAndUnknownClassification() {
        var store=new JpaRoleStore(null,null,5000);
        assertThat(store.translate(new OptimisticLockException())).isInstanceOfSatisfying(IamFailure.class,f->assertThat(f.code()).isEqualTo(IamFailure.Code.VERSION_CONFLICT));
        var unknown=new IllegalStateException("unknown technical failure");assertThat(store.translate(unknown)).isSameAs(unknown);
    }
}
