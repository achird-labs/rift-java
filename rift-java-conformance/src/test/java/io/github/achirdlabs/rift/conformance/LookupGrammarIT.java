package io.github.achirdlabs.rift.conformance;

import io.github.achirdlabs.rift.Imposter;
import io.github.achirdlabs.rift.Rift;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.stream.Stream;

import static io.github.achirdlabs.rift.conformance.LiveEngine.engine;
import static io.github.achirdlabs.rift.conformance.LiveEngine.gated;
import static io.github.achirdlabs.rift.dsl.RiftDsl.imposter;
import static io.github.achirdlabs.rift.dsl.RiftDsl.lookupKey;
import static io.github.achirdlabs.rift.dsl.RiftDsl.lookupKeyFromHeader;
import static io.github.achirdlabs.rift.dsl.RiftDsl.ok;
import static io.github.achirdlabs.rift.dsl.RiftDsl.onGet;
import static io.github.achirdlabs.rift.dsl.RiftDsl.regex;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The lookup grammar #290 added — {@code key.index}, {@code csv.delimiter}, an object {@code key.from}
 * — is accepted and honoured by a live engine. Needs {@code RIFT_IT=1}.
 */
class LookupGrammarIT {

    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @TestFactory
    Stream<DynamicTest> indexDelimiterAndHeaderKeyResolveTheRow() {
        return gated("lookup keys on a regex capture group by index, reads a ';' CSV, and keys on a header", () -> {
            Path csv = Files.createTempFile("rift-lookup-", ".csv");
            Files.writeString(csv, "id;name\n7;seven\n42;forty-two\n");
            try (Rift rift = engine()) {
                Imposter imp = rift.create(imposter("lookup")
                        .stub(onGet("/items/7/42").willReturn(ok().withTextBody("${row}[name]")
                                .lookup(lookupKey("path").using(regex("/items/(\\d+)/(\\d+)")).index(2)
                                        .fromCsv(csv.toString(), "id", ';').into("${row}"))))
                        .stub(onGet("/tenant").willReturn(ok().withTextBody("${row}[name]")
                                .lookup(lookupKeyFromHeader("X-Tenant").using(regex(".+"))
                                        .fromCsv(csv.toString(), "id", ';').into("${row}")))));

                assertEquals("forty-two", get(imp, "/items/7/42", null), "index(2) is the second capture group (Mountebank's meaning)");
                assertEquals("seven", get(imp, "/tenant", "7"), "the key came from the X-Tenant header");
            } finally {
                Files.deleteIfExists(csv);
            }
        });
    }

    private static String get(Imposter imp, String path, String tenant) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(imp.uri().resolve(path)).timeout(Duration.ofSeconds(5)).GET();
        if (tenant != null) {
            request.header("X-Tenant", tenant);
        }
        return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString()).body();
    }
}
