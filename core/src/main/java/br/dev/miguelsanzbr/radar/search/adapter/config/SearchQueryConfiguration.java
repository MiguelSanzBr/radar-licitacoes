package br.dev.miguelsanzbr.radar.search.adapter.config;

import br.dev.miguelsanzbr.radar.search.application.SearchReadModel;
import br.dev.miguelsanzbr.radar.search.application.SearchUseCase;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wires the search use case; the application layer has no Spring types. */
@Configuration(proxyBeanMethods = false)
class SearchQueryConfiguration {

  @Bean
  SearchUseCase searchUseCase(SearchReadModel readModel) {
    return new SearchUseCase(readModel);
  }
}
