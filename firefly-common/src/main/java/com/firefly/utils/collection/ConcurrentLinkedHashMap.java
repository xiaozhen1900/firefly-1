package com.firefly.utils.collection;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

public class ConcurrentLinkedHashMap<K, V> implements Map<K,V> {
	
	/**
     * The default initial capacity - MUST be a power of two.
     */
    static final int DEFAULT_INITIAL_CAPACITY = 16;
    
    /**
     * The load factor used when none specified in constructor.
     */
    static final float DEFAULT_LOAD_FACTOR = 0.75f;
    
    /**
	 * The default concurrency level for this table, used when not otherwise
	 * specified in a constructor.
	 */
	static final int DEFAULT_CONCURRENCY_LEVEL = 16;
	
	/**
	 * Mask value for indexing into segments. The upper bits of a key's hash
	 * code are used to choose the segment.
	 */
	private final int segmentMask;

	/**
	 * Shift value for indexing within segments.
	 */
	private final int segmentShift;
	private final int concurrencyLevel;
	private final LinkedHashMapSegment<K, V>[] segments;
	private final MapEventListener<K, V> mapEventListener;
	
	public ConcurrentLinkedHashMap(boolean accessOrder, 
 			int maxEntries,
 			MapEventListener<K, V> mapEventListener) {
		this(accessOrder, maxEntries, mapEventListener, DEFAULT_CONCURRENCY_LEVEL);
	}
	
	public ConcurrentLinkedHashMap(boolean accessOrder, 
 			int maxEntries,
 			MapEventListener<K, V> mapEventListener,
 			int concurrencyLevel) {
		this(DEFAULT_INITIAL_CAPACITY, DEFAULT_LOAD_FACTOR, accessOrder, maxEntries, mapEventListener, concurrencyLevel);
	}
	
	/**
	 * 
	 * @param initialCapacity map initial capacity in every segment
	 * @param loadFactor the load factor decide the map increase to what degree have to expand, default value is 0.75f
	 * @param accessOrder the ordering mode - <tt>true</tt> for
     *         access-order, <tt>false</tt> for insertion-order
	 * @param maxEntries map's the biggest capacity, it isn't accurate, 
	 * 					the actual limit of capacity depend on the entry weather if average in segments
	 * @param mapEventListener the callback method of map's operations
	 * @param concurrencyLevel the number of segment, default is 16
	 */
	@SuppressWarnings("unchecked")
	public ConcurrentLinkedHashMap(int initialCapacity,
 			float loadFactor,
 			boolean accessOrder, 
 			int maxEntries,
 			MapEventListener<K, V> mapEventListener,
 			int concurrencyLevel) {
		this.mapEventListener = mapEventListener;
		
		int cLevel = concurrencyLevel > 0 ? concurrencyLevel : DEFAULT_CONCURRENCY_LEVEL;
		// Find a power of 2 >= concurrencyLevel
        int level = 1;
        int sshift = 0;
        while (level < cLevel) {
            level <<= 1;
            sshift++;
        }
        segmentShift = 32 - sshift;
		segmentMask = level - 1;
        this.concurrencyLevel = level;
		
		segments = new LinkedHashMapSegment[this.concurrencyLevel];
		for (int i = 0; i < segments.length; i++) {
			LinkedHashMapSegment<K, V> segment = new LinkedHashMapSegment<K, V>(
					initialCapacity, 
					loadFactor, 
					accessOrder, 
					maxEntries <= this.concurrencyLevel ? 1 : (maxEntries / this.concurrencyLevel) , 
					mapEventListener);
			segments[i] = segment;
		}
		
	}

	public interface MapEventListener<K, V> {
		boolean onEliminateEntry(K key, V value);
		V onGetEntry(K key, V value);
		V onPutEntry(K key, V value, V previousValue);
		V onRemoveEntry(K key, V value);
	}
	
	static final class LinkedHashMapSegment<K, V> extends LinkedHashMap<K, V>{

		private static final long serialVersionUID = 3135160986591665845L;
		private int maxEntries;
		private MapEventListener<K, V> mapEventListener;
		
		public int getMaxEntries() {
			return maxEntries;
		}

		public LinkedHashMapSegment(int initialCapacity,
                         			float loadFactor,
                         			boolean accessOrder, 
                         			int maxEntries,
                         			MapEventListener<K, V> mapEventListener) {
			super(initialCapacity, loadFactor, accessOrder);
			this.maxEntries = maxEntries;
			this.mapEventListener = mapEventListener;
		}
		
		protected synchronized boolean removeEldestEntry(Map.Entry<K,V> eldest) {
			if(size() > maxEntries) {
				return mapEventListener.onEliminateEntry(eldest.getKey(), eldest.getValue());
			}
	        return false;
	    }
		
	}
	
	/**
	 * Applies a supplemental hash function to a given hashCode, which defends
	 * against poor quality hash functions. This is critical because
	 * ConcurrentHashMap uses power-of-two length hash tables, that otherwise
	 * encounter collisions for hashCodes that do not differ in lower or upper
	 * bits.
	 */
	private static int hash(int h) {
		// Spread bits to regularize both segment and index locations,
		// using variant of single-word Wang/Jenkins hash.
		h += (h << 15) ^ 0xffffcd7d;
		h ^= (h >>> 10);
		h += (h << 3);
		h ^= (h >>> 6);
		h += (h << 2) + (h << 14);
		return h ^ (h >>> 16);
	}
	
	/**
	 * Returns the segment that should be used for key with given hash
	 * 
	 * @param hash the hash code for the key
	 * @return the segment
	 */
	private final LinkedHashMapSegment<K, V> segmentFor(int hash) {
		int h = hash(hash);
		return segments[(h >>> segmentShift) & segmentMask];
	}

	/**
	 * the entry's total number, it's not accurate.
	 */
	@Override
	public int size() {
		int size = 0;
		for(LinkedHashMapSegment<K, V> seg : segments) {
			synchronized (seg) {
				size += seg.size();
			}
		}
		return size;
	}

	@Override
	public boolean isEmpty() {
		for(LinkedHashMapSegment<K, V> seg : segments) {
			synchronized (seg) {
				if(!seg.isEmpty())
					return false;
			}
		}
		return true;
	}

	@Override
	public boolean containsKey(Object key) {
		return get(key) != null;
	}

	@Override
	public boolean containsValue(Object value) {
		for(LinkedHashMapSegment<K, V> seg : segments) {
			synchronized (seg) {
				if(seg.containsValue(value))
					return true;
			}
		}
		return false;
	}

	@SuppressWarnings("unchecked")
	@Override
	public V get(Object key) {
		LinkedHashMapSegment<K, V> seg = segmentFor(key.hashCode());
		synchronized (seg) {
			return mapEventListener.onGetEntry((K)key, seg.get(key));
		}
		
	}

	@Override
	public V put(K key, V value) {
		LinkedHashMapSegment<K, V> seg = segmentFor(key.hashCode());
		synchronized (seg) {
			return mapEventListener.onPutEntry(key, value, seg.put(key, value));
		}
	}

	@SuppressWarnings("unchecked")
	@Override
	public V remove(Object key) {
		LinkedHashMapSegment<K, V> seg = segmentFor(key.hashCode());
		synchronized (seg) {
			return mapEventListener.onRemoveEntry((K)key, seg.remove(key));
		}
	}

	@Override
	public void putAll(Map<? extends K, ? extends V> m) {
		for(java.util.Map.Entry<? extends K, ? extends V> entry : m.entrySet()) {
			put(entry.getKey(), entry.getValue());
		}
	}
	
	/**
	 * clear all map's entries, but it dosen't trigger the remove callback method
	 */
	@Override
	public void clear() {
		for(LinkedHashMapSegment<K, V> seg : segments) {
			synchronized (seg) {
				seg.clear();
			}
		}
	}

	@Override
	public Set<K> keySet() {
		Set<K> set = new HashSet<K>();
		for(LinkedHashMapSegment<K, V> seg : segments) {
			synchronized (seg) {
				set.addAll(seg.keySet());
			}
		}
		return set;
	}

	@Override
	public Collection<V> values() {
		Collection<V> collection = new ArrayList<V>();
		for(LinkedHashMapSegment<K, V> seg : segments) {
			synchronized (seg) {
				collection.addAll(seg.values());
			}
		}
		return collection;
	}

	@Override
	public Set<java.util.Map.Entry<K, V>> entrySet() {
		Set<java.util.Map.Entry<K, V>> set = new HashSet<java.util.Map.Entry<K, V>>();
		for(LinkedHashMapSegment<K, V> seg : segments) {
			synchronized (seg) {
				set.addAll(seg.entrySet());
			}
		}
		return set;
	}

	public int getConcurrencyLevel() {
		return concurrencyLevel;
	}
	
	public int getSegmentShift() {
		return segmentShift;
	}

	public int getSegmentMask() {
		return segmentMask;
	}
	
}
