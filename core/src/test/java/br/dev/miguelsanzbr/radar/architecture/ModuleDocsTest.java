package br.dev.miguelsanzbr.radar.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ModuleDocsTest {

  @TempDir Path committed;
  @TempDir Path fresh;

  private static void write(Path dir, String name, String content) throws IOException {
    Files.writeString(dir.resolve(name), content);
  }

  @Test
  void identicalFoldersHaveNoDrift() throws IOException {
    write(committed, "a.puml", "x\ny\n");
    write(fresh, "a.puml", "x\ny\n");

    assertThat(ModuleDocs.drift(committed, fresh)).isEmpty();
  }

  @Test
  void theOrderOfLinesDoesNotMatter() throws IOException {
    write(committed, "a.puml", "x\ny\n");
    write(fresh, "a.puml", "y\nx\n");

    assertThat(ModuleDocs.drift(committed, fresh)).isEmpty();
  }

  @Test
  void aDuplicatedOrChangedLineIsDrift() throws IOException {
    write(committed, "a.puml", "x\ny\n");
    write(fresh, "a.puml", "x\nx\n");

    assertThat(ModuleDocs.drift(committed, fresh)).containsExactly("content differs: a.puml");
  }

  @Test
  void anAddedAndARemovedFileAreDrift() throws IOException {
    write(committed, "old.adoc", "x\n");
    write(fresh, "new.adoc", "x\n");

    assertThat(ModuleDocs.drift(committed, fresh))
        .containsExactlyInAnyOrder(
            "missing in docs/modules: new.adoc", "stale in docs/modules: old.adoc");
  }

  @Test
  void aMissingCommittedFolderIsDriftForEveryFreshFile() throws IOException {
    write(fresh, "a.adoc", "x\n");

    assertThat(ModuleDocs.drift(committed.resolve("nope"), fresh))
        .containsExactly("missing in docs/modules: a.adoc");
  }

  @Test
  void updateCopiesFreshFilesAndDeletesStaleOnes() throws IOException {
    write(committed, "old.adoc", "x\n");
    write(committed, "a.adoc", "before\n");
    write(fresh, "a.adoc", "after\n");
    write(fresh, "b.adoc", "new\n");

    ModuleDocs.update(committed, fresh);

    assertThat(committed.resolve("old.adoc")).doesNotExist();
    assertThat(committed.resolve("a.adoc")).hasContent("after");
    assertThat(committed.resolve("b.adoc")).hasContent("new");
    assertThat(ModuleDocs.drift(committed, fresh)).isEmpty();
  }

  @Test
  void updateCreatesTheCommittedFolderWhenItDoesNotExist() throws IOException {
    write(fresh, "a.adoc", "x\n");

    ModuleDocs.update(committed.resolve("modules"), fresh);

    assertThat(committed.resolve("modules").resolve("a.adoc")).hasContent("x");
  }
}
