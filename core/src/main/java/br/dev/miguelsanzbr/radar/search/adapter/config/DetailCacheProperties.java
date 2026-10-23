package br.dev.miguelsanzbr.radar.search.adapter.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** How long a cached detail lives; {@code radar.search.detail-cache.enabled} switches it off. */
@ConfigurationProperties("radar.search.detail-cache")
public record DetailCacheProperties(@DefaultValue("1h") Duration ttl) {}
