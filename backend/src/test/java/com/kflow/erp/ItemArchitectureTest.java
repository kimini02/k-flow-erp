package com.kflow.erp;

import com.tngtech.archunit.core.importer.*;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

class ItemArchitectureTest {
    final com.tngtech.archunit.core.domain.JavaClasses classes=new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS).importPackages("com.kflow.erp");
    @Test void providerGuardsHaveNoProductionConsumerUntilProtectedCommandsAreImplemented() {
        for(String port:List.of("ItemBusinessUseGuard","ItemFulfillmentGuard","ItemReferenceGuard"))
            noClasses().that().resideOutsideOfPackage("..masterdata..")
                    .should().dependOnClassesThat().haveFullyQualifiedName("com.kflow.erp.masterdata.api."+port)
                    .allowEmptyShould(true).check(classes);
        noClasses().that().resideOutsideOfPackage("..masterdata..")
                .should().dependOnClassesThat().resideInAnyPackage("..masterdata.application..","..masterdata.domain..","..masterdata.infrastructure..")
                .allowEmptyShould(true).check(classes);
    }
    @Test void itemHasNoHttpEntryAndSqlIsOwnerOnly() throws Exception {
        assertThat(classes.stream().filter(c->c.getPackageName().startsWith("com.kflow.erp.masterdata.web") && !c.getSimpleName().equals("package-info")).toList()).isEmpty();
        try(var paths=Files.walk(Path.of("src/main/java"))) {
            for(var path:paths.filter(p->p.toString().endsWith(".java")).toList())
                if(Files.readString(path).contains("masterdata_item"))
                    assertThat(path.toString()).contains("/masterdata/infrastructure/");
        }
    }
}
