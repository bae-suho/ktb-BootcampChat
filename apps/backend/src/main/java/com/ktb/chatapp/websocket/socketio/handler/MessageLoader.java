package com.ktb.chatapp.websocket.socketio.handler;

import com.ktb.chatapp.dto.FetchMessagesRequest;
import com.ktb.chatapp.dto.FetchMessagesResponse;
import com.ktb.chatapp.dto.MessageResponse;
import com.ktb.chatapp.model.Message;
import com.ktb.chatapp.model.User;
import com.ktb.chatapp.repository.MessageRepository;
import com.ktb.chatapp.repository.UserRepository;
import com.ktb.chatapp.service.MessageReadStatusService;
import jakarta.annotation.Nullable;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Objects;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;

import static java.util.Collections.emptyList;

@Slf4j
@Component
@RequiredArgsConstructor
public class MessageLoader {

    private final MessageRepository messageRepository;
    private final UserRepository userRepository;
    private final MessageResponseMapper messageResponseMapper;
    private final MessageReadStatusService messageReadStatusService;

    private static final int BATCH_SIZE = 30;

    /**
     * 메시지 로드
     */
    public FetchMessagesResponse loadMessages(FetchMessagesRequest data, String userId) {
        try {
            return loadMessagesInternal(
                    data.roomId(),
                    data.limit(BATCH_SIZE),
                    data.before(LocalDateTime.now()),
                    userId
            );
        } catch (Exception e) {
            log.error("Error loading initial messages for room {}", data.roomId(), e);
            return FetchMessagesResponse.builder()
                    .messages(emptyList())
                    .hasMore(false)
                    .build();
        }
    }

    private FetchMessagesResponse loadMessagesInternal(
            String roomId,
            int limit,
            LocalDateTime before,
            String userId
    ) {
        Pageable pageable = PageRequest.of(0, limit, Sort.by("timestamp").descending());

        Page<Message> messagePage = messageRepository
                .findByRoomIdAndIsDeletedAndTimestampBefore(roomId, false, before, pageable);

        List<Message> messages = messagePage.getContent();

        // DESC 로 조회했으므로 UI 표시용 ASC 정렬
        List<Message> sortedMessages = messages.reversed();

        // 읽음 처리
        var messageIds = sortedMessages.stream()
                .map(Message::getId)
                .toList();
        messageReadStatusService.updateReadStatus(messageIds, userId);

        // 🔥 N+1 제거 포인트: senderId를 한 번에 모아서 유저를 배치 조회
        Map<String, User> userMap = loadUsersForMessages(sortedMessages);

        // 메시지 응답 생성
        List<MessageResponse> messageResponses = sortedMessages.stream()
                .map(message -> {
                    User sender = null;
                    String senderId = message.getSenderId();
                    if (senderId != null) {
                        sender = userMap.get(senderId);
                    }
                    // sender 가 null 이면 AI/시스템 메시지 같은 케이스
                    return messageResponseMapper.mapToMessageResponse(message, sender);
                })
                .collect(Collectors.toList());

        boolean hasMore = messagePage.hasNext();

        log.debug("Messages loaded - roomId: {}, limit: {}, count: {}, hasMore: {}",
                roomId, limit, messageResponses.size(), hasMore);

        return FetchMessagesResponse.builder()
                .messages(messageResponses)
                .hasMore(hasMore)
                .build();
    }

    /**
     * 메시지 리스트에 등장하는 senderId를 한 번에 조회해서 Map으로 캐싱
     */
    private Map<String, User> loadUsersForMessages(List<Message> messages) {
        // 메시지에서 senderId만 뽑아서 Set으로(중복 제거)
        Set<String> senderIds = messages.stream()
                .map(Message::getSenderId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        if (senderIds.isEmpty()) {
            return Map.of(); // 빈 Map 리턴
        }

        // MongoRepository / CrudRepository 공통 메서드: findAllById(Iterable<ID>)
        List<User> users = userRepository.findAllById(senderIds);

        // id -> User 형태의 Map으로 변환
        return users.stream()
                .collect(Collectors.toMap(User::getId, u -> u));
    }

    /**
     * AI 경우 null 반환 가능 (다른 데서 쓸 수 있으니 남겨도 되고, 안 쓰면 제거해도 됨)
     */
    @Nullable
    private User findUserById(String id) {
        if (id == null) {
            return null;
        }
        return userRepository.findById(id)
                .orElse(null);
    }
}