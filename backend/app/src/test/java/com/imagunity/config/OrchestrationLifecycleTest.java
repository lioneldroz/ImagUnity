package com.imagunity.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.imagunity.orchestration.PairingService;
import com.imagunity.orchestration.ReceiveOrchestrator;
import com.imagunity.orchestration.SendOrchestrator;
import com.imagunity.packaging.PackageReader;
import com.imagunity.packaging.PackagingConfig;
import com.imagunity.packaging.StudyPackager;
import com.imagunity.transport.Invitation;
import com.imagunity.transport.PeerId;
import com.imagunity.transport.TransportEvent;
import com.imagunity.transport.TransportException;
import com.imagunity.transport.TransportPort;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.springframework.context.SmartLifecycle;

class OrchestrationLifecycleTest {

    /** Transport dont le rattrapage échoue ou réussit, selon le test. */
    private static final class Transport implements TransportPort {
        final List<Consumer<TransportEvent>> listeners = new ArrayList<>();
        boolean failCatchUp;
        int catchUps;

        @Override
        public Invitation createInvitation() {
            return null;
        }

        @Override
        public void acceptInvitation(String link) {
        }

        @Override
        public void sendMessage(PeerId peer, String text) {
        }

        @Override
        public long sendFile(PeerId peer, Path file) {
            return 0;
        }

        @Override
        public void receiveFile(long fileId, Path target) {
        }

        @Override
        public void addListener(Consumer<TransportEvent> listener) {
            listeners.add(listener);
        }

        @Override
        public void catchUp() {
            catchUps++;
            if (failCatchUp) {
                throw new TransportException("client simplex-chat injoignable");
            }
        }

        @Override
        public void close() {
        }
    }

    private final Transport transport = new Transport();
    private final Executor idle = task -> { };

    private SmartLifecycle lifecycle() {
        SendOrchestrator sender = new SendOrchestrator(null, null, new StudyPackager(PackagingConfig.defaults()),
                transport, Path.of("outbox"), idle, Clock.systemUTC());
        ReceiveOrchestrator receiver = new ReceiveOrchestrator(null, null, transport, new PackageReader(1), null,
                Path.of("inbox"), 1, idle, Clock.systemUTC());
        PairingService pairing = new PairingService(null, transport, Clock.systemUTC(), Duration.ofHours(1));
        return new OrchestrationConfiguration().orchestrationLifecycle(sender, receiver, pairing, transport);
    }

    @Test
    void startCatchesUpAfterListenersAreRegistered() {
        SmartLifecycle lifecycle = lifecycle();

        lifecycle.start();

        assertThat(transport.listeners).hasSize(3);
        assertThat(transport.catchUps).isEqualTo(1);
        assertThat(lifecycle.isRunning()).isTrue();
    }

    @Test
    void failedCatchUpDoesNotPreventStartup() {
        transport.failCatchUp = true;
        SmartLifecycle lifecycle = lifecycle();

        lifecycle.start();

        assertThat(lifecycle.isRunning()).isTrue();
    }
}
