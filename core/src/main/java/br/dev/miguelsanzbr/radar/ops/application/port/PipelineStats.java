package br.dev.miguelsanzbr.radar.ops.application.port;

import br.dev.miguelsanzbr.radar.ops.domain.Checkpoint;
import br.dev.miguelsanzbr.radar.ops.domain.Cursor;
import br.dev.miguelsanzbr.radar.ops.domain.IngestionRun;
import java.util.List;

/** Read-only view of the collection and of the read model projection. */
public interface PipelineStats {

  /** The latest run of each kind and modality. */
  List<IngestionRun> lastRuns();

  List<Cursor> cursors();

  List<Checkpoint> checkpoints();
}
