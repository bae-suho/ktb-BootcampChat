package com.ktb.chatapp.websocket.socketio;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.Optional;
import java.util.Set;

/**
 * Redis implementation of ChatDataStore.
 * Provides distributed storage for socket-related data across multiple server instances.
 */
@Slf4j
@RequiredArgsConstructor
public class RedisChatDataStore implements ChatDataStore {

    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;

    private static final String KEY_PREFIX = "socket:";
    private static final Duration DEFAULT_TTL = Duration.ofHours(1);

    @Override
    public <T> Optional<T> get(String key, Class<T> type) {
        try {
            String redisKey = buildRedisKey(key);
            String json = stringRedisTemplate.opsForValue().get(redisKey);

            if (json == null) {
                return Optional.empty();
            }

            T value = objectMapper.readValue(json, type);
            return Optional.ofNullable(value);

        } catch (JsonProcessingException e) {
            log.error("Failed to deserialize value for key: {}", key, e);
            return Optional.empty();
        } catch (Exception e) {
            log.error("Error getting value from Redis for key: {}", key, e);
            return Optional.empty();
        }
    }

    @Override
    public void set(String key, Object value) {
        try {
            String redisKey = buildRedisKey(key);
            String json = objectMapper.writeValueAsString(value);
            stringRedisTemplate.opsForValue().set(redisKey, json, DEFAULT_TTL);

        } catch (JsonProcessingException e) {
            log.error("Failed to serialize value for key: {}", key, e);
            throw new RuntimeException("Failed to serialize value", e);
        } catch (Exception e) {
            log.error("Error setting value in Redis for key: {}", key, e);
            throw new RuntimeException("Failed to set value in Redis", e);
        }
    }

    @Override
    public void delete(String key) {
        try {
            String redisKey = buildRedisKey(key);
            stringRedisTemplate.delete(redisKey);
        } catch (Exception e) {
            log.error("Error deleting key from Redis: {}", key, e);
        }
    }

    @Override
    public int size() {
        try {
            Set<String> keys = stringRedisTemplate.keys(KEY_PREFIX + "*");
            return keys != null ? keys.size() : 0;
        } catch (Exception e) {
            log.error("Error getting size from Redis", e);
            return 0;
        }
    }

    /**
     * Refresh TTL for a key (called on heartbeat/activity)
     */
    public void refreshTTL(String key) {
        try {
            String redisKey = buildRedisKey(key);
            stringRedisTemplate.expire(redisKey, DEFAULT_TTL);
        } catch (Exception e) {
            log.error("Error refreshing TTL for key: {}", key, e);
        }
    }

    private String buildRedisKey(String key) {
        return KEY_PREFIX + key;
    }
}
