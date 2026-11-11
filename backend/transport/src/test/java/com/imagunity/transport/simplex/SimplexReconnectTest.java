package com.imagunity.transport.simplex;

import static org.assertj.core.api.Assertions.assertThat;

import com.imagunity.transport.TransportEvent;
import com.imagunity.transport.TransportException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class SimplexReconnectTest {

    private static final Function<String, String> RESPONDER = cmd -> switch (cmd) {
        case "/user" -> "{\"type\":\"activeUser\",\"user\":{\"userId\":3}}";
        case "/_contacts 3" -> "{\"type\":\"contactsList\",\"contacts\":[]}";
        default -> "{\"type\":\"chatCmdError\",\"chatError\":{\"type\":\"error\"}}";
    };

    private final List<Duration> pauses = new CopyOnWriteArrayList<>();
    private final AtomicInteger failuresLeft = new AtomicInteger();
    private final List<FakeChatConnection> connections = new CopyOnWriteArrayList<>();
    private SimplexChatClient client;
    private Function<String, String> responder = RESPONDER;

    private SimplexChatClient client(Duration firstPause, SimplexChatClient.Sleeper sleeper) {
        client = new SimplexChatClient(JsonMapper.builder().build(), Duration.ofMillis(300), () -> {
            if (failuresLeft.getAndDecrement() > 0) {
                throw new TransportException("simplex-chat injoignable");
            }
            FakeChatConnection connection = new FakeChatConnection(client, responder);
            connections.add(connection);
            return connection;
        }, firstPause, sleeper);
        return client;
    }

    private SimplexChatClient.Sleeper recording() {
        return pauses::add;
    }

    @Test
    void reconnectsWithGrowingPausesThenNotifies() {
        client(Duration.ofSeconds(1), recording());
        List<String> notified = new ArrayList<>();
        client.addReconnectListener(() -> notified.add("reconnecté"));
        failuresLeft.set(2);

        client.reconnect();

        assertThat(pauses).containsExactly(Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofSeconds(4));
        assertThat(notified).containsExactly("reconnecté");
        assertThat(client.command("/user").path("type").asString()).isEqualTo("activeUser");
    }

    @Test
    void pauseIsCappedAt30Seconds() {
        client(Duration.ofSeconds(20), recording());
        failuresLeft.set(2);

        client.reconnect();

        assertThat(pauses).containsExactly(Duration.ofSeconds(20), Duration.ofSeconds(30), Duration.ofSeconds(30));
    }

    @Test
    void lostConnectionStartsReconnectionInBackground() throws InterruptedException {
        // La première tentative attend la seconde coupure : la reconnexion est bien en cours à ce moment-là.
        CountDownLatch secondDrop = new CountDownLatch(1);
        client(Duration.ZERO, d -> secondDrop.await(5, TimeUnit.SECONDS));
        CountDownLatch reconnected = new CountDownLatch(1);
        client.addReconnectListener(() -> {
            throw new IllegalStateException("écouteur défaillant");
        });
        client.addReconnectListener(reconnected::countDown);

        client.onClosed(new java.io.IOException("connexion réinitialisée"));
        client.onClosed(null); // déjà en cours : pas de seconde reconnexion
        secondDrop.countDown();

        assertThat(reconnected.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(connections).hasSize(1);
    }

    @Test
    void noReconnectionOnceClosed() {
        client(Duration.ZERO, recording());

        client.close();
        client.reconnect();

        assertThat(connections).isEmpty();
        assertThat(pauses).isEmpty();
    }

    @Test
    void closedDuringAttemptDropsTheNewConnection() {
        client(Duration.ZERO, d -> client.close());

        client.reconnect();

        assertThat(connections).singleElement().satisfies(c -> assertThat(c.closed).isTrue());
    }

    @Test
    void interruptedWaitStopsReconnection() {
        client(Duration.ZERO, d -> {
            throw new InterruptedException();
        });

        client.reconnect();

        assertThat(Thread.interrupted()).isTrue();
        assertThat(connections).isEmpty();
    }

    @Test
    void transportCatchesUpAfterReconnection() {
        client(Duration.ZERO, recording());
        new FakeChatConnection(client, responder);
        List<TransportEvent> events = new ArrayList<>();
        SimplexTransport transport = new SimplexTransport(client);
        transport.addListener(events::add);

        client.reconnect();

        assertThat(connections).singleElement().satisfies(c -> assertThat(c.commands).containsExactly("/_contacts 3"));
    }

    @Test
    void failedCatchUpAfterReconnectionIsOnlyLogged() {
        client(Duration.ZERO, recording());
        new FakeChatConnection(client, responder);
        new SimplexTransport(client);
        responder = cmd -> "{\"type\":\"chatCmdError\",\"chatError\":{\"type\":\"error\"}}";

        client.reconnect();

        assertThat(connections).singleElement().satisfies(c -> assertThat(c.commands).containsExactly("/_contacts 3"));
    }
}
