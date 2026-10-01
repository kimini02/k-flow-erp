package com.kflow.erp;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

class LayerArchitectureTest {
    private final com.tngtech.archunit.core.domain.JavaClasses classes = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.kflow.erp");

    @Test
    void domainDoesNotDependOnApplicationInfrastructureOrWeb() {
        noClasses().that().resideInAPackage("..domain..")
                .should().dependOnClassesThat().resideInAnyPackage("..application..", "..infrastructure..", "..web..")
                .allowEmptyShould(true).check(classes);
    }

    @Test
    void applicationDoesNotDependOnInfrastructureOrWeb() {
        noClasses().that().resideInAPackage("..application..")
                .should().dependOnClassesThat().resideInAnyPackage("..infrastructure..", "..web..")
                .allowEmptyShould(true).check(classes);
    }

    @Test
    void publishedContractsDoNotExposeImplementationLayers() {
        noClasses().that().resideInAPackage("..api..")
                .should().dependOnClassesThat()
                .resideInAnyPackage("..domain..", "..application..", "..infrastructure..", "..web..")
                .allowEmptyShould(true).check(classes);
    }
}
