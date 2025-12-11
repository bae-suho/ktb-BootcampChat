package com.ktb.chatapp.config;

import com.corundumstudio.socketio.AuthTokenListener;
import com.corundumstudio.socketio.SocketConfig;
import com.corundumstudio.socketio.SocketIOServer;
import com.corundumstudio.socketio.annotation.SpringAnnotationScanner;
import com.corundumstudio.socketio.namespace.Namespace;
import com.corundumstudio.socketio.protocol.JacksonJsonSupport;
import com.corundumstudio.socketio.store.RedissonStoreFactory;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.ktb.chatapp.websocket.socketio.ChatDataStore;
import com.ktb.chatapp.websocket.socketio.RedisChatDataStore;
import lombok.extern.slf4j.Slf4j;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.annotation.Role;

import static org.springframework.beans.factory.config.BeanDefinition.ROLE_INFRASTRUCTURE;

@Slf4j
@Configuration
@ConditionalOnProperty(name = "socketio.enabled", havingValue = "true", matchIfMissing = true)
public class SocketIOConfig {

    @Value("${socketio.server.host:0.0.0.0}")
    private String host;

    @Value("${socketio.server.port:5002}")
    private Integer port;

    // 👉 Redis A 설정 값 주입 (Session Redis 재사용)
    @Value("${spring.data.redis.host:localhost}")
    private String redisHost;

    @Value("${spring.data.redis.port:6379}")
    private Integer redisPort;

    @Value("${spring.data.redis.password:}")
    private String redisPassword;

    /**
     * Socket.IO용 Redisson 클라이언트 (Redis A 사용)
     * Session과 동일한 Redis 인스턴스를 사용하여 네트워크 오버헤드 감소
     */
    @Bean(destroyMethod = "shutdown")
    public RedissonClient socketRedisClient() {
        Config config = new Config();
        String address = "redis://" + redisHost + ":" + redisPort;

        var single = config.useSingleServer();
        single.setAddress(address);
        single.setConnectionMinimumIdleSize(50);   // 10 -> 50: 최소 유휴 연결 대폭 증가
        single.setConnectionPoolSize(500);         // 100 -> 500: 1000명 동시 연결 대비
        single.setSubscriptionConnectionMinimumIdleSize(10);
        single.setSubscriptionConnectionPoolSize(100);  // pub/sub 전용 풀

        // ⚡ 다중 EC2 환경 타임아웃 설정
        single.setConnectTimeout(10000);  // 연결 타임아웃: 10초
        single.setTimeout(5000);          // 명령 타임아웃: 5초
        single.setRetryAttempts(3);       // 재시도 횟수: 3회
        single.setRetryInterval(1500);    // 재시도 간격: 1.5초

        if (redisPassword != null && !redisPassword.isEmpty()) {
            single.setPassword(redisPassword);
        }

        log.info("╔═══════════════════════════════════════════════════════════════════════════════╗");
        log.info("║                    Socket.IO Redis(A) Configuration                           ║");
        log.info("╠═══════════════════════════════════════════════════════════════════════════════╣");
        log.info("║  Host: {}:{}", redisHost, redisPort);
        log.info("║  Password: {}", redisPassword != null && !redisPassword.isEmpty() ? "***" : "none");
        log.info("║  Use Case: Socket.IO Store + Session (Unified)                               ║");
        log.info("╚═══════════════════════════════════════════════════════════════════════════════╝");

        return Redisson.create(config);
    }

    @Bean(initMethod = "start", destroyMethod = "stop")
    public SocketIOServer socketIOServer(AuthTokenListener authTokenListener,
                                         RedissonClient socketRedisClient) {

        com.corundumstudio.socketio.Configuration config = new com.corundumstudio.socketio.Configuration();
        config.setHostname(host);
        config.setPort(port);

        SocketConfig socketConfig = new SocketConfig();
        socketConfig.setReuseAddress(true);
        socketConfig.setTcpNoDelay(true);  // true로 변경 - 지연 없이 즉시 전송
        socketConfig.setAcceptBackLog(1024);  // 10 -> 1024: 대량 동시 연결 수용
        socketConfig.setTcpSendBufferSize(65536);  // 4KB -> 64KB: 버퍼 오버플로우 방지
        socketConfig.setTcpReceiveBufferSize(65536);  // 4KB -> 64KB: 수신 버퍼 증가
        config.setSocketConfig(socketConfig);

        config.setOrigin("*");

        // Socket.IO settings
        config.setPingTimeout(60000);
        config.setPingInterval(25000);
        config.setUpgradeTimeout(30000);  // 10s -> 30s: heavy 테스트 시 핸드셰이크 타임아웃 방지

        // Netty 스레드 최적화 (대규모 동시 연결 처리)
        config.setBossThreads(8);      // Boss 스레드: 연결 수락 담당 (4 -> 8)
        config.setWorkerThreads(128);  // Worker 스레드: I/O 처리 담당 (32 -> 128, 1000+ 동시 연결 처리)

        // HTTP/WebSocket 제한 완화
        config.setMaxHttpContentLength(1048576);  // 1MB (기본값 64KB → 증가)
        config.setMaxFramePayloadLength(1048576); // 1MB WebSocket 프레임

        config.setJsonSupport(new JacksonJsonSupport(new JavaTimeModule()));

        // ✅ Redis A 기반 RedissonStoreFactory (Session과 통합)
        config.setStoreFactory(new RedissonStoreFactory(socketRedisClient));

        log.info("Socket.IO server configured on {}:{} with {} boss threads and {} worker threads",
                host, port, config.getBossThreads(), config.getWorkerThreads());

        SocketIOServer socketIOServer = new SocketIOServer(config);
        socketIOServer.getNamespace(Namespace.DEFAULT_NAME).addAuthTokenListener(authTokenListener);

        return socketIOServer;
    }

    /**
     * SpringAnnotationScanner는 BeanPostProcessor로서
     * ApplicationContext 초기화 초기에 등록되고,
     * 내부에서 사용하는 SocketIOServer는 Lazy로 지연되어
     * 다른 Bean들의 초기화 과정에 간섭하지 않게 한다.
     */
    @Bean
    @Role(ROLE_INFRASTRUCTURE)
    public BeanPostProcessor springAnnotationScanner(@Lazy SocketIOServer socketIOServer) {
        return new SpringAnnotationScanner(socketIOServer);
    }

    // ✅ ChatDataStore - Redis A 사용 (Session과 통합)
    @Bean
    @ConditionalOnProperty(name = "socketio.enabled", havingValue = "true", matchIfMissing = true)
    public ChatDataStore chatDataStore(RedissonClient socketRedisClient) {
        return new RedisChatDataStore(socketRedisClient);
    }
}
