package com.wolfmind.infrastructure.redis;

import com.wolfmind.WolfMindApplication;
import com.wolfmind.infrastructure.json.Json;
import com.wolfmind.infrastructure.memory.InMemoryEventPublisher;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class RedisBroadcastLiveTest {
    static LettuceConnectionFactory factory;
    static StringRedisTemplate redis;
    @TempDir Path directory;
    @BeforeAll static void connect(){factory=RedisTestSupport.connect();redis=factory==null?null:new StringRedisTemplate(factory);}
    @AfterAll static void disconnect(){if(factory!=null)factory.destroy();}

    @Test void committedRoomChangesReachTheRealWebSocketThroughTheConsumerGroup()throws Exception{
        RedisTestSupport.flush(redis);
        try(var context=new SpringApplicationBuilder(WolfMindApplication.class).properties(
                "wolfmind.storage=redis","spring.data.redis.host="+RedisTestSupport.host(),
                "spring.data.redis.port="+RedisTestSupport.port(),"spring.data.redis.database=15",
                "wolfmind.sqlite.path="+directory.resolve("live.db"),"server.port=0",
                "wolfmind.game.driver.enabled=false","wolfmind.lab.enabled=false",
                "wolfmind.ws.heartbeat.enabled=false","wolfmind.auth.pbkdf2-iterations=1",
                "wolfmind.auth.viewer-resolver=session").run()){
            int port=((WebServerApplicationContext)context).getWebServer().getPort();
            var rest=new TestRestTemplate(new RestTemplateBuilder().rootUri("http://localhost:"+port+"/api/v1"));
            String username="group_"+UUID.randomUUID().toString().substring(0,8);
            var credentials=Map.of("username",username,"password","test-consumer-password");
            assertEquals(201,rest.postForEntity("/auth/register",credentials,Map.class).getStatusCode().value());
            String token=(String)rest.postForEntity("/auth/login",credentials,Map.class).getBody().get("sessionToken");
            var headers=new HttpHeaders();headers.setBearerAuth(token);headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("Idempotency-Key",UUID.randomUUID().toString());
            var created=rest.exchange("/rooms",HttpMethod.POST,new HttpEntity<>(Map.of("name","group-room"),headers),Map.class);
            assertEquals(201,created.getStatusCode().value());String roomId=(String)created.getBody().get("roomId");
            var listener=new Listener();
            var socket=HttpClient.newHttpClient().newWebSocketBuilder().buildAsync(URI.create("ws://localhost:"+port+"/ws"),listener).join();
            try{
                socket.sendText(Json.write(Map.of("v",1,"id","auth","type","AUTH","payload",Map.of("token",token))),true).join();
                assertNotNull(listener.await("AUTH_OK"));
                socket.sendText(Json.write(Map.of("v",1,"id","subscribe","type","ROOM_SUBSCRIBE","payload",Map.of("roomId",roomId))),true).join();
                assertNotNull(listener.await("ROOM_SNAPSHOT"));
                headers.set("Idempotency-Key",UUID.randomUUID().toString());
                var ready=rest.exchange("/rooms/"+roomId+"/ready",HttpMethod.POST,new HttpEntity<>(Map.of(),headers),Map.class);
                assertEquals(200,ready.getStatusCode().value());
                Map<String,Object> updated=null;
                for(int attempt=0;attempt<10;attempt++){
                    var frame=listener.await("ROOM_SNAPSHOT");if(frame==null)break;
                    Map<?,?> payload=(Map<?,?>)frame.get("payload");Map<?,?> room=(Map<?,?>)payload.get("room");
                    if(room!=null&&((Number)room.get("readyCount")).intValue()==1){updated=frame;break;}
                }
                assertNotNull(updated,"Committed ready change should be delivered");
                assertTrue(context.getBean(InMemoryEventPublisher.class).realtimePublished().isEmpty(),"Redis mode must not also broadcast directly");
                assertNotNull(redis.opsForStream().pending(RedisKeys.events("room:"+roomId),"cg-broadcast"));
            }finally{socket.sendClose(WebSocket.NORMAL_CLOSURE,"done").join();}
        }
        RedisTestSupport.flush(redis);
    }
    private static final class Listener implements WebSocket.Listener{
        final BlockingQueue<Map<String,Object>> frames=new LinkedBlockingQueue<>();
        final StringBuilder partial=new StringBuilder();
        @Override public void onOpen(WebSocket socket){socket.request(1);}
        @Override public CompletionStage<?> onText(WebSocket socket,CharSequence text,boolean last){
            partial.append(text);if(last){frames.add(Json.readObject(partial.toString()));partial.setLength(0);}
            socket.request(1);return CompletableFuture.completedFuture(null);
        }
        Map<String,Object> await(String type)throws InterruptedException{
            long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
            while(System.nanoTime()<end){var frame=frames.poll(100,TimeUnit.MILLISECONDS);if(frame!=null&&type.equals(frame.get("type")))return frame;}
            return null;
        }
    }
}
