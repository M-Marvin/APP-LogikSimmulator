package de.m_marvin.logicsim.logic;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Random;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Stream;

import de.m_marvin.logicsim.logic.nodes.Node;
import de.m_marvin.univec.impl.Vec4i;

/**
 * The class containing all information about an circuit and its current simulation state.
 * The methods of this class are thread safe when calling them but the methods that return Lists, Maps or Sets do return the internal object.
 * Modifications or calls to that object are <b>NOT guaranteed to be threads safe</b> and should be surrounded by an synchronized block with the circuit as lock-object!
 * The returned objects are mainly intended to be used to directly read data from the simulation without querying the objects over and over.
 * 
 * @author Marvin K.
 *
 */
public class Circuit {
	
	public static final String DEFAULT_BUS_LANE = "bus0";
	
	public static Random floatingValue = new Random();
	public static boolean shortCircuitValue = false;
	
	public synchronized static boolean getFloatingValue() {
		return floatingValue.nextBoolean();
	}
	
	public synchronized static boolean getShortCircuitValue() {
		shortCircuitValue = !shortCircuitValue;
		return floatingValue.nextBoolean();
	}
	
	public static NetState combineStates(NetState stateA, NetState stateB, ShortCircuitType type) {
		if (stateA == NetState.FLOATING) return stateB;
		if (stateB == NetState.FLOATING) return stateA;
		switch (type) {
		default:
		case HIGH_LOW_SHORT:
			if (stateA == NetState.SHORT_CIRCUIT) return NetState.SHORT_CIRCUIT;
			if (stateB == NetState.SHORT_CIRCUIT) return NetState.SHORT_CIRCUIT;
			if (stateA != stateB) return NetState.SHORT_CIRCUIT;
			return stateA;
		case PREFER_HIGH:
			if (stateA == NetState.SHORT_CIRCUIT) return NetState.HIGH;
			if (stateB == NetState.SHORT_CIRCUIT) return NetState.HIGH;
			return stateA == NetState.HIGH || stateB == NetState.HIGH ? NetState.HIGH : NetState.LOW;
		case PREFER_LOW:
			if (stateA == NetState.SHORT_CIRCUIT) return NetState.LOW;
			if (stateB == NetState.SHORT_CIRCUIT) return NetState.LOW;
			return stateA == NetState.LOW || stateB == NetState.LOW ? NetState.LOW : NetState.HIGH;
		}
	}
	
	public static enum ShortCircuitType {
		HIGH_LOW_SHORT,PREFER_HIGH,PREFER_LOW;
	}
	
	public static enum NetState {
		LOW(() -> false),HIGH(() -> true),FLOATING(Circuit::getFloatingValue),SHORT_CIRCUIT(Circuit::getShortCircuitValue);
		
		private final Supplier<Boolean> logicValue;
		
		NetState(Supplier<Boolean> logicValueSupplier) {
			this.logicValue = logicValueSupplier;
		}
		
		public boolean getLogicState() {
			return this.logicValue.get();
		}
		
		public boolean isLogicalState() {
			return this == HIGH || this == LOW;
		}
		
		public boolean isErrorState() {
			return !isLogicalState();
		}
	}
	
	protected static Number castToBits(Long value, int bitCount) {
		if (bitCount > 32) return value.longValue();
		if (bitCount > 16) return value.intValue();
		if (bitCount > 8) return value.shortValue();
		return value.byteValue();
	}
	
	public static Map<String, Long> getLaneData(Map<String, NetState> laneData) {
		
		Map<String, Long> busData = new HashMap<>();
		Map<String, Integer> bitCounts = new HashMap<>();
		for (String lane : laneData.keySet()) {
			String[] laneParts = lane.split("(?<=\\D)(?=\\d)");
			try {
				String bus = laneParts[0];
				int bit = Integer.parseInt(laneParts[1]);
				boolean value = laneData.get(lane).getLogicState();
				int bitCount = bitCounts.getOrDefault(bus, 0);
				if (bit + 1 > bitCount) {
					bitCount = bit + 1;
					bitCounts.put(bus, bitCount);
				}
				
				Long dataValue = busData.getOrDefault(bus, 0L);
				if (value) dataValue += (1L << bit);
				busData.put(bus, dataValue);
			} catch (NumberFormatException e) {}
		}
		return busData;
		
	}
	
	
	
	
	protected final List<Set<Node>> networks = new ArrayList<>();
	protected final List<Map<String, NetState>> valuesSec = new ArrayList<>();
	protected final List<Map<String, NetState>> valuesPri = new ArrayList<>();
	protected final List<Component> components = new ArrayList<>();
	protected File circuitFile;
	protected final boolean virtual;
	protected ShortCircuitType shortCircuitType = ShortCircuitType.HIGH_LOW_SHORT;
	
	public Circuit() {
		this(false);
	}

	public Circuit(boolean virtual) {
		this.virtual = virtual;
	}
	
	public File getCircuitFile() {
		return this.circuitFile;
	}
	
	public synchronized void setCircuitFile(File file) {
		this.circuitFile = file;
	}
	
	public ShortCircuitType getShortCircuitMode() {
		return this.shortCircuitType;
	}
	
	public synchronized void setShortCircuitMode(ShortCircuitType shortCircuitType) {
		this.shortCircuitType = shortCircuitType;
	}

	public boolean isVirtual() {
		return this.virtual;
	}
	
	
	
	
	protected synchronized void reconnectNet(List<Node> nodes, boolean excludeNodes) {
		List<Node> nodesToReconnect = new ArrayList<>();
		nodes.forEach(node -> {
			OptionalInt netId = findNet(node);
			if (netId.isPresent()) {
				nodesToReconnect.addAll(removeNet(netId.getAsInt()));
			} else {
				nodesToReconnect.add(node);
			}
		});
		List<Node> excluded = excludeNodes ? nodes : null;
		Stream.of(nodesToReconnect.toArray(l -> new Node[l])).mapToInt(node -> groupNodeToNet(node, excluded)).forEach(this::combineNets);
	}
	
	protected synchronized int groupNodeToNet(Node node, List<Node> excluded) {
		List<Node> nodeCache = new ArrayList<Node>();
		Set<Node> network = new HashSet<>();
		this.components.forEach(component -> {
			component.getAllNodes().forEach(node2 -> {
				if (excluded != null) for (Node n : excluded) if (n == node2) return;
				if (node2.equals(node)) {
					nodeCache.add(node2);
				}
			});
		});
		this.components.forEach(component -> {
			component.getAllNodes().forEach(node2 -> {
				nodeCache.forEach(node1 -> {
					if (excluded != null) for (Node n : excluded) if (n == node2) return;
					if (node2.getVisualPosition().equals(node1.getVisualPosition())) {
						network.add(node2);
					}
				});
			});
		});
		network.addAll(nodeCache);
		if (network.size() > 0) {
			this.networks.add(network);
			this.valuesPri.add(new HashMap<>());
			this.valuesSec.add(new HashMap<>());
		}
		return this.networks.size() - 1;
	}
	
	protected synchronized void combineNets(int netId) {
		if (netId == -1) return;
		Set<Node> network = new HashSet<>();
		removeNet(netId).forEach(node -> {
			OptionalInt existingNet = findNet(node);
			if (existingNet.isPresent()) {
				network.addAll(removeNet(existingNet.getAsInt()));
			} else {
				network.add(node);
			}
		});
		if (network.size() > 0) {
			this.networks.add(network);
			this.valuesPri.add(new HashMap<>());
			this.valuesSec.add(new HashMap<>());
		}
	}

	protected synchronized Set<Node> removeNet(int netId) {
		this.valuesPri.remove(netId);
		this.valuesSec.remove(netId);
		return this.networks.remove(netId);
	}
	
	protected OptionalInt findNet(Node node) {
		for (int i = 0; i < this.networks.size(); i++) {
			for (Node n : this.networks.get(i)) {
				if (n.equals(node)) return OptionalInt.of(i);
			}
		}
		return OptionalInt.empty();
	}
	
	public void reconnect(boolean excludeComponents, Component... components) {
		List<Node> nodes = Stream.of(components).flatMap(component -> component.getAllNodes().stream()).toList();
		reconnectNet(nodes, excludeComponents);
	}
	
	protected NetState getNetValue(int netId, String lane) {
		Map<String, NetState> laneData = valuesSec.get(netId);
		if (!laneData.containsKey(lane)) laneData.put(lane, NetState.FLOATING);
		return this.valuesSec.size() > netId ? laneData.get(lane) : NetState.FLOATING;
	}

	protected synchronized Map<String, NetState> getNetLanes(int netId) {
		return this.valuesSec.size() > netId ? this.valuesSec.get(netId) : null;
	}

	protected synchronized void applyNetValue(int netId, NetState state, String lane) {
		if (netId >= this.valuesPri.size()) return;
		NetState resultingState = combineStates(this.valuesPri.get(netId).getOrDefault(lane, NetState.FLOATING), state, this.shortCircuitType);
		this.valuesPri.get(netId).put(lane, resultingState);
		this.valuesSec.get(netId).put(lane, resultingState);
	}
	
	protected synchronized void applyNetLanes(int netId, Map<String, NetState> laneStates) {
		if (netId >= this.valuesPri.size()) return;
		Map<String, NetState> laneStatesPri = this.valuesPri.get(netId);
		Map<String, NetState> laneStatesSec = this.valuesSec.get(netId);
		laneStates.forEach((lane, state) -> {
			NetState resultingState = combineStates(laneStatesPri.getOrDefault(lane, NetState.FLOATING), state, this.shortCircuitType);
			laneStatesPri.put(lane, resultingState);
			laneStatesSec.put(lane, resultingState);
		});
	}
	
	public NetState getNetState(Node node) {
		return getNetState(node, DEFAULT_BUS_LANE);
	}
	
	public NetState getNetState(Node node, String lane) {
		OptionalInt netId = findNet(node);
		if (netId.isEmpty()) return NetState.FLOATING;
		return getNetValue(netId.getAsInt(), lane);
	}
	
	public Map<String, NetState> getLaneMapReference(Node node) {
		OptionalInt netId = findNet(node);
		if (netId.isEmpty()) return null;
		return getNetLanes(netId.getAsInt());
	}
	
	public void setNetState(Node node, NetState state) {
		setNetState(node, state, DEFAULT_BUS_LANE);
	}
	
	public void writeLanes(Node node, Map<String, NetState> laneStates) {
		if (laneStates == null || laneStates.isEmpty()) return;
		OptionalInt netId = findNet(node);
		if (netId.isPresent()) applyNetLanes(netId.getAsInt(), laneStates);
	}
	
	public void setNetState(Node node, NetState state, String lane) {
		OptionalInt netId = findNet(node);
		if (netId.isPresent()) applyNetValue(netId.getAsInt(), state, lane);
	}

	public synchronized void resetNetworks() {
		for (int i = 0; i < this.valuesPri.size(); i++) this.valuesPri.get(i).clear();
		for (int i = 0; i < this.valuesSec.size(); i++) {
			for (String lane : this.valuesSec.get(i).keySet()) {
				this.valuesSec.get(i).put(lane, NetState.LOW);
			}
		}
		this.components.forEach(Component::reset);
	}
	
	public synchronized void updateCircuit() {
		assert !this.virtual : "Can't simulate virtual circuit!";
		this.valuesPri.forEach(holder -> holder.clear());
		this.components.forEach(Component::updateIO);
		for (int i = 0; i < this.valuesPri.size(); i++) this.valuesSec.get(i).putAll(this.valuesPri.get(i)); //.put("", this.values.get(i).get(""));
	}
	
	
	
	
	public boolean isNodeConnected(Node node) {
		for (int i = 0; i < this.networks.size(); i++) {
			for (Node n : this.networks.get(i)) {
				if (n.equals(node)) {
					return networks.get(i).size() > 1;
				}
			}
		}
		return false;
	}
	
	public synchronized void add(Component component) {
		component.created();
		this.components.add(component);
	}
	
	public synchronized void remove(Component component) {
		component.dispose();
		this.components.remove(component);
	}
	
	public List<Component> getComponents() {
		return this.components;
	}

	public List<Component> getComponents(Predicate<Component> componentPredicate) {
		return this.components.stream().filter(componentPredicate).toList();
	}
	
	public synchronized void clear() {
		this.components.clear();
		this.networks.clear();
		this.valuesPri.clear();
		this.valuesSec.clear();
	}
	
	public int nextFreeId() {
		OptionalInt lastId = this.components.stream().mapToInt(Component::getComponentNr).max();
		if (lastId.isEmpty()) return 0;
		return lastId.getAsInt() + 1;
	}
	
	public Vec4i getCircuitBounds(Predicate<Component> componentPredicate) {
		return getCircuitBounds(componentPredicate, componentPredicate);
	}
	
	public Vec4i getCircuitBounds(Predicate<Component> componentPredicateX, Predicate<Component> componentPredicateY) {
		if (this.components.stream().anyMatch(componentPredicateX) && this.components.stream().anyMatch(componentPredicateY)) {
			int minX = this.components.stream().filter(componentPredicateX).mapToInt(component -> component.getVisualPosition().x).min().getAsInt();
			int minY = this.components.stream().filter(componentPredicateY).mapToInt(component -> component.getVisualPosition().y).min().getAsInt();
			int maxX = this.components.stream().filter(componentPredicateX).mapToInt(component -> component.getVisualPosition().x).max().getAsInt();
			int maxY = this.components.stream().filter(componentPredicateY).mapToInt(component -> component.getVisualPosition().y).max().getAsInt();
			return new Vec4i(minX, minY, maxX, maxY);
		}
		return new Vec4i(0, 0, 0, 0);
	}
	
}
