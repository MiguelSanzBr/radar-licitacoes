package br.dev.miguelsanzbr.radar.outbox.adapter.kafka;

import io.apicurio.registry.resolver.client.RegistryArtifactReference;
import io.apicurio.registry.resolver.client.RegistryClientFacade;
import io.apicurio.registry.resolver.client.RegistryVersionCoordinates;
import io.apicurio.registry.resolver.strategy.ArtifactReference;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;

/**
 * A schema registry in memory, enough for the Apicurio serdes to register and look up schemas
 * without a server: the same content gets the same id, as in the real registry.
 */
public final class InMemoryRegistry implements RegistryClientFacade {

  /** One schema the serializer registered. */
  public record Registration(String groupId, String artifactId, String content) {}

  private final Map<String, Long> idByContent = new HashMap<>();
  private final Map<Long, String> contentById = new HashMap<>();
  private final List<Registration> registrations = new ArrayList<>();
  private RuntimeException failure;
  private boolean hanging;

  /** Every later call fails, as when the registry is unreachable. */
  public InMemoryRegistry down() {
    return failingWith(new UncheckedIOException(new IOException("registry unreachable")));
  }

  /** Every later call fails with this exception. */
  public InMemoryRegistry failingWith(RuntimeException failure) {
    this.failure = failure;
    return this;
  }

  /**
   * Every later call waits until the thread is interrupted, as a registry that accepts and never
   * answers.
   */
  public InMemoryRegistry hanging() {
    hanging = true;
    return this;
  }

  public List<Registration> registrations() {
    return registrations;
  }

  /** The id the registry gave to the content, the one written in the wire format. */
  public long idOf(String content) {
    return idByContent.get(content);
  }

  private int calls;

  /** How many times the serdes asked this registry for anything. */
  public int calls() {
    return calls;
  }

  private void check() {
    calls++;
    if (failure != null) {
      throw failure;
    }
    if (hanging) {
      try {
        new CountDownLatch(1).await();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException(e);
      }
    }
  }

  @Override
  public RegistryVersionCoordinates createSchema(
      String artifactType,
      String groupId,
      String artifactId,
      String version,
      String ifExists,
      boolean canonical,
      String content,
      Set<RegistryArtifactReference> references) {
    check();
    var id = idByContent.computeIfAbsent(content, c -> (long) idByContent.size() + 1);
    contentById.put(id, content);
    registrations.add(new Registration(groupId, artifactId, content));
    return RegistryVersionCoordinates.create(id, id, groupId, artifactId, "1");
  }

  @Override
  public String getSchemaByContentId(Long contentId) {
    check();
    return contentById.get(contentId);
  }

  @Override
  public String getSchemaByGlobalId(long globalId, boolean dereference) {
    return getSchemaByContentId(globalId);
  }

  @Override
  public String getSchemaByGAV(String groupId, String artifactId, String version) {
    throw new UnsupportedOperationException();
  }

  @Override
  public String getSchemaByContentHash(String contentHash) {
    throw new UnsupportedOperationException();
  }

  @Override
  public List<RegistryArtifactReference> getReferencesByContentId(long contentId) {
    return List.of();
  }

  @Override
  public List<RegistryArtifactReference> getReferencesByGlobalId(long globalId) {
    return List.of();
  }

  @Override
  public List<RegistryArtifactReference> getReferencesByGAV(
      String groupId, String artifactId, String version) {
    return List.of();
  }

  @Override
  public List<RegistryArtifactReference> getReferencesByContentHash(String contentHash) {
    return List.of();
  }

  @Override
  public List<RegistryVersionCoordinates> searchVersionsByContent(
      String content, String artifactType, ArtifactReference reference, boolean canonical) {
    throw new UnsupportedOperationException();
  }

  @Override
  public RegistryVersionCoordinates getVersionCoordinatesByGAV(
      String groupId, String artifactId, String version) {
    throw new UnsupportedOperationException();
  }

  @Override
  public Object getClient() {
    throw new UnsupportedOperationException();
  }
}
