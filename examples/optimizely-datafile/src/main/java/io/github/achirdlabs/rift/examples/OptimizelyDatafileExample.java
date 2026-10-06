package io.github.achirdlabs.rift.examples;

import io.github.achirdlabs.rift.EmbeddedOptions;
import io.github.achirdlabs.rift.Imposter;
import io.github.achirdlabs.rift.Intercept;
import io.github.achirdlabs.rift.Rift;

import javax.net.ssl.SSLContext;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static io.github.achirdlabs.rift.dsl.RiftDsl.imposter;
import static io.github.achirdlabs.rift.dsl.RiftDsl.okJson;
import static io.github.achirdlabs.rift.dsl.RiftDsl.onGet;

/**
 * Parity sample: an {@link Intercept} {@code redirectTo} rule diverts real HTTPS traffic bound for
 * {@code cdn.optimizely.com} to a local imposter serving a feature-flag datafile, then swaps the
 * datafile (revision 42 → 43) with {@code replaceStubs} — how a test changes a flag mid-run — without
 * re-issuing the rule. No real network call ever leaves the JVM. Requires JDK 22+ (the embedded engine). This
 * sample points the engine at a cdylib explicitly via {@code -Drift.ffi.lib=/path/to/librift_ffi.<ext>}
 * for a self-contained run; a real consumer instead puts a {@code rift-java-natives} classifier jar on
 * the classpath and just calls {@code Rift.embedded()} — see this module's README.
 */
public final class OptimizelyDatafileExample {

    private static final String DATAFILE_V1 =
            "{\"revision\":\"42\",\"accountId\":\"acct-1\",\"featureFlags\":[]}";
    private static final String DATAFILE_V2 =
            "{\"revision\":\"43\",\"accountId\":\"acct-1\",\"featureFlags\":[]}";
    private static final String DATAFILE_URL = "https://cdn.optimizely.com/datafiles/acct-1.json";

    public static void main(String[] args) throws Exception {
        EmbeddedOptions.Builder options = EmbeddedOptions.builder();
        String lib = System.getProperty("rift.ffi.lib");
        if (lib != null && !lib.isBlank()) {
            options.libraryPath(Path.of(lib));
        }

        try (Rift rift = Rift.embedded(options.build())) {
            Imposter cdn = rift.create(imposter("optimizely-cdn")
                    .stub(onGet("/datafiles/acct-1.json").willReturn(okJson(DATAFILE_V1))));

            try (Intercept intercept = rift.intercept()) {
                intercept.redirectTo("cdn.optimizely.com", cdn);

                SSLContext trust = intercept.trust().sslContext();
                HttpClient client = HttpClient.newBuilder()
                        .sslContext(trust)
                        .proxy(intercept.proxySelector())
                        .connectTimeout(Duration.ofSeconds(10))
                        .build();
                System.out.println("Intercepted datafile: " + get(client, DATAFILE_URL));

                cdn.replaceStubs(List.of(onGet("/datafiles/acct-1.json").willReturn(okJson(DATAFILE_V2))));
                System.out.println("After the swap:       " + get(client, DATAFILE_URL));
            }
        }
    }

    private static String get(HttpClient client, String url) throws Exception {
        HttpResponse<String> response = client.send(
                HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        return response.body();
    }
}
