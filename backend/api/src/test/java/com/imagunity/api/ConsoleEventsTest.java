package com.imagunity.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

class ConsoleEventsTest {

    /** Flux qui garde les événements envoyés, ou échoue à la demande. */
    static final class RecordingEmitter extends SseEmitter {
        final List<Set<?>> sent = new ArrayList<>();
        boolean broken;

        @Override
        public void send(SseEventBuilder builder) throws IOException {
            if (broken) {
                throw new IOException("console partie");
            }
            sent.add(builder.build());
        }
    }

    private final List<RecordingEmitter> created = new ArrayList<>();
    private final ConsoleEvents events = new ConsoleEvents(Runnable::run) {
        @Override
        SseEmitter newEmitter() {
            RecordingEmitter emitter = new RecordingEmitter();
            created.add(emitter);
            return emitter;
        }
    };

    private static String text(Set<?> event) {
        StringBuilder text = new StringBuilder();
        event.forEach(part -> text.append(part instanceof org.springframework.http.server.DelegatingServerHttpResponse
                ? "" : String.valueOf(part instanceof SseEmitter.DataWithMediaType d ? d.getData() : part)));
        return text.toString();
    }

    @Test
    void subscriberReceivesReadyThenPublishedEvents() {
        events.subscribe();

        events.publish("transfer", "état");

        RecordingEmitter emitter = created.getFirst();
        assertThat(events.subscribers()).isEqualTo(1);
        assertThat(emitter.sent).hasSize(2);
        assertThat(text(emitter.sent.get(0))).contains("event:ready");
        assertThat(text(emitter.sent.get(1))).contains("event:transfer").contains("état");
    }

    @Test
    void brokenStreamsAreForgotten() {
        events.subscribe();
        events.subscribe();
        created.getFirst().broken = true;

        events.publish("inbound", "x");

        assertThat(events.subscribers()).isEqualTo(1);
        assertThat(created.get(1).sent).hasSize(2);
    }

    @Test
    void completedStreamIsForgottenOnNextSend() {
        ConsoleEvents real = new ConsoleEvents(Runnable::run);
        SseEmitter emitter = real.subscribe();
        emitter.complete();

        real.publish("transfer", "x");

        assertThat(real.subscribers()).isZero();
    }

    @Test
    void publishingWithoutSubscriberDoesNothing() {
        List<Runnable> tasks = new ArrayList<>();
        ConsoleEvents idle = new ConsoleEvents(tasks::add);

        idle.publish("transfer", "x");

        assertThat(tasks).isEmpty();
        assertThat(new ConsoleEvents().subscribe()).isNotNull();
    }

    @Test
    void stopClosesEveryStream() {
        events.start();
        events.subscribe();
        events.subscribe();
        assertThat(events.isRunning()).isTrue();

        events.stop();

        assertThat(events.isRunning()).isFalse();
        assertThat(events.subscribers()).isZero();
    }
}
