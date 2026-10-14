package com.wolfmind.infrastructure.sqlite;

import com.wolfmind.application.port.InsightPort;
import com.wolfmind.domain.game.event.EventEnvelope;
import com.wolfmind.domain.game.model.*;
import com.wolfmind.infrastructure.json.Json;
import com.wolfmind.infrastructure.memory.GameSnapshotCodec;
import java.sql.*;
import java.util.*;

/** 单局明细、回放索引和累计重算在同一 SQLite 事务提交。 */
public final class SqliteInsightRepository implements InsightPort, AutoCloseable {
    private final Connection connection;
    public SqliteInsightRepository(String jdbcUrl) {
        try { connection = SqliteSupport.open(jdbcUrl); SqliteSupport.applySchema(connection); }
        catch (Exception error) { throw new SqliteArchiveRepository.ArchiveException("Open insight database failed", error); }
    }

    private record Detail(String scope, String key, String camp, int won, int survivors,
                          long tokens, long calls, long latency, long fallback) { }

    @Override public synchronized void record(GameSnapshot state, List<EventEnvelope> events, long now) {
        String id = state.gameId().value();
        try {
            execute("BEGIN IMMEDIATE");
            try (var check = connection.prepareStatement("SELECT version,events_archived,status FROM games WHERE game_id=?")) {
                check.setString(1, id);
                try (var rows = check.executeQuery()) {
                    if (!rows.next() || rows.getInt(1) != state.version() || rows.getInt(2) != state.version()
                            || !rows.getString(3).equals(state.status().name())) throw new SQLException("Verified archive not ready");
                }
            }
            try (var update = connection.prepareStatement("UPDATE games SET phase=?,round=?,winner=?,finished_at=?,updated_at=? WHERE game_id=?")) {
                update.setString(1, state.phase().name()); update.setInt(2, state.round());
                update.setString(3, state.winner() == null ? null : state.winner().name());
                update.setLong(4, events.getLast().occurredAt()); update.setLong(5, now); update.setString(6, id); update.executeUpdate();
            }
            String snapshot = GameSnapshotCodec.encode(state);
            history(id, "SNAPSHOT", state, snapshot, GameSnapshotCodec.checksum(snapshot), events.getLast().occurredAt(), now);
            var summary = new LinkedHashMap<String,Object>();
            summary.put("players", state.players()); summary.put("winner", state.winner() == null ? null : state.winner().name());
            summary.put("rounds", state.round());
            summary.put("keyVersions", Map.of("firstEventVersion", 1, "lastEventVersion", state.version(),
                    "voteResolutions", events.stream().filter(e -> e.type().equals("VOTE_TALLY_RESOLVED")).map(EventEnvelope::version).toList()));
            history(id, "SUMMARY", state, Json.write(summary), null, events.getLast().occurredAt(), now);
            for (var detail : details(state)) {
                boolean abandoned = state.status() == GameStatus.ABORTED
                        && ("NO_HUMANS".equals(state.finishReason()) || "ROOM_CLOSED".equals(state.finishReason()));
                writeDetail(id, state.round(), detail, now, abandoned ? 0 : 1);
                rebuild(detail.scope(), detail.key(), now);
            }
            execute("COMMIT");
        } catch (Exception error) {
            try { execute("ROLLBACK"); } catch (SQLException ignored) { }
            throw new SqliteArchiveRepository.ArchiveException("Insight transaction failed", error);
        }
    }

    private List<Detail> details(GameSnapshot state) throws SQLException {
        var list = new ArrayList<Detail>();
        list.add(new Detail("GLOBAL", "ALL", null, 0, (int)state.players().stream().filter(PlayerSnapshot::alive).count(), 0,0,0,0));
        for (Role role : Role.values()) {
            var members = state.players().stream().filter(p -> p.role() == role).toList();
            if (!members.isEmpty()) list.add(new Detail("ROLE", role.name(), members.getFirst().camp().name(),
                    state.winner() == members.getFirst().camp() ? 1 : 0,
                    (int) members.stream().filter(PlayerSnapshot::alive).count(),0,0,0,0));
        }
        for (var player : state.players()) {
            boolean ai = player.controller().type() == ControllerType.AI;
            if (!ai && player.controller().type() != ControllerType.HUMAN) continue;
            String key = ai ? player.playerId().value() : player.controller().refId();
            long tokens=0, calls=0, latency=0, fallback=0;
            if (ai) try (var query = connection.prepareStatement("SELECT source_type,content FROM agent_memory WHERE game_id=? AND agent_id=? AND memory_type='DECISION_TRACE'")) {
                query.setString(1,state.gameId().value()); query.setString(2,key);
                try (var rows = query.executeQuery()) {
                    while(rows.next()) {
                        var payload = Json.readObject(rows.getString(2));
                        if (rows.getString(1).equals("AGENT_LLM_INVOKED")) {
                            calls++; tokens += number(payload,"promptTokens") + number(payload,"completionTokens");
                            latency += number(payload,"latencyMs");
                        }
                        if (rows.getString(1).equals("AGENT_TURN_FINISHED") && Boolean.TRUE.equals(payload.get("fallbackUsed"))) fallback++;
                    }
                }
            }
            list.add(new Detail(ai ? "AGENT" : "PLAYER", key, player.camp().name(), state.winner() == player.camp() ? 1 : 0,
                    player.alive() ? 1 : 0, tokens,calls,latency,fallback));
        }
        return list;
    }
    private static long number(Map<String,Object> payload, String key) { return payload.get(key) instanceof Number n ? n.longValue() : 0L; }

    private void writeDetail(String id, int rounds, Detail d, long now, int scoredGame) throws SQLException {
        try (var statement = connection.prepareStatement("""
                INSERT INTO statistics(scope,scope_key,game_id,camp,games_played,games_won,win_rate,rounds_total,survivors,
                  token_total,llm_calls,llm_latency_ms_total,fallback_total,rebuilt_at,updated_at)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                ON CONFLICT(scope,scope_key,game_id) WHERE game_id IS NOT NULL DO UPDATE SET
                  camp=excluded.camp,games_played=excluded.games_played,games_won=excluded.games_won,win_rate=excluded.win_rate,
                  rounds_total=excluded.rounds_total,survivors=excluded.survivors,token_total=excluded.token_total,
                  llm_calls=excluded.llm_calls,llm_latency_ms_total=excluded.llm_latency_ms_total,fallback_total=excluded.fallback_total,
                  rebuilt_at=excluded.rebuilt_at,updated_at=excluded.updated_at
                """)) {
            int index=1; statement.setString(index++,d.scope()); statement.setString(index++,d.key()); statement.setString(index++,id);
            statement.setString(index++,d.camp()); statement.setInt(index++,scoredGame);
            statement.setInt(index++,d.won()); statement.setDouble(index++,d.won());
            statement.setInt(index++,rounds); statement.setInt(index++,d.survivors()); statement.setLong(index++,d.tokens());
            statement.setLong(index++,d.calls()); statement.setLong(index++,d.latency()); statement.setLong(index++,d.fallback());
            statement.setLong(index++,now); statement.setLong(index,now); statement.executeUpdate();
        }
    }
    private void rebuild(String scope, String key, long now) throws SQLException {
        try (var delete = connection.prepareStatement("DELETE FROM statistics WHERE scope=? AND scope_key=? AND game_id IS NULL")) {
            delete.setString(1,scope); delete.setString(2,key); delete.executeUpdate();
        }
        try (var insert = connection.prepareStatement("""
                INSERT INTO statistics(scope,scope_key,game_id,camp,games_played,games_won,win_rate,rounds_total,survivors,
                  token_total,llm_calls,llm_latency_ms_total,fallback_total,rebuilt_at,updated_at)
                SELECT scope,scope_key,NULL,NULL,SUM(games_played),SUM(games_won),
                  CASE WHEN SUM(games_played)=0 THEN 0 ELSE 1.0*SUM(games_won)/SUM(games_played) END,
                  SUM(rounds_total),SUM(survivors),SUM(token_total),SUM(llm_calls),SUM(llm_latency_ms_total),SUM(fallback_total),?,?
                FROM statistics WHERE scope=? AND scope_key=? AND game_id IS NOT NULL GROUP BY scope,scope_key
                """)) {
            insert.setLong(1,now); insert.setLong(2,now); insert.setString(3,scope); insert.setString(4,key); insert.executeUpdate();
        }
    }
    private void history(String id, String kind, GameSnapshot state, String content, String checksum, long occurred, long now) throws SQLException {
        String conflict = kind.equals("SUMMARY") ? "(game_id) WHERE kind='SUMMARY'" : "(game_id,version) WHERE kind='SNAPSHOT'";
        try (var statement = connection.prepareStatement("INSERT INTO game_history(game_id,kind,version,phase,round,content,checksum,occurred_at,created_at)"
                + " VALUES(?,?,?,?,?,?,?,?,?) ON CONFLICT" + conflict + " DO UPDATE SET version=excluded.version,phase=excluded.phase,round=excluded.round,content=excluded.content,checksum=excluded.checksum,occurred_at=excluded.occurred_at")) {
            statement.setString(1,id); statement.setString(2,kind); statement.setInt(3,state.version()); statement.setString(4,state.phase().name());
            statement.setInt(5,state.round()); statement.setString(6,content); statement.setString(7,checksum); statement.setLong(8,occurred); statement.setLong(9,now); statement.executeUpdate();
        }
    }
    private void execute(String sql) throws SQLException { try (var statement=connection.createStatement()) { statement.execute(sql); } }

    public synchronized List<Map<String,Object>> statistics(String gameId) {
        var result=new ArrayList<Map<String,Object>>();
        try (var query=connection.prepareStatement("SELECT * FROM statistics WHERE game_id IS ? ORDER BY scope,scope_key")) {
            query.setString(1,gameId);
            try (var rows=query.executeQuery()) {
                while(rows.next()) {
                    var item=new LinkedHashMap<String,Object>();
                    for(int i=1;i<=rows.getMetaData().getColumnCount();i++) item.put(rows.getMetaData().getColumnName(i),rows.getObject(i));
                    result.add(item);
                }
            }
            return result;
        } catch(SQLException error) { throw new SqliteArchiveRepository.ArchiveException("Read statistics failed",error); }
    }
    public synchronized List<String> pendingRebuildIds() {
        var ids=new ArrayList<String>();
        try(var statement=connection.createStatement();var rows=statement.executeQuery("""
                SELECT g.game_id FROM games g WHERE g.status IN ('FINISHED','ABORTED')
                  AND g.version=g.events_archived AND g.version>0 AND (
                    NOT EXISTS(SELECT 1 FROM statistics s WHERE s.game_id=g.game_id AND s.scope='GLOBAL' AND s.scope_key='ALL')
                    OR EXISTS(SELECT 1 FROM agent_memory a WHERE a.game_id=g.game_id
                      AND a.created_at >= COALESCE((SELECT MIN(s.updated_at) FROM statistics s WHERE s.game_id=g.game_id),0)))
                ORDER BY g.game_id
                """)){
            while(rows.next())ids.add(rows.getString(1));return List.copyOf(ids);
        }catch(SQLException error){throw new SqliteArchiveRepository.ArchiveException("Find pending insight backfill failed",error);}
    }
    @Override public void close() { try { connection.close(); } catch(SQLException error) { throw new IllegalStateException(error); } }
}
