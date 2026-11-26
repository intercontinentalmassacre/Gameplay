package com.winlator.xenvironment.components;

import org.junit.Test;
import org.mockito.InOrder;

import java.util.concurrent.ExecutorService;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

public class PulseAudioComponentTest {
    @Test
    public void lifecycleCallbacksAfterStopDoNotSubmitToTheClosedExecutor() {
        ExecutorService executor = mock(ExecutorService.class);
        PulseAudioComponent audio = new PulseAudioComponent(null, false, executor);
        audio.start();
        audio.pause();
        audio.resume();
        audio.stop();

        // Android can pause or resume the activity while the guest is being torn down.
        audio.pause();
        audio.resume();
        audio.stop();
        audio.start();

        InOrder order = inOrder(executor);
        order.verify(executor, times(4)).execute(any(Runnable.class));
        order.verify(executor).shutdown();
        verifyNoMoreInteractions(executor);
    }

    @Test
    public void teardownBeforeStartupDoesNotQueueAudioWork() {
        ExecutorService executor = mock(ExecutorService.class);
        PulseAudioComponent audio = new PulseAudioComponent(null, false, executor);
        audio.pause();
        audio.resume();
        audio.stop();
        audio.stop();

        verify(executor).shutdown();
        verifyNoMoreInteractions(executor);
    }
}
