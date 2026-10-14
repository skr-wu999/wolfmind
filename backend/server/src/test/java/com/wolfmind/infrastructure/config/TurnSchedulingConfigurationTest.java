package com.wolfmind.infrastructure.config;

import com.wolfmind.application.scheduling.EventDrivenTurnScheduler;
import com.wolfmind.application.port.GameViewQueryPort;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TurnSchedulingConfigurationTest {
    @Test void eventModeStartsApplicationSchedulerWithoutGameScanning() throws Exception {
        var scheduler = mock(EventDrivenTurnScheduler.class);
        var views = mock(GameViewQueryPort.class);
        var executor = mock(ScheduledExecutorService.class);
        new SchedulingConfiguration().startGameScheduling(scheduler, views, new MockEnvironment(), executor).run(null);
        verify(scheduler).start(true);
        verifyNoInteractions(views, executor);
    }

    @Test void pollingFallbackUsesTheSameApplicationScheduler() throws Exception {
        var scheduler = mock(EventDrivenTurnScheduler.class);
        var views = mock(GameViewQueryPort.class);
        var executor = mock(ScheduledExecutorService.class);
        when(views.listGames("RUNNING", null, 1, 100)).thenReturn(List.of(java.util.Map.of("gameId", "g_test")));
        new SchedulingConfiguration().startGameScheduling(scheduler, views,
                new MockEnvironment().withProperty("wolfmind.game.driver.mode", "POLLING"), executor).run(null);
        verify(scheduler).start(false);
        var callback = org.mockito.ArgumentCaptor.forClass(Runnable.class);
        verify(executor).scheduleWithFixedDelay(callback.capture(), eq(200L), eq(200L), eq(TimeUnit.MILLISECONDS));
        callback.getValue().run();
        verify(scheduler).reconcileGame("g_test");
    }

    @Test void disablingSchedulingStartsNeitherDispatchPath() throws Exception {
        var scheduler = mock(EventDrivenTurnScheduler.class);
        var views = mock(GameViewQueryPort.class);
        var executor = mock(ScheduledExecutorService.class);
        new SchedulingConfiguration().startGameScheduling(scheduler, views,
                new MockEnvironment().withProperty("wolfmind.game.driver.enabled", "false"), executor).run(null);
        verifyNoInteractions(scheduler, views, executor);
    }

    @Test void removedInterfaceDriverAndVotePoolCannotBeRegisteredAgain() {
        Set<String> legacy = Set.of("gameDriver", "gameDriverVoteExecutor", "gameDriverScheduler");
        assertTrue(java.util.Arrays.stream(SchedulingConfiguration.class.getDeclaredMethods())
                .noneMatch(method -> legacy.contains(method.getName())));
        assertThrows(ClassNotFoundException.class, () -> Class.forName("com.wolfmind.interfaces.rest.GameDriver"));
        assertThrows(ClassNotFoundException.class, () -> Class.forName("com.wolfmind.interfaces.rest.SpeechPacing"));
    }

    @Test void readingDelayConfigurationUsesApplicationTypeAndExcludesLab() {
        var config = SchedulingConfiguration.speechPacing(new MockEnvironment());
        assertTrue(config.enabledFor("g_room"));
        assertFalse(config.enabledFor("g_lab_batch_0"));
        assertEquals(1500, config.pauseFor(""));
        assertFalse(SchedulingConfiguration.speechPacing(new MockEnvironment()
                .withProperty("wolfmind.game.speech-pacing.enabled", "false")).enabledFor("g_room"));
    }
}
