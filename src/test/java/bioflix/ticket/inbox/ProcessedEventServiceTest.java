package bioflix.ticket.inbox;

import bioflix.ticket.messaging.EventEnvelope;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProcessedEventServiceTest {

    @Mock ProcessedEventRepository repository;

    private final EventEnvelope<String> event = EventEnvelope.create("booking.confirmed", "booking-service", "b1", "x");

    @Test
    void runsAndRecordsNewEvent() {
        when(repository.existsById(event.eventId().toString())).thenReturn(false);
        AtomicInteger runs = new AtomicInteger();

        new ProcessedEventService(repository).runOnce(event, runs::incrementAndGet);

        assertThat(runs).hasValue(1);
        verify(repository).insert(any(ProcessedEvent.class));
    }

    @Test
    void skipsAlreadyProcessedEvent() {
        when(repository.existsById(event.eventId().toString())).thenReturn(true);
        AtomicInteger runs = new AtomicInteger();

        new ProcessedEventService(repository).runOnce(event, runs::incrementAndGet);

        assertThat(runs).hasValue(0);
    }

    @Test
    void failedHandlerIsNotRecorded() {
        when(repository.existsById(event.eventId().toString())).thenReturn(false);

        assertThatThrownBy(() -> new ProcessedEventService(repository).runOnce(event, () -> {
            throw new IllegalStateException("boom");
        })).isInstanceOf(IllegalStateException.class);
        verify(repository, never()).insert(any(ProcessedEvent.class));
    }
}
