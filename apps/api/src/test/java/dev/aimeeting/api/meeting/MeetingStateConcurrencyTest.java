package dev.aimeeting.api.meeting;

import static org.assertj.core.api.Assertions.*;

import dev.aimeeting.api.web.ApiException;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class MeetingStateConcurrencyTest {
    @Autowired MeetingRepository meetings;

    @Test
    void onlyOneMeetingCanStartAndRepeatedStartIsIdempotent() throws Exception {
        Meeting first = meetings.create("First concurrent meeting");
        Meeting second = meetings.create("Second concurrent meeting");
        CountDownLatch release = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<Object> firstResult = executor.submit(() -> startAfterRelease(first, release));
            Future<Object> secondResult = executor.submit(() -> startAfterRelease(second, release));
            release.countDown();
            List<Object> results = List.of(firstResult.get(), secondResult.get());
            assertThat(results.stream().filter(Meeting.class::isInstance)).hasSize(1);
            assertThat(results.stream().filter(ApiException.class::isInstance)).hasSize(1);
        }
        Meeting active = meetings.list().stream().filter(meeting -> "STARTED".equals(meeting.status())).findFirst().orElseThrow();
        int revision = active.revision();
        assertThat(meetings.start(active.id()).revision()).isEqualTo(revision);
        meetings.stop(active.id());
    }

    private Object startAfterRelease(Meeting meeting, CountDownLatch release) {
        try {
            release.await();
            return meetings.start(meeting.id());
        } catch (Exception exception) {
            return exception;
        }
    }
}
