package com.safeheal.agent;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

class BoundedTtlMap<K, V> {

    private record Entry<V>(V value, Instant expiresAt) {}

    private final int maxEntries;
    private final Duration ttl;
    private final Clock clock;
    private final Map<K, Entry<V>> map;

    BoundedTtlMap(int maxEntries, Duration ttl, Clock clock) {
        this.maxEntries = maxEntries;
        this.ttl = ttl;
        this.clock = clock;
        this.map = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<K, Entry<V>> eldest) {
                return size() > maxEntries;
            }
        };
    }

    public synchronized boolean containsKey(K key) {
        Entry<V> entry = map.get(key);
        if (entry == null) {
            return false;
        }
        if (clock.instant().isAfter(entry.expiresAt())) {
            map.remove(key);
            return false;
        }
        return true;
    }

    public synchronized V get(K key) {
        Entry<V> entry = map.get(key);
        if (entry == null) {
            return null;
        }
        if (clock.instant().isAfter(entry.expiresAt())) {
            map.remove(key);
            return null;
        }
        return entry.value();
    }

    public synchronized void put(K key, V value) {
        map.put(key, new Entry<>(value, clock.instant().plus(ttl)));
    }

    public synchronized void remove(K key) {
        map.remove(key);
    }

    public synchronized int size() {
        return map.size();
    }
}
