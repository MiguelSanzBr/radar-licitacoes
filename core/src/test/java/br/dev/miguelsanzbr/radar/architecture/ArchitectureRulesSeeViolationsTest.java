package br.dev.miguelsanzbr.radar.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

/**
 * Each rule must fail on a class that breaks it and stay quiet on one that complies. The fixtures
 * live under {@code architecture.fixture}, a package the real-code rules never import.
 */
class ArchitectureRulesSeeViolationsTest {

  private static final String FIXTURES = "br.dev.miguelsanzbr.radar.architecture.fixture";

  private static final JavaClasses FIXTURE_CLASSES =
      new ClassFileImporter().importPackages(FIXTURES);

  private static String failures(ArchRule rule) {
    return String.join("\n", rule.evaluate(FIXTURE_CLASSES).getFailureReport().getDetails());
  }

  @Test
  void domainRuleFlagsEveryForbiddenFamilyAndNothingElse() {
    var failures = failures(ArchitectureRules.domainIsFrameworkFree());

    assertThat(failures)
        .contains(
            "org.springframework.context.ApplicationContext",
            "jakarta.persistence.EntityManager",
            "org.hibernate.Session",
            "com.fasterxml.jackson.annotation.JsonProperty",
            "tools.jackson.databind.json.JsonMapper",
            "org.jooq.DSLContext",
            "org.apache.kafka.clients.producer.Producer")
        .doesNotContain("PureDomain");
  }

  @Test
  void applicationRuleFlagsFrameworksOutsideTheAllowList() {
    var failures = failures(ArchitectureRules.applicationUsesOnlyDomainAndPorts(FIXTURES));

    assertThat(failures)
        .contains("UsesAForbiddenFramework", "JdbcClient", "UsesAnotherSharedPackage")
        .doesNotContain("UsesOnlyAllowedTypes");
  }

  @Test
  void adapterRuleFlagsDomainAndApplicationClassesThatReachAnAdapter() {
    var failures = failures(ArchitectureRules.coreNeverDependsOnAdapters());

    assertThat(failures)
        .contains("fixture.domain.ReachesAnAdapter", "fixture.application.ReachesAnAdapter")
        .doesNotContain("PureDomain", "UsesOnlyAllowedTypes");
  }

  @Test
  void searchRuleFlagsIngestAndOutboxButNotSearchAlone() {
    var failures = failures(ArchitectureRules.searchIsIndependentOfIngestAndOutbox(FIXTURES));

    assertThat(failures)
        .contains("SearchReadsIngest", "SearchReadsOutbox")
        .doesNotContain("SearchOnItsOwn", "IngestReadsSearch");
  }

  @Test
  void ingestRuleFlagsSearchButNotIngestAlone() {
    var failures = failures(ArchitectureRules.ingestIsIndependentOfSearch(FIXTURES));

    assertThat(failures).contains("IngestReadsSearch").doesNotContain("SearchReadsIngest");
  }

  @Test
  void togglzRuleFlagsIngestClassesThatTouchTheFlagLibrary() {
    var failures = failures(ArchitectureRules.ingestDoesNotKnowTogglz(FIXTURES));

    assertThat(failures).contains("IngestReadsTogglz").doesNotContain("IngestInternals");
  }

  @Test
  void transactionRuleFlagsSpringAndJakartaOnClassesAndMethodsOfInboundAdaptersOnly() {
    var failures = failures(ArchitectureRules.inboundAdaptersAreNotTransactional());

    assertThat(failures)
        .contains(
            "adapter.in.TransactionalController>",
            "adapter.in.SpringTransactionalMethod.handle()>",
            "adapter.in.JakartaTransactionalController>",
            "adapter.in.JakartaTransactionalMethod.handle()>")
        .doesNotContain("PlainController", "TransactionalOutboundAdapter");
  }

  @Test
  void outboundRuleFlagsEveryWayToOpenAConnectionOutsideThePncpGateway() {
    var failures = failures(ArchitectureRules.onlyThePncpGatewayCallsOut(FIXTURES));

    assertThat(failures)
        .contains(
            "UsesJdkHttpClient",
            "UsesRestClient",
            "UsesRestTemplate",
            "OpensAUrlContent",
            "UsesAnyUrlConnection",
            "UsesHttpUrlConnection",
            "OpensAUrlConnection",
            "OpensAUrlStream")
        .doesNotContain("PncpGatewayFixture", "KnowsATimeoutException", "SearchOnItsOwn");
  }
}
