package com.rtgs.gridlock;

import com.rtgs.payment.PaymentInstruction;
import com.rtgs.payment.PaymentStatus;
import java.util.*;

public class ObligationGraph {
    private final NavigableMap<String, List<PaymentEdge>> outgoing = new TreeMap<>();

    public ObligationGraph(Collection<PaymentInstruction> payments) {
        payments.stream().filter(p -> p.status() == PaymentStatus.QUEUED)
                .map(PaymentEdge::from).sorted(Comparator.comparing(PaymentEdge::paymentId))
                .forEach(edge -> {
                    outgoing.computeIfAbsent(edge.sourceParticipantId(), ignored -> new ArrayList<>()).add(edge);
                    outgoing.computeIfAbsent(edge.destinationParticipantId(), ignored -> new ArrayList<>());
                });
    }

    public Set<String> vertices() { return Collections.unmodifiableSet(outgoing.navigableKeySet()); }
    public List<PaymentEdge> outgoing(String participantId) {
        return List.copyOf(outgoing.getOrDefault(participantId, List.of()));
    }
    public List<PaymentEdge> internalEdges(Set<String> vertices) {
        return outgoing.values().stream().flatMap(List::stream)
                .filter(e -> vertices.contains(e.sourceParticipantId()) && vertices.contains(e.destinationParticipantId()))
                .sorted(Comparator.comparing(PaymentEdge::paymentId)).toList();
    }
}
