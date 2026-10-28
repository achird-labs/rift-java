package io.github.achirdlabs.rift;

import io.github.achirdlabs.rift.error.CommunicationError;
import io.github.achirdlabs.rift.json.JsonArray;
import io.github.achirdlabs.rift.json.JsonObject;
import io.github.achirdlabs.rift.json.JsonValue;

import java.util.ArrayList;
import java.util.List;

/**
 * Reads a recorded-request list out of an admin-API response body, for the imposter-wide journal and
 * the space-scoped view alike — both are the same list in the same two shapes, and reading them in
 * two places is how they came to drift.
 *
 * <p>Strict about the container, lenient about the elements. The two accepted shapes are a bare
 * array (what the engine serves) and a {@code {"requests":[...]}} envelope; anything else is a
 * {@link CommunicationError}, not an empty journal. The distinction matters because the caller
 * cannot make it: "recorded nothing" is a perfectly ordinary answer, so a 2xx whose body is not a
 * journal at all — an engine bug answering an error with status 200, a proxy substituting a page —
 * would otherwise read as fact, with nothing to correlate against. Element-level leniency stays: a
 * field the engine omits comes back empty rather than failing the whole read, because per-protocol
 * and per-version shape drift there is expected (see {@link RecordedRequest#read}).
 */
final class RecordedRequests {

    private RecordedRequests() {}

    /**
     * @param context the endpoint being read, named in the error so a mis-shaped body is traceable
     *                to the call that produced it
     */
    static List<RecordedRequest> readAll(JsonValue body, String context) {
        if (body instanceof JsonArray arr) {
            return read(arr);
        }
        if (body instanceof JsonObject obj && obj.get("requests") instanceof JsonArray arr) {
            return read(arr);
        }
        throw new CommunicationError("rift admin API returned an unrecognized " + context
                + " body: expected a JSON array of recorded requests, or a {\"requests\":[...]} envelope");
    }

    /**
     * The matching requests out of a {@code verify} reply asked for with {@code includeRequests}. A
     * reply without them is a {@link CommunicationError}: read as empty, it would say "nothing matched"
     * beside the engine's own non-zero {@code matched}.
     */
    static List<RecordedRequest> readMatched(JsonValue verifyReply) {
        if (verifyReply instanceof JsonObject obj && obj.get("requests") instanceof JsonArray arr) {
            return read(arr);
        }
        throw new CommunicationError("rift admin API returned a verify body without the requested"
                + " \"requests\" array: " + verifyReply.toJson());
    }

    private static List<RecordedRequest> read(JsonArray arr) {
        List<RecordedRequest> out = new ArrayList<>(arr.items().size());
        for (JsonValue v : arr.items()) {
            out.add(RecordedRequest.read(v));
        }
        return List.copyOf(out);
    }
}
