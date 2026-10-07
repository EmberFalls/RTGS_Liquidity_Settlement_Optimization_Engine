package com.rtgs.gridlock;

import java.util.List;
import java.util.Set;

public record GridlockCandidate(Set<String> participants, List<PaymentEdge> edges) {
    public GridlockCandidate {
        participants = Set.copyOf(participants);
        edges = List.copyOf(edges);
        if (participants.size() < 2 || edges.isEmpty()) throw new IllegalArgumentException("candidate requires a cycle");
    }
}
