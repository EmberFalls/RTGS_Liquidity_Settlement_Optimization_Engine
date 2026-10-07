package com.rtgs.gridlock;

import java.util.*;

public class StronglyConnectedComponents {
    private final Map<String, Integer> index = new HashMap<>();
    private final Map<String, Integer> low = new HashMap<>();
    private final Deque<String> stack = new ArrayDeque<>();
    private final Set<String> onStack = new HashSet<>();
    private final List<Set<String>> components = new ArrayList<>();
    private int nextIndex;

    public List<Set<String>> find(ObligationGraph graph) {
        index.clear(); low.clear(); stack.clear(); onStack.clear(); components.clear(); nextIndex = 0;
        for (String vertex : graph.vertices()) if (!index.containsKey(vertex)) visit(vertex, graph);
        return components.stream().sorted(Comparator.comparing(component -> component.iterator().next())).toList();
    }

    private void visit(String vertex, ObligationGraph graph) {
        index.put(vertex, nextIndex);
        low.put(vertex, nextIndex++);
        stack.push(vertex);
        onStack.add(vertex);
        for (PaymentEdge edge : graph.outgoing(vertex)) {
            String next = edge.destinationParticipantId();
            if (!index.containsKey(next)) {
                visit(next, graph);
                low.put(vertex, Math.min(low.get(vertex), low.get(next)));
            } else if (onStack.contains(next)) {
                low.put(vertex, Math.min(low.get(vertex), index.get(next)));
            }
        }
        if (low.get(vertex).equals(index.get(vertex))) {
            Set<String> component = new TreeSet<>();
            String current;
            do {
                current = stack.pop();
                onStack.remove(current);
                component.add(current);
            } while (!current.equals(vertex));
            components.add(component);
        }
    }
}
