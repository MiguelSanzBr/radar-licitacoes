package br.dev.miguelsanzbr.radar.architecture;

import static com.tngtech.archunit.base.DescribedPredicate.not;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.assignableTo;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.equivalentTo;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAnyPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;

import com.tngtech.archunit.core.domain.JavaAccess;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.properties.HasName;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.CompositeArchRule;
import java.net.URL;
import java.net.URLConnection;
import org.springframework.transaction.support.TransactionOperations;

/** The hexagonal and context-isolation rules, as functions so fixtures can exercise them. */
final class ArchitectureRules {

  private static final String SPRING_TRANSACTIONAL =
      "org.springframework.transaction.annotation.Transactional";
  private static final String JAKARTA_TRANSACTIONAL = "jakarta.transaction.Transactional";

  private ArchitectureRules() {}

  static ArchRule domainIsFrameworkFree() {
    return noClasses()
        .that()
        .resideInAPackage("..domain..")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage(
            "org.springframework..",
            "jakarta.persistence..",
            "org.hibernate..",
            "com.fasterxml.jackson..",
            "tools.jackson..",
            "org.jooq..",
            "org.apache.kafka..")
        .because("the domain is plain Java: no framework, mapping, JSON, SQL or broker types");
  }

  /**
   * The one Spring abstraction the use cases take is TransactionOperations (a port in effect).
   * Domain and application stay generic packages on purpose: which context a class may reach is
   * Modulith's job; only the shared kernel is scoped to the root.
   */
  static ArchRule applicationUsesOnlyDomainAndPorts(String root) {
    return classes()
        .that()
        .resideInAPackage("..application..")
        .should()
        .onlyDependOnClassesThat(
            resideInAnyPackage("..domain..", "..application..", root + ".shared..", "java..")
                .or(equivalentTo(TransactionOperations.class)))
        .because("use cases depend on the domain and their own ports; adapters implement them");
  }

  static ArchRule coreNeverDependsOnAdapters() {
    return noClasses()
        .that()
        .resideInAnyPackage("..domain..", "..application..")
        .should()
        .dependOnClassesThat()
        .resideInAPackage("..adapter..")
        .because("dependencies point inward: adapters depend on the core, never the reverse");
  }

  static ArchRule searchIsIndependentOfIngestAndOutbox(String root) {
    return noClasses()
        .that()
        .resideInAPackage(root + ".search..")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage(root + ".ingest..", root + ".outbox..")
        .because("search is the read side: it learns of changes by events, never by calling in");
  }

  static ArchRule ingestIsIndependentOfSearch(String root) {
    return noClasses()
        .that()
        .resideInAPackage(root + ".ingest..")
        .should()
        .dependOnClassesThat()
        .resideInAPackage(root + ".search..")
        .because("ingest is the write side and does not know who reads it");
  }

  static ArchRule ingestDoesNotKnowTogglz(String root) {
    return noClasses()
        .that()
        .resideInAPackage(root + ".ingest..")
        .should()
        .dependOnClassesThat()
        .resideInAPackage("org.togglz..")
        .because("ingest reads flags through its own port; only ops knows the flag library");
  }

  static ArchRule inboundAdaptersAreNotTransactional() {
    var because = "the transaction boundary belongs to the use case, not to the inbound adapter";
    var classes =
        noClasses()
            .that()
            .resideInAPackage("..adapter.in..")
            .should()
            .beAnnotatedWith(SPRING_TRANSACTIONAL)
            .orShould()
            .beAnnotatedWith(JAKARTA_TRANSACTIONAL)
            .because(because);
    var methods =
        noMethods()
            .that()
            .areDeclaredInClassesThat()
            .resideInAPackage("..adapter.in..")
            .should()
            .beAnnotatedWith(SPRING_TRANSACTIONAL)
            .orShould()
            .beAnnotatedWith(JAKARTA_TRANSACTIONAL)
            .because(because);
    return CompositeArchRule.of(classes).and(methods);
  }

  /**
   * The one outbound HTTP client of the application code is the PNCP gateway, whose address comes
   * from configuration. Nothing else opens a connection, so no request field can pick a host (OWASP
   * A10). Libraries that fetch from addresses of the configuration (the identity provider's keys,
   * the schema registry) are not application code and are not covered.
   */
  static ArchRule onlyThePncpGatewayCallsOut(String root) {
    return noClasses()
        .that()
        .resideOutsideOfPackage(root + ".ingest.adapter.out.pncp..")
        .should()
        .dependOnClassesThat(
            JavaClass.Predicates.resideInAnyPackage(
                    "java.net.http..",
                    "org.springframework.web.client..",
                    "org.springframework.web.reactive.function.client..",
                    "org.apache.hc..",
                    "okhttp3..")
                // exception types (the registry client's timeouts are told apart by type) are no
                // client
                .and(not(assignableTo(Throwable.class)))
                .or(assignableTo(URLConnection.class)))
        .orShould()
        .callMethodWhere(
            JavaAccess.Predicates.targetOwner(equivalentTo(URL.class))
                .and(
                    JavaAccess.Predicates.target(
                        HasName.Predicates.nameMatching("openConnection|openStream|getContent"))))
        .because("a host taken from a request would make the server fetch for the caller");
  }
}
