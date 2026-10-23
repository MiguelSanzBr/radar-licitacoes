package br.dev.miguelsanzbr.radar.search.adapter.config;

import br.dev.miguelsanzbr.radar.search.application.DetailReadModel;
import br.dev.miguelsanzbr.radar.search.application.DetailUseCase;
import br.dev.miguelsanzbr.radar.search.application.OrganReadModel;
import br.dev.miguelsanzbr.radar.search.application.OrganSearchUseCase;
import br.dev.miguelsanzbr.radar.search.application.SearchReadModel;
import br.dev.miguelsanzbr.radar.search.application.SearchUseCase;
import br.dev.miguelsanzbr.radar.search.application.StatsReadModel;
import br.dev.miguelsanzbr.radar.search.application.StatsUseCase;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wires the search use cases; the application layer has no Spring types. */
@Configuration(proxyBeanMethods = false)
class SearchQueryConfiguration {

  @Bean
  SearchUseCase searchUseCase(SearchReadModel readModel) {
    return new SearchUseCase(readModel);
  }

  @Bean
  OrganSearchUseCase organSearchUseCase(OrganReadModel readModel) {
    return new OrganSearchUseCase(readModel);
  }

  @Bean
  StatsUseCase statsUseCase(StatsReadModel readModel) {
    return new StatsUseCase(readModel);
  }

  @Bean
  DetailUseCase detailUseCase(DetailReadModel readModel) {
    return new DetailUseCase(readModel);
  }
}
