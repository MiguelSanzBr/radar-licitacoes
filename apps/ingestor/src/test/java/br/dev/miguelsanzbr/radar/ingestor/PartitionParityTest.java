package br.dev.miguelsanzbr.radar.ingestor;

import static org.assertj.core.api.Assertions.assertThat;

import br.dev.miguelsanzbr.radar.RadarApplication;
import br.dev.miguelsanzbr.radar.searchapi.SearchApiApplication;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.TypeExcludeFilter;
import org.springframework.core.type.classreading.MetadataReader;
import org.springframework.core.type.classreading.MetadataReaderFactory;

/**
 * The split loses nothing: every bean of the monolith is in the ingestor or in the search-api. A
 * module bean left out of both scans would fail here. The search-api is on the test classpath only
 * for this test.
 */
class PartitionParityTest {

  /**
   * The monolith finds its main class and the three configurations of the root package by scanning
   * (bean names are the simple class names); the apps take the configurations by {@code @Import}
   * (the name is the full class name) and have a main class of their own. Same classes, other
   * names.
   */
  private static final Set<String> ALLOWED_ONLY_IN_MONOLITH =
      Set.of(
          "radarApplication",
          "radarConfiguration",
          "primaryPersistenceConfiguration",
          "redisClientConfiguration");

  /** The two apps' own main classes and the imported configurations, by full class name. */
  private static final Set<String> ALLOWED_ONLY_IN_APPS =
      Set.of(
          "br.dev.miguelsanzbr.radar.RadarConfiguration",
          "br.dev.miguelsanzbr.radar.PrimaryPersistenceConfiguration",
          "br.dev.miguelsanzbr.radar.RedisClientConfiguration",
          "br.dev.miguelsanzbr.radar.identity.SecurityConfig",
          "ingestorApplication",
          "searchApiApplication");

  @Test
  void theMonolithBeansAreTheUnionOfTheTwoApps() {
    var monolith = beanNames(RadarApplication.class, new WithoutTheApps());
    var apps = beanNames(IngestorApplication.class, null, "ingestor");
    apps.addAll(beanNames(SearchApiApplication.class, null, "search-api"));

    var missing = new TreeSet<>(monolith);
    missing.removeAll(apps);
    missing.removeAll(ALLOWED_ONLY_IN_MONOLITH);
    var extra = new TreeSet<>(apps);
    extra.removeAll(monolith);
    extra.removeAll(ALLOWED_ONLY_IN_APPS);

    assertThat(missing).as("in the monolith, in neither app").isEmpty();
    assertThat(extra).as("in an app, not in the monolith").isEmpty();
  }

  private static Set<String> beanNames(
      Class<?> application, TypeExcludeFilter filter, String... profiles) {
    var builder = new SpringApplicationBuilder(application).profiles(profiles);
    if (filter != null) {
      builder.initializers(
          context -> context.getBeanFactory().registerSingleton("withoutTheApps", filter));
    }
    try (var context = builder.run(IngestorProductionContextTest.WITHOUT_INFRASTRUCTURE)) {
      return new TreeSet<>(Set.of(context.getBeanDefinitionNames()));
    }
  }

  /** The scan of the monolith, on this classpath, would also find the two apps' own classes. */
  private static final class WithoutTheApps extends TypeExcludeFilter {

    @Override
    public boolean match(MetadataReader reader, MetadataReaderFactory factory) {
      var name = reader.getClassMetadata().getClassName();
      return name.equals(IngestorApplication.class.getName())
          || name.equals(SearchApiApplication.class.getName());
    }
  }
}
