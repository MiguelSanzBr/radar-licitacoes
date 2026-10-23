package br.dev.miguelsanzbr.radar.architecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/** The architecture rules applied to the production code. */
@AnalyzeClasses(
    packages = ArchitectureTest.ROOT,
    importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

  static final String ROOT = "br.dev.miguelsanzbr.radar";

  @ArchTest
  static final ArchRule domain_does_not_depend_on_frameworks =
      ArchitectureRules.domainIsFrameworkFree();

  @ArchTest
  static final ArchRule application_depends_only_on_domain_and_its_ports =
      ArchitectureRules.applicationUsesOnlyDomainAndPorts(ROOT);

  @ArchTest
  static final ArchRule domain_and_application_never_depend_on_adapters =
      ArchitectureRules.coreNeverDependsOnAdapters();

  @ArchTest
  static final ArchRule search_does_not_depend_on_ingest_or_outbox =
      ArchitectureRules.searchIsIndependentOfIngestAndOutbox(ROOT);

  @ArchTest
  static final ArchRule ingest_does_not_depend_on_search =
      ArchitectureRules.ingestIsIndependentOfSearch(ROOT);

  @ArchTest
  static final ArchRule ingest_does_not_know_the_flag_library =
      ArchitectureRules.ingestDoesNotKnowTogglz(ROOT);

  @ArchTest
  static final ArchRule inbound_adapters_have_no_transaction_boundary =
      ArchitectureRules.inboundAdaptersAreNotTransactional();
}
