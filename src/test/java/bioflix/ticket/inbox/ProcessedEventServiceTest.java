package bioflix.ticket.inbox;

import bioflix.ticket.messaging.EventEnvelope;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
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
        // Arrange
        when(repository.existsById(event.eventId().toString())).thenReturn(false);
        ProcessedEventService service = new ProcessedEventService(repository);
        AtomicInteger runs = new AtomicInteger();

        // Act
        service.runOnce(event, runs::incrementAndGet);

        // Assert
        assertThat(runs).hasValue(1);
        verify(repository).insert(any(ProcessedEvent.class));
    }

    @Test
    void skipsAlreadyProcessedEvent() {
        // Arrange
        when(repository.existsById(event.eventId().toString())).thenReturn(true);
        ProcessedEventService service = new ProcessedEventService(repository);
        AtomicInteger runs = new AtomicInteger();

        // Act
        service.runOnce(event, runs::incrementAndGet);

        // Assert
        assertThat(runs).hasValue(0);
        verify(repository, never()).insert(any(ProcessedEvent.class));
    }

    @Test
    void failedHandlerIsNotRecorded() {
        // Arrange
        when(repository.existsById(event.eventId().toString())).thenReturn(false);
        ProcessedEventService service = new ProcessedEventService(repository);
        Runnable failingHandler = () -> {
            throw new IllegalStateException("boom");
        };

        // Act
        Throwable thrown = catchThrowable(() -> service.runOnce(event, failingHandler));

        // Assert
        assertThat(thrown).isInstanceOf(IllegalStateException.class);
        verify(repository, never()).insert(any(ProcessedEvent.class));
    }
}
