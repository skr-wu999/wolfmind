package com.wolfmind.infrastructure.redis;

import com.wolfmind.application.events.*;
import com.wolfmind.application.game.GameArchivalService;
import com.wolfmind.application.scheduling.EventConsumptionPort;
import com.wolfmind.application.agent.trace.AgentTracePort;
import com.wolfmind.infrastructure.archive.*;
import com.wolfmind.infrastructure.memory.*;
import com.wolfmind.infrastructure.metrics.InProcessMetrics;
import com.wolfmind.infrastructure.sqlite.*;
import com.wolfmind.interfaces.ws.WebSocketGateway;
import com.wolfmind.testsupport.ArchivedMatchFixture;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.connection.stream.*;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RedisFourConsumerGroupsTest {
    static final ArchivedMatchFixture MATCH=ArchivedMatchFixture.create("g_four_groups",true);
    static LettuceConnectionFactory factory;
    static StringRedisTemplate redis;
    @TempDir Path directory;
    SqliteArchiveRepository sqlite;
    SqliteInsightRepository insight;
    RedisEventStore store;
    RedisGameStateCache cache;
    SqliteReplayArchive archive;
    ReliableEventConsumers workers;
    InProcessMetrics metrics;
    WebSocketGateway gateway;
    String jdbc;

    @BeforeAll static void connect(){factory=RedisTestSupport.connect();redis=factory==null?null:new StringRedisTemplate(factory);}
    @AfterAll static void disconnect(){if(factory!=null)factory.destroy();}
    @BeforeEach void setup(){
        RedisTestSupport.flush(redis);jdbc="jdbc:sqlite:"+directory.resolve("groups.db");
        sqlite=new SqliteArchiveRepository(jdbc);insight=new SqliteInsightRepository(jdbc);
        store=new RedisEventStore(redis);cache=new RedisGameStateCache(redis);archive=new SqliteReplayArchive(sqlite);
        metrics=new InProcessMetrics();gateway=mock(WebSocketGateway.class);
        var matchArchive=new SqliteMatchArchive(sqlite);
        var archival=new GameArchivalService(store,matchArchive,cache,store::shortenAfterArchive,MATCH.harness().time());
        var repository=new InMemoryGameRepository(store,new RedisSnapshotStore(redis,store),cache);
        var views=new InMemoryGameViewQuery(store,repository,archive);
        workers=new ReliableEventConsumers(redis,store,archive,archival,
                new GameInsightService(store,matchArchive,insight,MATCH.harness().time()),gateway,views,
                mock(com.wolfmind.application.room.GetRoomQueryService.class),metrics,0);
    }
    @AfterEach void close(){if(workers!=null)workers.close();if(insight!=null)insight.close();if(sqlite!=null)sqlite.close();}
    void append(){store.append(MATCH.gameId(),0,MATCH.events());}
    Map<String,Object> row(List<Map<String,Object>> rows,String scope,String key){
        return rows.stream().filter(r->scope.equals(r.get("scope"))&&key.equals(r.get("scope_key"))).findFirst().orElseThrow();
    }
    long number(Map<String,Object> row,String key){return ((Number)row.get(key)).longValue();}

    @Test void fourGroupsHaveIndependentProgressAndTerminalExit(){
        append();
        try(var agent=new RedisEventConsumption(redis,store)){
            var agentDelivery=agent.read(200,0).getFirst();agent.acknowledge(agentDelivery);agent.forget(MATCH.gameId());
            assertTrue(redis.opsForSet().isMember(RedisEventConsumption.directory("cg-projection"),MATCH.gameId()));
            assertEquals(MATCH.events().size(),workers.projection().consumeOnce(0));
            assertTrue(sqlite.game(MATCH.gameId()).orElseThrow().archivedThrough(MATCH.events().size()));
            assertEquals(MATCH.state(),cache.get(MATCH.gameId()));
            assertTrue(store.ttlSecondsOf(MATCH.gameId())<=RedisEventStore.GAME_TERMINAL_TTL_SECONDS);
            assertTrue(redis.getExpire(RedisKeys.scope(MATCH.gameId())+":state")>0);
            assertEquals(MATCH.events().size(),workers.insight().consumeOnce(0));
            assertEquals(MATCH.events().size(),workers.broadcast().consumeOnce(0));
            verify(gateway,times(1)).onCommittedEvents(anyList());
            for(String group:List.of("cg-agent","cg-projection","cg-broadcast","cg-insight")){
                assertEquals(0,redis.opsForStream().pending(RedisKeys.events(MATCH.gameId()),group).getTotalPendingMessages());
                assertFalse(redis.opsForSet().isMember(RedisEventConsumption.directory(group),MATCH.gameId()));
            }
            assertEquals(1,number(row(insight.statistics(null),"GLOBAL","ALL"),"games_played"));
            assertEquals(1,number(row(insight.statistics(null),"ROLE","WEREWOLF"),"games_played"));
        }
    }

    @Test void insightWaitsForArchiveAndRetriesWithoutBlockingBroadcast(){
        append();assertEquals(0,workers.insight().consumeOnce(0));
        assertEquals(MATCH.events().size(),workers.broadcast().consumeOnce(0));
        assertTrue(insight.statistics(null).isEmpty());
        assertEquals(MATCH.events().size(),workers.projection().consumeOnce(0));
        assertEquals(MATCH.events().size(),workers.insight().consumeOnce(0));
        assertEquals(1,number(row(insight.statistics(null),"GLOBAL","ALL"),"games_played"));
    }

    @Test void sameMessageAndDelayedTraceDoNotDoubleCount(){
        append();workers.projection().consumeOnce(0);workers.insight().consumeOnce(0);
        var before=insight.statistics(null);
        insight.record(MATCH.state(),store.read(MATCH.gameId(),1,Integer.MAX_VALUE),2L);
        assertEquals(number(row(before,"ROLE","WEREWOLF"),"games_won"),number(row(insight.statistics(null),"ROLE","WEREWOLF"),"games_won"));
        try(var traces=new SqliteDecisionTraceRepository(jdbc)){
            var recorder=new ArchivingAgentTrace(new InMemoryAgentTrace(),traces,store::requestInsightRebuild);
            recorder.record(new AgentTracePort.AgentTraceEvent(AgentTracePort.TraceEventType.AGENT_LLM_INVOKED,
                    MATCH.gameId(),"p_1",MATCH.state().version(),"DAY_VOTE",2,2L,
                    Map.of("promptTokens",10,"completionTokens",5,"latencyMs",7)));
            recorder.record(new AgentTracePort.AgentTraceEvent(AgentTracePort.TraceEventType.AGENT_TURN_FINISHED,
                    MATCH.gameId(),"p_1",MATCH.state().version(),"DAY_VOTE",2,3L,Map.of("fallbackUsed",true)));
            assertTrue(workers.insight().consumeOnce(0)>0);
            var result=row(insight.statistics(null),"AGENT","p_1");
            assertEquals(1,number(result,"games_played"));assertEquals(15,number(result,"token_total"));
            assertEquals(1,number(result,"llm_calls"));assertEquals(7,number(result,"llm_latency_ms_total"));
            assertEquals(1,number(result,"fallback_total"));
        }
    }

    @Test void statisticsTransactionRollsBackBothDetailsAndIndexThenRetries()throws Exception{
        append();workers.projection().consumeOnce(0);
        try(var db=DriverManager.getConnection(jdbc);var statement=db.createStatement()){
            statement.execute("CREATE TRIGGER fail_stat BEFORE INSERT ON statistics WHEN NEW.scope='ROLE' BEGIN SELECT RAISE(ABORT,'injected'); END");
            assertEquals(0,workers.insight().consumeOnce(0));assertTrue(insight.statistics(null).isEmpty());
            try(var rows=statement.executeQuery("SELECT COUNT(*) FROM game_history")){assertTrue(rows.next());assertEquals(0,rows.getInt(1));}
            assertTrue(redis.opsForStream().pending(RedisKeys.events(MATCH.gameId()),"cg-insight").getTotalPendingMessages()>0);
            statement.execute("DROP TRIGGER fail_stat");
            assertTrue(workers.insight().consumeOnce(0)>0);
            assertEquals(1,number(row(insight.statistics(null),"GLOBAL","ALL"),"games_played"));
            assertEquals(MATCH.events().size(),sqlite.game(MATCH.gameId()).orElseThrow().eventsArchived());
        }
    }

    @Test void abandonedPendingIsClaimedByANewConsumerBeforeLaterVersions(){
        append();EventConsumptionPort.Delivery old;
        try(var first=new RedisEventConsumption(redis,store,"cg-projection",archive,0)){old=first.read(5,0).getFirst();}
        try(var second=new RedisEventConsumption(redis,store,"cg-projection",archive,0)){
            var claimed=second.read(200,0).getFirst();assertEquals(old,claimed);second.acknowledge(claimed);
            var next=second.read(200,0).getFirst();assertEquals(6,next.events().getFirst().version());
        }
    }

    @Test void badMessagesBlockOnlyTheirOwnGroupAndGame(){
        append();String bad="g_bad_groups";
        redis.opsForStream().add(MapRecord.create(RedisKeys.events(bad),Map.of("version","1")).withId(RecordId.of("1-0")));
        for(String group:List.of("cg-projection","cg-broadcast","cg-insight")){
            redis.opsForSet().add(RedisEventConsumption.directory(group),bad);
            try(var reader=new RedisEventConsumption(redis,store,group,archive,0)){
                for(int attempt=0;attempt<3;attempt++)reader.read(200,0);
                assertEquals(1,reader.blockedCount());
            }
        }
        assertEquals(0,redis.opsForHash().size(RedisEventConsumption.BLOCKED));
        assertEquals(3,redis.opsForStream().size(RedisKeys.events(bad)+":dlq"));
        assertTrue(workers.projection().consumeOnce(0)>0);
    }

    @Test void partialProjectionDoesNotMarkArchiveCompleteOrExpire(){
        store.append(MATCH.gameId(),0,MATCH.events().subList(0,11));workers.projection().consumeOnce(0);
        assertEquals(11,sqlite.archivedCount(MATCH.gameId()));assertTrue(sqlite.game(MATCH.gameId()).isEmpty());
        assertEquals(-1L,store.ttlSecondsOf(MATCH.gameId()));
        assertEquals(-1L,redis.getExpire(RedisKeys.scope(MATCH.gameId())+":state"));
    }

    @Test void expiredArchiveBackfillsInsightAndRepairReceiptsDoNotLoseNewRequests(){
        append();workers.projection().consumeOnce(0);redis.delete(RedisKeys.events(MATCH.gameId()));
        assertTrue(workers.insight().consumeOnce(0)>0);
        assertEquals(1,number(row(insight.statistics(null),"GLOBAL","ALL"),"games_played"));
        store.requestInsightRebuild(MATCH.gameId());
        try(var reader=new RedisEventConsumption(redis,store,"cg-insight",archive,0)){
            var old=reader.read(200,0).getFirst();store.requestInsightRebuild(MATCH.gameId());reader.acknowledge(old);
            assertEquals(1,redis.opsForHash().size(RedisEventConsumption.repairs("cg-insight")));
            var next=reader.read(200,0).getFirst();reader.acknowledge(next);
            assertEquals(0,redis.opsForHash().size(RedisEventConsumption.repairs("cg-insight")));
        }
    }

    @Test void olderProjectionCannotRegressNewerState(){
        append();workers.projection().consumeOnce(0);
        cache.put(com.wolfmind.application.game.CommittedEventReplay.state(MATCH.gameId(),MATCH.events().subList(0,11),11));
        assertEquals(MATCH.state(),cache.get(MATCH.gameId()));
    }

    @Test void activeConsumerLeasePreventsLaterVersionsFromPassingThePrefix(){
        append();
        try(var first=new RedisEventConsumption(redis,store,"cg-projection",archive,0);
            var second=new RedisEventConsumption(redis,store,"cg-projection",archive,0)){
            var prefix=first.read(5,0).getFirst();assertTrue(second.read(200,0).isEmpty());
            first.acknowledge(prefix);assertEquals(6,second.read(200,0).getFirst().events().getFirst().version());
        }
    }

    @Test void databaseBackfillFindsACommittedTraceWhoseRedisNotificationWasLost()throws Exception{
        append();workers.projection().consumeOnce(0);workers.insight().consumeOnce(0);
        assertTrue(insight.pendingRebuildIds().isEmpty());
        try(var traces=new SqliteDecisionTraceRepository(jdbc)){
            traces.archive(new AgentTracePort.AgentTraceEvent(AgentTracePort.TraceEventType.AGENT_LLM_INVOKED,
                    MATCH.gameId(),"p_1",MATCH.state().version(),"DAY_VOTE",2,5L,
                    Map.of("promptTokens",20,"completionTokens",5,"latencyMs",8)));
        }
        assertEquals(List.of(MATCH.gameId()),insight.pendingRebuildIds());
        for(String id:insight.pendingRebuildIds())store.requestInsightRebuild(id);
        assertTrue(workers.insight().consumeOnce(0)>0);
        assertEquals(25,number(row(insight.statistics(null),"AGENT","p_1"),"token_total"));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"mixed-test","NO_HUMANS","ROOM_CLOSED"})
    void mixedAbortedMatchRecordsHumanAndRoleOnceWithoutAWin(String reason){
        String id="g_mixed_stats";
        var game=com.wolfmind.domain.game.model.Game.create(com.wolfmind.domain.game.model.GameId.of(id));
        var seats=new ArrayList<com.wolfmind.domain.game.model.SeatAssignment>();
        seats.add(com.wolfmind.domain.game.model.SeatAssignment.human(1,"u_owner"));
        for(int seat=2;seat<=9;seat++)seats.add(com.wolfmind.domain.game.model.SeatAssignment.ai(seat,"p_"+seat));
        var command=new com.wolfmind.domain.game.model.StartGameCommand(com.wolfmind.domain.game.model.GameId.of(id),"room_mixed",seats,
                com.wolfmind.domain.game.model.RoleComposition.STANDARD_NINE,com.wolfmind.domain.game.model.GameRules.DEFAULT);
        var initial=game.start(command,new com.wolfmind.domain.game.model.ActionContext("start-mixed",1L,"SYSTEM","start","start",0,42L));
        store.append(id,0,initial.stream().map(e->com.wolfmind.domain.game.event.EventEnvelope.of(e,id,e.version())).toList());
        int version=game.version();
        var ended=game.abort(reason,new com.wolfmind.domain.game.model.ActionContext("abort-mixed",2L,"SYSTEM","abort","abort",version,0L));
        store.append(id,version,ended.stream().map(e->com.wolfmind.domain.game.event.EventEnvelope.of(e,id,e.version())).toList());
        assertTrue(workers.projection().consumeOnce(0)>0);assertTrue(workers.insight().consumeOnce(0)>0);
        var person=row(insight.statistics(id),"PLAYER","u_owner");
        int scored = reason.equals("mixed-test") ? 1 : 0;
        assertEquals(scored,number(person,"games_played"));assertEquals(0,number(person,"games_won"));
        assertEquals(scored,number(row(insight.statistics(id),"ROLE","WEREWOLF"),"games_played"));
    }
}
