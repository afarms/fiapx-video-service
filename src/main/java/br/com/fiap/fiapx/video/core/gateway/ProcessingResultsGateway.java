package br.com.fiap.fiapx.video.core.gateway;

import br.com.fiap.fiapx.video.core.domain.ProcessingResultEvent;

public interface ProcessingResultsGateway {
    enum Outcome { APPLIED, IGNORED, DUPLICATE }
    /** Every outcome is returned only after inbox and metadata commit; exceptions forbid ACK. */
    Outcome apply(ProcessingResultEvent event);
}
