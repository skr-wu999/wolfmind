package com.wolfmind.application.scheduling;

import com.wolfmind.application.agent.llm.LlmClient;
import com.wolfmind.application.agent.model.*;
import com.wolfmind.application.agent.runtime.*;
import com.wolfmind.application.agent.strategy.WolfMindStrategy;
import com.wolfmind.application.game.TimeoutApplicationService;
import com.wolfmind.domain.game.event.EventEnvelope;
import com.wolfmind.domain.game.model.*;
import com.wolfmind.infrastructure.agent.*;
import com.wolfmind.infrastructure.agent.wiring.InfrastructureGameHarness;
import com.wolfmind.infrastructure.json.Json;
import com.wolfmind.infrastructure.memory.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;
import static org.junit.jupiter.api.Assertions.*;

class EventDrivenTurnSchedulerTest {
    static final String ID = "g_event";
    static final class CandidateModel implements LlmClient {
        final AtomicInteger calls = new AtomicInteger();
        final AtomicInteger queries = new AtomicInteger();
        final AtomicInteger concurrentVotes = new AtomicInteger();
        final AtomicInteger maxVotes = new AtomicInteger();
        final CountDownLatch overlap = new CountDownLatch(2);
        @Override public boolean available() { return true; }
        @Override public String providerId() { return "fake"; }
        @Override public InteractionMode interactionMode() { return InteractionMode.FUNCTION_CALLING; }
        @Override public LlmResponse chat(LlmRequest request) {
            int call = calls.incrementAndGet();
            if (request.messages().stream().noneMatch(m -> m.role().equals("tool"))) {
                queries.incrementAndGet(); return tools(call, "QueryGameStateTool", Map.of("scope", "SELF"));
            }
            var candidate = Pattern.compile("(?m)^- ([A-Z_]+)(?: (\\d+)号)? \\| score=").matcher(request.messages().get(1).content());
            assertTrue(candidate.find());
            String action = candidate.group(1), target = candidate.group(2);
            Map<String,Object> args = new LinkedHashMap<>();
            if (target != null) args.put("targetId", "p_"+target);
            String tool = switch (action) {
                case "SPEAK" -> { args.put("content", "我会结合公开发言判断，目前的信息还有分歧。发言序号"+call+"。"); yield "SpeakTool"; }
                case "SKIP" -> { args.put("skip", true); yield "SpeakTool"; }
                case "VOTE" -> { if (target == null) args.put("abstain", true); yield "SubmitVoteTool"; }
                case "WOLF_KILL" -> { if (target == null) args.put("empty", true); yield "KillPlayerTool"; }
                default -> { args.put("skill", action); yield "UseSkillTool"; }
            };
            if (action.equals("VOTE")) {
                int parallel = concurrentVotes.incrementAndGet(); maxVotes.accumulateAndGet(parallel, Math::max);
                overlap.countDown();
                try { assertTrue(overlap.await(3, TimeUnit.SECONDS)); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                finally { concurrentVotes.decrementAndGet(); }
            }
            return tools(call, tool, args);
        }
        static LlmResponse tools(int id, String name, Map<String,Object> args) {
            return new LlmResponse.Success("", "fake", 10, 5, 1,
                    List.of(new ToolCall("call_"+id, name, Json.write(args))), null);
        }
    }
    static final class Fixture implements AutoCloseable {
        final InfrastructureGameHarness harness = new InfrastructureGameHarness(1_730_000_000_000L);
        final InMemoryTurnTaskStore tasks = new InMemoryTurnTaskStore();
        final TurnPlanner planner;
        final InMemoryEventConsumption events;
        final EventDrivenTurnScheduler scheduler;
        Fixture(LlmClient model, SpeechPacing pacing, int concurrency, int queue) {
            planner = new TurnPlanner(harness.repository(), harness.eventStore(), pacing);
            harness.submitService().bindTaskGuard(new TaskSubmissionGuard(tasks, planner));
            var views = new AgentViewAdapter(harness.viewQuery());
            var trace = new InMemoryAgentTrace();
            var gateway = new ToolGatewayAdapter(views, harness.viewQuery(), harness.submitService(), harness.idempotency(), trace, harness.time());
            gateway.bindTaskStore(tasks);
            var runtime = new AgentRuntimeImpl(views, new WolfMindStrategy(gateway), gateway, new InMemoryMemoryPort(), trace, model,
                    harness.time(), new BudgetGuard(harness.time()), new com.wolfmind.infrastructure.ai.WorkflowEngineSelection(null));
            runtime.bindTaskStore(tasks);
            runtime.validateWorkflowEngine();
            var config = model == null ? AgentConfig.ruleBaseline("event-test") : new AgentConfig("event", "event", "fake", .6, .2,
                    AgentConfig.StrategyProfile.BALANCED, "中性", AgentConfig.WorkflowMode.A, 60, .5, 8, 3, "default", TurnBudget.FUNCTION_CALLING);
            var adapter = new AIControllerAdapter(runtime, views, gateway, harness.time(), config);
            events = new InMemoryEventConsumption(harness.eventStore(), harness.eventStore());
            scheduler = new EventDrivenTurnScheduler(events, tasks, planner, adapter,
                    new TimeoutApplicationService(harness.repository(), harness.lock(), harness.publisher(), harness.time(), planner),
                    harness.time(), com.wolfmind.application.port.MetricsPort.NOOP, concurrency, queue);
        }
        GameSnapshot state() { return harness.repository().load(ID).game().snapshot(); }
        void start(long seed) { harness.startNineAiGame(ID, RoleComposition.STANDARD_NINE, GameRules.DEFAULT, seed); }
        @Override public void close() { scheduler.close(); }
    }
    static void await(BooleanSupplier condition) throws Exception {
        long end = System.nanoTime()+TimeUnit.SECONDS.toNanos(15);
        while (!condition.getAsBoolean() && System.nanoTime()<end) Thread.sleep(10);
        assertTrue(condition.getAsBoolean(), "等待条件未达成");
    }

    @ParameterizedTest @ValueSource(longs={7,42,20261013})
    void nineNativeAgentsFinishWithoutGamePolling(long seed) throws Exception {
        CandidateModel model = new CandidateModel();
        try (Fixture f = new Fixture(model, SpeechPacing.NONE, 8, 64)) {
            f.start(seed); f.scheduler.start();
            await(() -> f.state().status() == GameStatus.FINISHED);
            assertTrue(model.queries.get() > 9); assertTrue(model.maxVotes.get() >= 2);
            Game replayed = Game.create(GameId.of(ID));
            f.harness.eventStream(ID).forEach(e -> replayed.replay(com.wolfmind.domain.game.event.EventCodec.decode(e)));
            assertEquals(f.state(), replayed.snapshot());
        }
    }
    @Test void planningRepeatedlyPreservesClaimAndDispatchVersion() {
        try (Fixture f = new Fixture(null, SpeechPacing.NONE, 1, 1)) {
            f.start(7); var plan = f.planner.plan(ID); f.tasks.reconcile(ID, plan.tasks());
            var action = plan.tasks().stream().filter(t -> t.kind() == TurnTask.Kind.ACTION).findFirst().orElseThrow();
            var claimed = f.tasks.claim(action.taskId(), "owner", f.harness.now(), 60_000).orElseThrow();
            assertTrue(f.tasks.reserve(claimed.fence(), "MODEL", 1, f.harness.now()));
            f.tasks.reconcile(ID, f.planner.plan(ID).tasks());
            assertEquals(claimed, f.tasks.tasks(ID).stream().filter(t -> t.taskId().equals(action.taskId())).findFirst().orElseThrow());
            assertFalse(f.tasks.reserve(claimed.fence(), "MODEL", 99, f.harness.now()));
            assertTrue(f.tasks.claim(action.taskId(), "other", f.harness.now(), 60_000).isEmpty());
        }
    }

    @Test void pollingFallbackCompletesAMatchThroughTheApplicationScheduler() throws Exception {
        try (Fixture f = new Fixture(null, SpeechPacing.NONE, 8, 64)) {
            f.start(7);
            f.scheduler.start(false);
            await(() -> {
                f.scheduler.reconcileGame(ID);
                return f.state().status() == GameStatus.FINISHED;
            });
            Game replayed = Game.create(GameId.of(ID));
            f.harness.eventStream(ID).forEach(e -> replayed.replay(com.wolfmind.domain.game.event.EventCodec.decode(e)));
            assertEquals(f.state(), replayed.snapshot());
            assertFalse(f.events.read(200, 0).isEmpty(), "Polling does not consume or acknowledge the event stream");
        }
    }
    @Test void registrationFailureLeavesDeliveryUnacknowledged() {
        var harness = new InfrastructureGameHarness(1000);
        harness.startNineAiGame(ID, RoleComposition.STANDARD_NINE, GameRules.DEFAULT, 7);
        var consumer = new InMemoryEventConsumption(harness.eventStore(), harness.eventStore());
        var delivery = consumer.read(200, 0).getFirst();
        assertEquals(delivery, consumer.read(200, 0).getFirst());
        consumer.acknowledge(delivery);
        assertTrue(consumer.read(200, 0).isEmpty()); consumer.close();
    }
    @Test void interruptedModelTaskWaitsForDeadlineAndCannotBeReclaimed() {
        var store = new InMemoryTurnTaskStore();
        var task = TurnTask.ready(ID, TurnTask.Kind.ACTION, "p_1", 1, "NIGHT_WOLF", 1, 11, 0, "p_1", "AI", 11, 100, 1000);
        store.reconcile(ID, List.of(task));
        var running = store.claim(task.taskId(), "dead-process", 100, 100).orElseThrow();
        assertEquals(1, store.recover(201));
        assertFalse(store.valid(running.fence(), 201));
        assertTrue(store.claim(task.taskId(), "restart", 201, 100).isEmpty());
        assertEquals(TurnTask.Status.WAITING_DEADLINE, store.tasks(ID).getFirst().status());
    }
    @Test void staleControllerTaskIsRejectedInsideApplicationWriteBoundary() {
        try (Fixture f = new Fixture(null, SpeechPacing.NONE, 1, 1)) {
            f.start(7); var plan = f.planner.plan(ID); f.tasks.reconcile(ID, plan.tasks());
            var action = plan.tasks().stream().filter(t -> t.kind()==TurnTask.Kind.ACTION).findFirst().orElseThrow();
            var claimed = f.tasks.claim(action.taskId(), "owner", f.harness.now(), 60_000).orElseThrow();
            Game game = f.harness.repository().load(ID).game(); int version = game.version();
            game.changeController(SeatNo.of(action.seatNo()), PlayerController.human("u_human"),
                    new ActionContext("controller", f.harness.now(), "SYSTEM", "controller", "controller", version, 0));
            List<EventEnvelope> changed = new ArrayList<>();
            for (var event : game.pendingEvents()) changed.add(EventEnvelope.of(event, ID, ++version));
            f.harness.repository().commit(ID, changed.getFirst().version()-1, changed,
                    com.wolfmind.application.port.GameRepositoryPort.SnapshotPolicy.INTERVAL);
            assertTrue(f.tasks.valid(claimed.fence(), f.harness.now()), "事件尚未消费，任务存储仍显示旧租约");
            int before = f.state().version();
            var command = new com.wolfmind.application.game.SubmitActionCommand(ID, "u_human", "SKIP", Map.of(), before,
                    "task:"+claimed.taskId(), null, claimed.fence());
            assertThrows(GameException.class, () -> f.harness.submitService().submit(command));
            assertEquals(before, f.state().version());
        }
    }
    @Test void humanTurnGetsDeadlineButNoModelTask() {
        try (Fixture f = new Fixture(null, SpeechPacing.NONE, 1, 1)) {
            f.start(7); var action = f.planner.plan(ID).tasks().stream().filter(t -> t.kind()==TurnTask.Kind.ACTION).findFirst().orElseThrow();
            Game game = f.harness.repository().load(ID).game(); int version=game.version();
            game.changeController(SeatNo.of(action.seatNo()), PlayerController.human("human"),
                    new ActionContext("change", f.harness.now(), "SYSTEM", "change", "change", version, 0));
            var batch = new ArrayList<EventEnvelope>();
            for (var event : game.pendingEvents()) batch.add(EventEnvelope.of(event, ID, ++version));
            f.harness.repository().commit(ID, batch.getFirst().version()-1, batch,
                    com.wolfmind.application.port.GameRepositoryPort.SnapshotPolicy.INTERVAL);
            var plan=f.planner.plan(ID);
            assertTrue(plan.tasks().stream().anyMatch(t -> t.kind()==TurnTask.Kind.TIMEOUT));
            assertTrue(plan.tasks().stream().noneMatch(t -> t.kind()==TurnTask.Kind.ACTION));
        }
    }
    @Test void replayedEventAfterFailedAckCannotRedispatchTheSameWindow() throws Exception {
        var harness = new InfrastructureGameHarness(1000); harness.startNineAiGame(ID, RoleComposition.STANDARD_NINE, GameRules.DEFAULT, 7);
        var store = new InMemoryTurnTaskStore(); var reader = new InMemoryEventConsumption(harness.eventStore(), harness.eventStore());
        var wrapped = new EventConsumptionPort() {
            boolean fail=true;
            @Override public List<Delivery> read(int limit, long block) { return reader.read(limit,block); }
            @Override public void acknowledge(Delivery d) { if (fail) { fail=false; throw new IllegalStateException("ACK failed"); } reader.acknowledge(d); }
            @Override public void forget(String id) { reader.forget(id); }
            @Override public void close() { reader.close(); }
        };
        var adapter=org.mockito.Mockito.mock(AIControllerAdapter.class); AtomicInteger dispatches=new AtomicInteger();
        org.mockito.Mockito.when(adapter.dispatch(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any())).thenAnswer(invocation -> {
            dispatches.incrementAndGet(); return null;
        });
        var planner=new TurnPlanner(harness.repository(),harness.eventStore(),SpeechPacing.NONE);
        try (var scheduler=new EventDrivenTurnScheduler(wrapped,store,planner,adapter,
                new TimeoutApplicationService(harness.repository(),harness.lock(),harness.publisher(),harness.time(),planner),
                harness.time(), com.wolfmind.application.port.MetricsPort.NOOP,1,1)) {
            assertEquals(0, scheduler.consumeOnce(0));
            scheduler.pumpActions(); await(() -> dispatches.get()==1);
            await(() -> store.tasks(ID).stream().anyMatch(t -> t.status()==TurnTask.Status.WAITING_DEADLINE));
            scheduler.consumeOnce(0); scheduler.pumpActions();
            assertEquals(1,dispatches.get());
        }
    }
    @Test void independentTimeoutAdvancesWhileModelIsBlocked() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        LlmClient blocked = new LlmClient() {
            @Override public boolean available() { return true; }
            @Override public String providerId() { return "blocked"; }
            @Override public LlmResponse chat(LlmRequest req) {
                entered.countDown(); try { release.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                return new LlmResponse.Failure("FAKE", "blocked", false);
            }
        };
        try (Fixture f = new Fixture(blocked, SpeechPacing.NONE, 1, 1)) {
            f.start(7); var initial = f.state(); f.scheduler.start();
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            f.harness.advanceClock(60_001); f.scheduler.pumpTimeouts();
            await(() -> f.state().phase() != initial.phase());
            release.countDown();
            await(() -> f.tasks.tasks(ID).stream().noneMatch(t -> t.windowId()==11 && t.status()==TurnTask.Status.READY));
        } finally { release.countDown(); }
    }
    @Test void oneBlockedGameDoesNotStopAnotherGamesAgentWithinConcurrencyLimit() throws Exception {
        CountDownLatch blocked=new CountDownLatch(1), release=new CountDownLatch(1);
        CandidateModel delegate=new CandidateModel();
        LlmClient model=new LlmClient() {
            final AtomicInteger invocations=new AtomicInteger();
            @Override public boolean available() { return true; }
            @Override public String providerId() { return "isolated"; }
            @Override public InteractionMode interactionMode() { return InteractionMode.FUNCTION_CALLING; }
            @Override public LlmResponse chat(LlmRequest request) {
                if (invocations.getAndIncrement()==0) {
                    blocked.countDown(); try { release.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                    return new LlmResponse.Failure("FAKE","blocked",false);
                }
                return delegate.chat(request);
            }
        };
        try (Fixture f=new Fixture(model,SpeechPacing.NONE,2,1)) {
            f.start(7); f.scheduler.start(); assertTrue(blocked.await(3,TimeUnit.SECONDS));
            String other="g_second";
            f.harness.startNineAiGame(other,RoleComposition.STANDARD_NINE,GameRules.DEFAULT,42);
            await(() -> f.harness.eventStore().currentVersion(other)>11);
            assertEquals(11,f.state().version());
        } finally { release.countDown(); }
    }
    @Test void saturatedModelQueueKeepsTasksAndIndependentDeadlinesStillRun() throws Exception {
        CountDownLatch entered=new CountDownLatch(1), release=new CountDownLatch(1);
        LlmClient model=new LlmClient() {
            @Override public boolean available() { return true; }
            @Override public String providerId() { return "queue-test"; }
            @Override public LlmResponse chat(LlmRequest request) {
                entered.countDown(); try { release.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                return new LlmResponse.Failure("FAKE","blocked",false);
            }
        };
        try (Fixture f=new Fixture(model,SpeechPacing.NONE,1,1)) {
            f.start(7); f.scheduler.start(); assertTrue(entered.await(3,TimeUnit.SECONDS));
            for (String id:List.of("g_queue2","g_queue3","g_queue4"))
                f.harness.startNineAiGame(id,RoleComposition.STANDARD_NINE,GameRules.DEFAULT,42);
            await(() -> f.tasks.tasks("g_queue4").stream().anyMatch(t -> t.kind()==TurnTask.Kind.ACTION && t.status()==TurnTask.Status.READY));
            f.harness.advanceClock(60_001); f.scheduler.pumpTimeouts();
            await(() -> f.harness.repository().load("g_queue4").game().snapshot().phase()!=GamePhase.NIGHT_WOLF);
        } finally { release.countDown(); }
    }
}
