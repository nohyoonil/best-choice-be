package com.winnow.bestchoice.repository;

import com.winnow.bestchoice.entity.Post;
import com.winnow.bestchoice.exception.CustomException;
import com.winnow.bestchoice.exception.ErrorCode;
import com.winnow.bestchoice.model.dto.ChatRoom;
import com.winnow.bestchoice.model.dto.ChatRoomPage;
import com.winnow.bestchoice.model.response.ChatRoomResponse;
import com.winnow.bestchoice.service.RedisSubscriber;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.*;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.util.*;
import java.util.stream.Collectors;

@RequiredArgsConstructor
@Service
public class ChatRoomRepository {
    // Redis CacheKeys
    private static final String CHAT_ROOMS = "CHAT_ROOM"; // 채팅룸 저장
    public static final String USER_COUNT = "USER_COUNT"; // 채팅룸에 입장한 클라이언트수 저장
    public static final String ENTER_INFO = "ENTER_INFO"; // 채팅룸에 입장한 클라이언트의 sessionId와 채팅룸 id를 맵핑한 정보 저장


    private final PostRepository postRepository;

    @Resource(name = "redisTemplate")
    private HashOperations<String, String, ChatRoom> hashOpsChatRoom;
    @Resource(name = "redisTemplate")
    private HashOperations<String, String, String> hashOpsEnterInfo;
    @Resource(name = "redisTemplate")
    private ValueOperations<String, String> valueOps;

    // 특정 채팅방 조회
    public ChatRoom findChatRoomByRoomId(String roomId) {
        return Optional.ofNullable(hashOpsChatRoom.get(CHAT_ROOMS, roomId)).orElseThrow(
                () -> new CustomException(ErrorCode.CHATROOM_NOT_FOUND)
        );
    }

    // 채팅방 목록 조회
    public ChatRoomPage<List<ChatRoomResponse>> findAllChatRoom(int pageNumber, int pageSize) {
        ArrayList<ChatRoomResponse> chatRooms = new ArrayList<>();
        Set<String> roomIds = hashOpsChatRoom.keys(CHAT_ROOMS);

        for (String roomId : roomIds) {
            Post post = postRepository.findById(Long.parseLong(roomId)).orElseThrow(
                    () -> new CustomException(ErrorCode.POST_NOT_FOUND));
            ChatRoom chatRoom =  hashOpsChatRoom.get(CHAT_ROOMS, roomId);

            ChatRoomResponse chatRoomResponse = ChatRoomResponse.fromEntity(post, Objects.requireNonNull(chatRoom));

            chatRooms.add(chatRoomResponse);
        }

        chatRooms.sort((o1, o2) -> o2.getCreatedDate().compareTo(o1.getCreatedDate()));
        ChatRoomPage<?> chatRoomPage = new ChatRoomPage<>(chatRooms, pageSize);

        return (ChatRoomPage<List<ChatRoomResponse>>) chatRoomPage.getPage(pageNumber);
    }

    // 채팅방 생성
    public ChatRoom createChatRoom(String roomId) {
        ChatRoom chatRoom = ChatRoom.create(roomId);
        hashOpsChatRoom.put(CHAT_ROOMS, roomId, chatRoom);
        return chatRoom;
    }

    // 유저가 입장한 채팅방ID와 유저 세션ID 맵핑 정보 저장
    public void setUserEnterInfo(String sessionId, String roomId) {
        hashOpsEnterInfo.put(ENTER_INFO, sessionId, roomId);
    }

    // 유저 세션으로 입장해 있는 채팅방 ID 조회
    public String getUserEnterRoomId(String sessionId) {
        return hashOpsEnterInfo.get(ENTER_INFO, sessionId);
    }

    // 유저 세션정보와 맵핑된 채팅방ID 삭제
    public void removeUserEnterInfo(String sessionId) {
        hashOpsEnterInfo.delete(ENTER_INFO, sessionId);
    }


    // 채팅방 유저수 조회
    public long getUserCount(String roomId) {
        return Long.parseLong( Optional.ofNullable(valueOps.get(USER_COUNT + "_" + roomId)).orElse("0"));
    }

    // 채팅방에 입장한 유저수 +1
    public long plusUserCount(String roomId) {
        return Optional.ofNullable(valueOps.increment(USER_COUNT + "_" + roomId)).orElse(0L);
    }

    // 채팅방에 입장한 유저수 -1
    public long minusUserCount(String roomId) {
        return Optional.ofNullable(valueOps.decrement(USER_COUNT + "_" + roomId))
                .filter(count -> count > 0).orElse(0L);
    }

    // 채팅방 삭제
    public void deleteChatRoom(String roomId) {
        hashOpsChatRoom.delete(CHAT_ROOMS, roomId);
        valueOps.getAndDelete(USER_COUNT + "_" + roomId);
    }

}
