package sair.aiagent.core;

import com.google.gson.Gson;

import java.lang.reflect.Type;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 临时存储层 —— 介于「持久存储层（SQLite）」与「AI 上下文」之间的一层加速缓存。
 * <p>
 * 定位：把最近查询过的记忆 / 印象 / 群印象 / 纠正记录暂存在内存 LRU 中，
 * 避免高频读库；Redis 可选加速（Redis 未配置或不可用时静默降级为纯内存 LRU）。
 * </p>
 *
 * <h3>设计</h3>
 * <ul>
 *   <li>内存 LRU：{@link LinkedHashMap} accessOrder=true，淘汰最久未用的条目。</li>
 *   <li>TTL：每个条目有默认过期时间（默认 5 分钟），避免脏数据长期驻留。</li>
 *   <li>Redis 加速：写时 setex 同步一份（JSON 序列化），读时内存 miss 后回源 Redis。</li>
 *   <li>所有 Redis 操作走 {@link RedisClient} 的容错降级，绝不阻塞主流程。</li>
 * </ul>
 */
public final class TemporalStore {

    /** 默认最大条目数 */
    private static final int DEFAULT_MAX_SIZE = 500;

    /** 默认 TTL（毫秒）—— 5 分钟 */
    private static final long DEFAULT_TTL_MS = 300_000L;

    private final int maxSize;
    private final long defaultTtlMs;
    private final Gson gson = new Gson();

    /** 内存 LRU（accessOrder=true 实现 LRU 淘汰） */
    private final Map<String, CacheEntry> lru;

    public TemporalStore() {
        this(DEFAULT_MAX_SIZE, DEFAULT_TTL_MS);
    }

    public TemporalStore(int maxSize, long defaultTtlMs) {
        this.maxSize = maxSize > 0 ? maxSize : DEFAULT_MAX_SIZE;
        this.defaultTtlMs = defaultTtlMs > 0 ? defaultTtlMs : DEFAULT_TTL_MS;
        this.lru = new LinkedHashMap<String, CacheEntry>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, CacheEntry> eldest) {
                return size() > TemporalStore.this.maxSize;
            }
        };
    }

    /**
     * 读取缓存对象。内存 LRU 命中直接返回；miss 后回源 Redis（可选加速）。
     * 全部 miss 返回 null（调用方走 SQLite 原路径）。
     */
    public <T> T get(String key, Class<T> clazz) {
        if (key == null || clazz == null) return null;
        CacheEntry e;
        synchronized (lru) {
            e = lru.get(key);
            if (e != null && e.isExpired()) {
                lru.remove(key);
                e = null;
            }
        }
        if (e != null) return cast(e.value, clazz);

        // Redis 加速层（可选，静默降级）
        RedisClient redis = RedisClient.getInstance();
        if (redis != null && redis.isAvailable()) {
            try {
                String json = redis.get(redisKey(key));
                if (json != null && !json.isEmpty()) {
                    T obj = gson.fromJson(json, clazz);
                    if (obj != null) {
                        put(key, obj, defaultTtlMs);
                        return obj;
                    }
                }
            } catch (Exception ignored) {}
        }
        return null;
    }

    /**
     * 读取缓存对象（支持泛型 List 等复杂类型，配合 {@code TypeToken} 使用）。
     * 内存命中直接强转返回；miss 后回源 Redis 用 Type 反序列化。
     */
    @SuppressWarnings("unchecked")
    public <T> T get(String key, Type type) {
        if (key == null || type == null) return null;
        CacheEntry e;
        synchronized (lru) {
            e = lru.get(key);
            if (e != null && e.isExpired()) {
                lru.remove(key);
                e = null;
            }
        }
        if (e != null) {
            try { return (T) e.value; } catch (ClassCastException ex) { return null; }
        }
        // Redis 加速层（可选，静默降级）
        RedisClient redis = RedisClient.getInstance();
        if (redis != null && redis.isAvailable()) {
            try {
                String json = redis.get(redisKey(key));
                if (json != null && !json.isEmpty()) {
                    T obj = gson.fromJson(json, type);
                    if (obj != null) {
                        put(key, obj, defaultTtlMs);
                        return obj;
                    }
                }
            } catch (Exception ignored) {}
        }
        return null;
    }

    /** 写入缓存（默认 TTL）。 */
    public void put(String key, Object value) {
        put(key, value, defaultTtlMs);
    }

    /** 写入缓存（自定义 TTL）。 */
    public void put(String key, Object value, long ttlMs) {
        if (key == null || value == null) return;
        long expireAt = System.currentTimeMillis() + (ttlMs > 0 ? ttlMs : defaultTtlMs);
        synchronized (lru) {
            lru.put(key, new CacheEntry(value, expireAt));
        }
        // Redis 加速层（可选，静默降级）
        RedisClient redis = RedisClient.getInstance();
        if (redis != null && redis.isAvailable()) {
            try {
                int seconds = (int) Math.max(1, (ttlMs > 0 ? ttlMs : defaultTtlMs) / 1000);
                redis.setex(redisKey(key), seconds, gson.toJson(value));
            } catch (Exception ignored) {}
        }
    }

    /**
     * 前缀失效代数：{@code prefix -> generation}。
     * <p>
     * {@link #invalidateByPrefix} 只能清内存（Redis 没有 SCAN 就无法按前缀删除），
     * 于是「写库 → 前缀失效 → 下一次读」会在内存 miss 后从 Redis 回源到<b>失效前的旧值</b>
     * （旧数据复活）。把代数拼进 Redis 键名即可隔离：失效后代数 +1，旧键再也读不到，
     * 由各自的 TTL 自然过期。
     * </p>
     */
    private final Map<String, Integer> prefixGeneration = new java.util.concurrent.ConcurrentHashMap<>();

    /** 计算实际使用的 Redis 键名（带所有命中前缀的失效代数签名）。 */
    private String redisKey(String key) {
        StringBuilder sig = null;
        for (Map.Entry<String, Integer> e : prefixGeneration.entrySet()) {
            if (key.startsWith(e.getKey())) {
                if (sig == null) sig = new StringBuilder();
                sig.append(e.getKey()).append('#').append(e.getValue()).append(';');
            }
        }
        return (sig == null ? "" : sig.toString()) + key;
    }

    /** 使单个 key 失效（内存 + Redis）。 */
    public void invalidate(String key) {
        if (key == null) return;
        synchronized (lru) {
            lru.remove(key);
        }
        RedisClient redis = RedisClient.getInstance();
        if (redis != null) {
            try { redis.del(redisKey(key)); } catch (Exception ignored) {}
        }
    }

    /** 使某前缀下所有 key 失效（内存 + Redis 代数隔离）。 */
    public void invalidateByPrefix(String prefix) {
        if (prefix == null) return;
        prefixGeneration.merge(prefix, 1, Integer::sum);
        synchronized (lru) {
            lru.keySet().removeIf(k -> k.startsWith(prefix));
        }
    }

    /** 清空内存缓存（并用空前缀代数让所有 Redis 旧值失效）。 */
    public void clear() {
        prefixGeneration.merge("", 1, Integer::sum);
        synchronized (lru) {
            lru.clear();
        }
    }

    /** 当前内存缓存条目数。 */
    public int size() {
        synchronized (lru) {
            return lru.size();
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T cast(Object o, Class<T> clazz) {
        if (o == null) return null;
        try {
            return clazz.cast(o);
        } catch (ClassCastException e) {
            return null;
        }
    }

    /** 缓存条目（值 + 过期时间戳） */
    private static final class CacheEntry {
        final Object value;
        final long expireAt;

        CacheEntry(Object value, long expireAt) {
            this.value = value;
            this.expireAt = expireAt;
        }

        boolean isExpired() {
            return System.currentTimeMillis() > expireAt;
        }
    }
}
