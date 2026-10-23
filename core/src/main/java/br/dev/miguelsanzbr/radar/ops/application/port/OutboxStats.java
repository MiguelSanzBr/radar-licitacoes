package br.dev.miguelsanzbr.radar.ops.application.port;

import br.dev.miguelsanzbr.radar.ops.domain.OutboxBacklog;

public interface OutboxStats {

  OutboxBacklog read();
}
