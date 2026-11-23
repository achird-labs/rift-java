package io.github.achirdlabs.rift.conformance;

import io.github.achirdlabs.rift.ComponentInfo;
import io.github.achirdlabs.rift.Imposter;
import io.github.achirdlabs.rift.ImposterClock;
import io.github.achirdlabs.rift.Rift;
import io.github.achirdlabs.rift.WasmInstanceInfo;
import io.github.achirdlabs.rift.dsl.ClockSource;
import io.github.achirdlabs.rift.dsl.ImposterSpec;
import io.github.achirdlabs.rift.dsl.Script;
import io.github.achirdlabs.rift.dsl.WasmInstanceScope;
import io.github.achirdlabs.rift.error.EngineError;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import static io.github.achirdlabs.rift.conformance.LiveEngine.engine;
import static io.github.achirdlabs.rift.conformance.LiveEngine.gated;
import static io.github.achirdlabs.rift.dsl.RiftDsl.imposter;
import static io.github.achirdlabs.rift.dsl.RiftDsl.onGet;
import static io.github.achirdlabs.rift.dsl.RiftDsl.script;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * rift 0.25.0's clock, seed and wasm surface against a live engine (rift-java#354), on every lane. The
 * clock and seed claims hold on any 0.25.0 engine; the component claims need one built with wasm,
 * which the spawned binary is and the plain {@code librift_ffi} is not, so they are skipped on an
 * engine that does not report the {@code wasm} feature. Needs {@code RIFT_IT=1}.
 *
 * <p>The two components under {@code src/test/resources/wasm/} are rift's own conformance fixtures
 * ({@code conformance/rift-fixtures/scripting/wasm.json} at v0.25.0, generated from hand-written core
 * modules by {@code crates/rift-mock-core/test-support/wasm_fixtures.rs}): {@code ok-twin} answers
 * {@code 200 ok} with {@code x-twin: yes}; {@code counter-twin} increments {@code count} in flow state.
 * Neither exports {@code rift:mock/introspect}.
 */
class WasmEngineIT {

    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private static final Instant FROZEN = Instant.ofEpochMilli(1_700_000_000_000L);
    private static final String NOW_JS = "function respond(ctx) { return http(200, String(Date.now())); }";
    private static final String RANDOM_JS = "function respond(ctx) { return http(200, String(Math.random())); }";

    @TestFactory
    Stream<DynamicTest> aScriptReadsTheImpostersClock() {
        return gated("Date.now() in a script follows a frozen, stepped and reset imposter clock", () -> {
            try (Rift rift = engine()) {
                Imposter imp = rift.create(imposter("clock").stub(onGet("/now").willReturn(script(Script.js(NOW_JS)))));
                ImposterClock clock = imp.clock();
                assertEquals(ImposterClock.Mode.WALL, clock.get().mode());

                assertEquals(new ImposterClock.Reading(ImposterClock.Mode.FROZEN, FROZEN), clock.set(FROZEN));
                assertEquals(Long.toString(FROZEN.toEpochMilli()), get(imp, "/now", Map.of()));

                ImposterClock.Reading stepped = clock.advance(Duration.ofDays(1));
                assertEquals(FROZEN.plus(Duration.ofDays(1)), stepped.now());
                assertEquals(Long.toString(stepped.nowMs()), get(imp, "/now", Map.of()));

                assertEquals(ImposterClock.Mode.WALL, clock.reset().mode());
                assertNotEquals(Long.toString(stepped.nowMs()), get(imp, "/now", Map.of()));
            }
        });
    }

    @TestFactory
    Stream<DynamicTest> aRequestNamesItsOwnInstantUnderTheHeaderClock() {
        return gated("clock(HEADER): X-Rift-Now is the request's instant", () -> {
            try (Rift rift = engine()) {
                Imposter imp = rift.create(imposter("header-clock").clock(ClockSource.HEADER)
                        .stub(onGet("/now").willReturn(script(Script.js(NOW_JS)))));
                assertEquals("1234567890123", get(imp, "/now", Map.of("X-Rift-Now", "1234567890123")));
            }
        });
    }

    @TestFactory
    Stream<DynamicTest> aSeedReplaysTheRandomStream() {
        return gated("seed(...): a recreated imposter draws the same Math.random() values", () -> {
            try (Rift rift = engine()) {
                // -2L is the seed 2^64-2: it only reaches the engine as a valid u64 if it is written unsigned.
                ImposterSpec spec = imposter("seeded").seed(-2L)
                        .stub(onGet("/r").willReturn(script(Script.js(RANDOM_JS))));
                Imposter first = rift.create(spec);
                List<String> run = draws(first, 3);
                assertEquals(3, run.stream().distinct().count(), "a stream, not a constant: " + run);
                first.delete();
                assertEquals(run, draws(rift.create(spec), 3));
            }
        });
    }

    @TestFactory
    Stream<DynamicTest> anUploadedComponentAnswers() {
        return gated("components: upload, run by hash, refuse delete while referenced, then delete", () -> {
            try (Rift rift = engine()) {
                assumeWasm(rift);
                byte[] bytes = resource("ok-twin.wasm");
                ComponentInfo info = rift.components().upload(bytes);
                assertEquals(sha256(bytes), info.sha256());
                assertEquals(bytes.length, info.size());
                assertEquals(info.sha256(), rift.components().upload(bytes).sha256(), "uploading again is not an error");
                assertTrue(rift.components().list().stream().anyMatch(c -> c.sha256().equals(info.sha256())));

                Imposter imp = rift.create(imposter("registry").stub(onGet("/ok")
                        .willReturn(script(Script.component(info.reference())))));
                HttpResponse<String> response = send(imp, "/ok", Map.of());
                assertEquals(200, response.statusCode());
                assertEquals("ok", response.body());
                assertEquals(Optional.of("yes"), response.headers().firstValue("x-twin"));
                assertEquals(List.of(imp.port()), rift.components().get(info.sha256()).orElseThrow().referencedBy());

                EngineError referenced = assertThrows(EngineError.class, () -> rift.components().delete(info.sha256()));
                assertEquals(409, referenced.code());

                imp.delete();
                rift.components().delete(info.sha256());
                assertEquals(Optional.empty(), rift.components().get(info.sha256()));
                rift.components().delete(info.sha256());
            }
        });
    }

    @TestFactory
    Stream<DynamicTest> componentBytesRunInline() {
        return gated("Script.wasm(bytes): the component travels as base64 code", () -> {
            try (Rift rift = engine()) {
                assumeWasm(rift);
                Imposter imp = rift.create(imposter("inline").stub(onGet("/ok")
                        .willReturn(script(Script.wasm(resource("ok-twin.wasm"))))));
                assertEquals("ok", get(imp, "/ok", Map.of()));
            }
        });
    }

    @TestFactory
    Stream<DynamicTest> aKeptInstanceIsListed() {
        return gated("instance(IMPOSTER): the kept instance is listed, and has no introspect export to snapshot", () -> {
            try (Rift rift = engine()) {
                assumeWasm(rift);
                byte[] bytes = resource("counter-twin.wasm");
                Imposter imp = rift.create(imposter("kept").stub(onGet("/incr")
                        .willReturn(script(Script.wasm(bytes).instance(WasmInstanceScope.IMPOSTER)))));
                assertEquals(List.of(), imp.wasmInstances(), "nothing is instantiated before a call");

                get(imp, "/incr", Map.of());
                get(imp, "/incr", Map.of());
                List<WasmInstanceInfo> instances = imp.wasmInstances();
                assertEquals(1, instances.size(), instances.toString());
                assertEquals(sha256(bytes), instances.get(0).sha256());
                assertEquals(2, instances.get(0).calls());

                EngineError noExport = assertThrows(EngineError.class, imp::wasmSnapshot);
                assertEquals(404, noExport.code(), noExport.getMessage());
            }
        });
    }

    /**
     * Skips on an engine without wasm, except on a lane that says its engine has it ({@code
     * RIFT_EXPECT_WASM}, set by CI for the spawned binary and the {@code -wasm} library): there a
     * missing feature is a broken lane, and a skip would hide it.
     */
    private static void assumeWasm(Rift rift) {
        boolean hasWasm = rift.info().features().contains("wasm");
        if (LiveEngine.integrationEnabled(System.getenv("RIFT_EXPECT_WASM"))) {
            assertTrue(hasWasm, "RIFT_EXPECT_WASM is set but the engine reports features " + rift.info().features());
        }
        assumeTrue(hasWasm, "this engine is built without wasm (the plain librift_ffi); the -wasm flavour or the binary has it");
    }

    private static List<String> draws(Imposter imp, int n) throws Exception {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            out.add(get(imp, "/r", Map.of()));
        }
        return out;
    }

    private static String get(Imposter imp, String path, Map<String, String> headers) throws Exception {
        HttpResponse<String> response = send(imp, path, headers);
        assertEquals(200, response.statusCode(), "status for " + path + ": " + response.body());
        return response.body();
    }

    private static HttpResponse<String> send(Imposter imp, String path, Map<String, String> headers) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(imp.uri() + path)).GET();
        headers.forEach(request::header);
        return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static byte[] resource(String name) {
        try (InputStream in = WasmEngineIT.class.getResourceAsStream("/wasm/" + name)) {
            if (in == null) {
                throw new IllegalStateException("missing test resource /wasm/" + name);
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
