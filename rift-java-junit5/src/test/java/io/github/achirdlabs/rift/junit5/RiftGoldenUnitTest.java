package io.github.achirdlabs.rift.junit5;

import io.github.achirdlabs.rift.Imposter;
import io.github.achirdlabs.rift.dsl.ImposterSpec;
import org.junit.jupiter.api.Test;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.testkit.engine.Events;

import java.nio.file.Files;
import java.nio.file.Path;

import static io.github.achirdlabs.rift.dsl.RiftDsl.imposter;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;
import static org.junit.platform.testkit.engine.EngineTestKit.engine;

/**
 * @RiftGolden misconfiguration paths (#25 Part 2), driven over a fake admin via EngineTestKit — no
 * Docker. The happy-path capture→replay round-trip is covered by the real-engine RiftGoldenIT.
 */
class RiftGoldenUnitTest {

    static final FakeRiftAdmin ADMIN = new FakeRiftAdmin();

    static {
        System.setProperty("rift.golden.unit.admin", ADMIN.baseUri().toString());
    }

    @Test
    void stublessGoldenFileFailsLoudly() throws Exception {
        Path file = Path.of("target/rift-golden-unit/empty.json");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "{\"stubs\":[]}");   // present but records nothing → REPLAY must not serve blindly
        assertFailureContains(EmptyGoldenFixture.class, "no stubs");
    }

    @Test
    void unknownGoldenImposterNameFailsClearly() {
        assertFailureContains(UnknownImposterFixture.class, "nope");
    }

    /**
     * Since rift 0.19.0 {@code DELETE savedProxyResponses} also deletes the stubs a proxy recorded
     * ({@code recordedFrom}) — on the golden imposter, the fixture itself. The PER_TEST reset must
     * therefore leave the golden imposter's proxy responses alone, while still clearing the others'.
     */
    @Test
    void perTestResetKeepsTheGoldenImpostersRecordedStubs() throws Exception {
        Path file = Path.of("target/rift-golden-unit/one-stub.json");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "{\"stubs\":[{\"predicates\":[{\"equals\":{\"path\":\"/u/1\"}}],"
                + "\"responses\":[{\"is\":{\"statusCode\":200}}],\"recordedFrom\":\"http://origin\"}]}");
        engine("junit-jupiter").selectors(selectClass(ReplayResetFixture.class)).execute()
                .testEvents().assertStatistics(stats -> stats.started(2).succeeded(2).failed(0));
        assertEquals(0, ADMIN.state(ReplayResetFixture.goldenPort).proxyResponseClears.get(),
                "the golden imposter's recorded stubs survive the PER_TEST reset");
        assertEquals(2, ADMIN.state(ReplayResetFixture.otherPort).proxyResponseClears.get(),
                "a non-golden imposter's proxy responses are still cleared before each test");
    }

    private static void assertFailureContains(Class<?> fixture, String expected) {
        Events containers = engine("junit-jupiter").selectors(selectClass(fixture)).execute().containerEvents();
        String message = containers.failed().stream()
                .map(event -> event.getPayload(TestExecutionResult.class).orElseThrow())
                .map(result -> result.getThrowable().map(Throwable::getMessage).orElse(""))
                .reduce("", (a, b) -> a + b);
        assertTrue(message.contains(expected), "expected a golden failure mentioning '" + expected + "', got: " + message);
    }

    @RiftTest(transport = Transport.CONNECT, adminUri = "${rift.golden.unit.admin}")
    @RiftGolden(origin = "http://unused", file = "target/rift-golden-unit/empty.json")
    static class EmptyGoldenFixture {
        @RiftImposter
        static ImposterSpec users = imposter("users").record();

        @Test
        void unreached() {
        }
    }

    @RiftTest(transport = Transport.CONNECT, adminUri = "${rift.golden.unit.admin}")
    @RiftGolden(origin = "http://unused", file = "target/rift-golden-unit/missing.json", imposter = "nope")
    static class UnknownImposterFixture {
        @RiftImposter
        static ImposterSpec users = imposter("users").record();

        @Test
        void unreached() {
        }
    }

    @RiftTest(transport = Transport.CONNECT, adminUri = "${rift.golden.unit.admin}")
    @RiftGolden(origin = "http://unused", file = "target/rift-golden-unit/one-stub.json", imposter = "users")
    static class ReplayResetFixture {
        static volatile int goldenPort;
        static volatile int otherPort;

        @RiftImposter
        static ImposterSpec users = imposter("users").record();

        @RiftImposter
        static ImposterSpec other = imposter("other").record();

        @InjectImposter("users")
        Imposter usersImposter;

        @InjectImposter("other")
        Imposter otherImposter;

        @Test
        void first() {
            goldenPort = usersImposter.port();
            otherPort = otherImposter.port();
        }

        @Test
        void second() {
        }
    }
}
