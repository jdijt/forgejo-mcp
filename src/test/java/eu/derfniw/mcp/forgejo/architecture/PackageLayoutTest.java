package eu.derfniw.mcp.forgejo.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * Enforces the package-layout rule from CLAUDE.md: every class lives in a leaf package. A package
 * may either contain classes OR contain sub-packages, never both.
 * <p>
 * This is based on the "Flatten components pattern" from "Software Architecture: The Hard Parts".
 */
class PackageLayoutTest {

    private static final String ROOT_PACKAGE = "eu.derfniw.mcp.forgejo";

    @Test
    void classesOnlyLiveInLeafPackages() {
        JavaClasses classesUnderRoot = new ClassFileImporter().importPackages(ROOT_PACKAGE);

        Set<String> packagesWithClasses =
                classesUnderRoot.stream().map(JavaClass::getPackageName).collect(Collectors.toSet());

        classes()
                .that()
                .resideInAPackage(ROOT_PACKAGE + "..")
                .should(new ArchCondition<>("reside in a leaf package (no sub-packages that also hold classes)") {
                    @Override
                    public void check(JavaClass item, ConditionEvents events) {
                        String pkg = item.getPackageName();
                        boolean hasChildPackageWithClasses = packagesWithClasses.stream()
                                .anyMatch(other -> !other.equals(pkg) && other.startsWith(pkg + "."));
                        if (hasChildPackageWithClasses) {
                            events.add(SimpleConditionEvent.violated(
                                    item,
                                    item.getName()
                                            + " is in package '"
                                            + pkg
                                            + "' which also contains sub-packages with classes; move it to a"
                                            + " leaf package"));
                        }
                    }
                })
                .check(classesUnderRoot);
    }
}
