package com.rtgs.gridlock;

import com.rtgs.payment.PaymentInstruction;
import java.util.Collection;
import java.util.List;

public class GridlockDetector {
    public List<GridlockCandidate> detect(Collection<PaymentInstruction> payments) {
        ObligationGraph graph = new ObligationGraph(payments);
        return new StronglyConnectedComponents().find(graph).stream()
                .filter(component -> component.size() > 1)
                .map(component -> new GridlockCandidate(component, graph.internalEdges(component)))
                .toList();
    }
}
