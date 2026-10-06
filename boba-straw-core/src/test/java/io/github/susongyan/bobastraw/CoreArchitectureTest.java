package io.github.susongyan.bobastraw;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.io.DataInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Iterator;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.junit.jupiter.api.Assertions.assertEquals;

class CoreArchitectureTest {
    @Test
    void runtimeClassesDependOnlyOnTheJdkAndCore() {
        noClasses().should().dependOnClassesThat().resideOutsideOfPackages(
            "java..", "javax..", "io.github.susongyan.bobastraw..")
            .check(new ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("io.github.susongyan.bobastraw"));
    }

    @Test
    void runtimeBytecodeTargetsJava8() throws Exception {
        Path classes = Paths.get(BobaStrawClient.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        try (Stream<Path> paths = Files.walk(classes)) {
            Iterator<Path> iterator = paths.filter(path -> path.toString().endsWith(".class")).iterator();
            while (iterator.hasNext()) {
                Path path = iterator.next();
                try (DataInputStream input = new DataInputStream(Files.newInputStream(path))) {
                    assertEquals(0xCAFEBABE, input.readInt(), path.toString());
                    input.readUnsignedShort();
                    assertEquals(52, input.readUnsignedShort(), path.toString());
                }
            }
        }
    }
}
