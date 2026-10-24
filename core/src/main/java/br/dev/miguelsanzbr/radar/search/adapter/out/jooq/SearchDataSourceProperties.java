package br.dev.miguelsanzbr.radar.search.adapter.out.jooq;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Connection of the read side; a blank {@code url} means the same database as the write side.
 * {@code maximumPoolSize} sizes the search pool (docs/benchmarks/db/hikari.md).
 */
@ConfigurationProperties("radar.search.datasource")
public record SearchDataSourceProperties(
    @DefaultValue("") String url,
    @DefaultValue("radar_read_writer") String username,
    String password,
    @DefaultValue("10") int maximumPoolSize) {}
