package br.dev.miguelsanzbr.radar.architecture.fixture.domain;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.persistence.EntityManager;
import org.apache.kafka.clients.producer.Producer;
import org.hibernate.Session;
import org.jooq.DSLContext;
import org.springframework.context.ApplicationContext;
import tools.jackson.databind.json.JsonMapper;

/**
 * Violates the domain purity rule: one reference per forbidden family.
 *
 * <p>Only field types, never stereotypes or mappings: component scanning also reaches test classes.
 */
public class ForbiddenImportsInDomain {

  @JsonProperty("a")
  JsonMapper mapper;

  ApplicationContext spring;
  EntityManager jpa;
  Session hibernate;
  DSLContext jooq;
  Producer<String, String> kafka;
}
