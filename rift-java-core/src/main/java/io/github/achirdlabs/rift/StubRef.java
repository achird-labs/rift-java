package io.github.achirdlabs.rift;

import io.github.achirdlabs.rift.dsl.StubSpec;
import io.github.achirdlabs.rift.json.JsonValue;
import io.github.achirdlabs.rift.model.Stub;

import java.util.Optional;

/** A thin, transport-backed reference to one stub on a live imposter — no local caching. */
public interface StubRef {

    int index();

    Optional<String> id();

    Stub definition();

    /**
     * Replaces this reference's stub. From rift 0.23.0 (rift#1515), replacing a stub addressed by
     * index (the reference {@code addStub} returns) restarts its response cycle at the first
     * response, as Mountebank does, even when the content is unchanged; replacing one addressed by
     * id ({@link Imposter#stub(String)}) keeps the cycle where it was.
     */
    void replace(StubSpec spec);

    /**
     * Raw-JSON form of {@link #replace(StubSpec)} — {@code stub} replaces this reference's target
     * as-is, preserving fields the DSL cannot express ({@code extra}, {@code _rift}); the engine
     * validates the content.
     */
    void replace(JsonValue stub);

    void delete();
}
