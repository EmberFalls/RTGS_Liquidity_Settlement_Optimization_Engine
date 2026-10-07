package com.rtgs.gridlock;

import static org.junit.jupiter.api.Assertions.*;

import com.rtgs.payment.PaymentInstruction;
import com.rtgs.payment.PaymentPriority;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class GridlockDetectorTest {
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private final GridlockDetector detector = new GridlockDetector();

    @Test void noCycle() {
        assertTrue(detector.detect(List.of(edge("ab", "A", "B"), edge("bc", "B", "C"))).isEmpty());
    }

    @Test void twoNodeCycle() {
        var candidates = detector.detect(List.of(edge("ab", "A", "B"), edge("ba", "B", "A")));
        assertEquals(1, candidates.size());
        assertEquals(Set.of("A", "B"), candidates.getFirst().participants());
    }

    @Test void threeNodeCycle() {
        var candidates = detector.detect(List.of(edge("ab", "A", "B"), edge("bc", "B", "C"), edge("ca", "C", "A")));
        assertEquals(3, candidates.getFirst().edges().size());
    }

    @Test void multipleSccsAndDisconnectedGraph() {
        var candidates = detector.detect(List.of(edge("xy", "X", "Y"), edge("ba", "B", "A"),
                edge("ab", "A", "B"), edge("dc", "D", "C"), edge("cd", "C", "D")));
        assertEquals(2, candidates.size());
        assertEquals(Set.of("A", "B"), candidates.get(0).participants());
        assertEquals(Set.of("C", "D"), candidates.get(1).participants());
    }

    @Test void parallelEdgesAndDeterminism() {
        var input = List.of(edge("ab2", "A", "B"), edge("ba", "B", "A"), edge("ab1", "A", "B"));
        var candidate = detector.detect(input).getFirst();
        assertEquals(List.of("ab1", "ab2", "ba"), candidate.edges().stream().map(PaymentEdge::paymentId).toList());
        assertEquals(candidate.edges(), detector.detect(input.reversed()).getFirst().edges());
    }

    private PaymentInstruction edge(String id, String source, String destination) {
        return PaymentInstruction.received(id, source, destination, 10, PaymentPriority.NORMAL, NOW, null).queued(NOW);
    }
}
