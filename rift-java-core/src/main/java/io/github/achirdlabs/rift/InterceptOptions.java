package io.github.achirdlabs.rift;

import io.github.achirdlabs.rift.json.JsonBool;
import io.github.achirdlabs.rift.json.JsonNumber;
import io.github.achirdlabs.rift.json.JsonObject;
import io.github.achirdlabs.rift.json.JsonString;
import io.github.achirdlabs.rift.json.JsonValue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.Key;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.util.Base64;
import java.util.Enumeration;
import java.util.Objects;

/**
 * Options for {@link Rift#intercept(InterceptOptions)}: the bind address for the intercept
 * listener, and an optional committed CA (both a cert and a key, or neither — a half-supplied pair
 * is rejected at {@link Builder#ca} rather than surfacing as a confusing engine-side error). With no
 * CA given, the engine mints a fresh ephemeral one per listener.
 *
 * <p>A CA may be supplied as file paths ({@link Builder#ca(Path, Path)}) or <em>in memory</em> — a
 * PEM {@link Builder#ca(String, String) String}, {@link Builder#ca(byte[], byte[]) byte[]}, or a
 * {@link Builder#ca(KeyStore, char[]) KeyStore} (e.g. from a secret store, without touching disk in
 * caller code). In-memory material is sent inline in the start request ({@code caCertPem}/{@code
 * caKeyPem}, rift &ge; 0.13.4), so it reaches an engine on another machine or in a container with
 * nothing staged there. A file-path CA is read by the engine from <em>its own</em> filesystem: right
 * for the embedded engine, but a containerized one needs the files mounted at those paths.
 */
public final class InterceptOptions {

    private final String host;
    private final int port;
    private final Path caCertPath;
    private final Path caKeyPath;
    private final String caCertPem;
    private final String caKeyPem;
    private final boolean returnCaKey;
    private final boolean attach;
    /** The CA the caller says an attached listener runs with; null when not given (or not attaching). */
    private final Intercept.CaMaterial attachCa;

    private InterceptOptions(String host, int port, Path caCertPath, Path caKeyPath,
                             String caCertPem, String caKeyPem, boolean returnCaKey, boolean attach,
                             Intercept.CaMaterial attachCa) {
        this.host = host;
        this.port = port;
        this.caCertPath = caCertPath;
        this.caKeyPath = caKeyPath;
        this.caCertPem = caCertPem;
        this.caKeyPem = caKeyPem;
        this.returnCaKey = returnCaKey;
        this.attach = attach;
        this.attachCa = attachCa;
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * Attach to an intercept listener something else started — the engine at launch ({@code
     * --intercept-port} / {@code RIFT_INTERCEPT_PORT}, e.g. a container's {@code withInterceptPort})
     * — at {@code host:port}, rather than starting one; {@code host}/{@code port} are the reachable
     * endpoint (e.g. a mapped Docker port). To start a listener instead, on any transport, pass
     * {@link #builder()} options to {@link Rift#intercept(InterceptOptions)} (rift &ge; 0.13.3 over the
     * admin API).
     */
    public static InterceptOptions attach(String host, int port) {
        Objects.requireNonNull(host, "host");
        return new InterceptOptions(host, port, null, null, null, null, false, true, null);
    }

    /**
     * {@link #attach(String, int)} to a listener the caller started with {@code ca} (a container's
     * committed CA, say), so {@link Intercept#caMaterial()} hands that pair back — to give a second
     * container, for instance. The engine never returns a key it was given, so this is the only way
     * the handle can carry one. The attach checks the listener's served certificate against {@code
     * ca}'s and refuses a mismatch, which would otherwise surface as a failed TLS handshake in the SUT.
     */
    public static InterceptOptions attach(String host, int port, Intercept.CaMaterial ca) {
        Objects.requireNonNull(host, "host");
        Objects.requireNonNull(ca, "ca");
        return new InterceptOptions(host, port, null, null, null, null, false, true, ca);
    }

    /** The CA given to {@link #attach(String, int, Intercept.CaMaterial)}, or null. */
    Intercept.CaMaterial attachCa() {
        return attachCa;
    }

    /** Whether these options attach to an already-running listener rather than starting one. */
    boolean isAttach() {
        return attach;
    }

    String host() {
        return host;
    }

    int port() {
        return port;
    }

    /** The intercept start JSON the engine's {@code rift_start_intercept} / {@code POST /intercept} expects. */
    public JsonValue toJson() {
        JsonObject.Builder json = JsonObject.builder()
                .put("host", new JsonString(host))
                .put("port", JsonNumber.of(port));
        if (caCertPem != null) {
            // Inline CA (rift >= 0.13.4, rift#593): ship the PEM in the body — no engine-side file.
            json.put("caCertPem", new JsonString(caCertPem)).put("caKeyPem", new JsonString(caKeyPem));
        } else if (caCertPath != null) {
            json.put("caCertPath", new JsonString(caCertPath.toString()))
                    .put("caKeyPath", new JsonString(caKeyPath.toString()));
        }
        if (returnCaKey) {
            json.put("returnCaKey", JsonBool.TRUE);
        }
        return json.build();
    }

    public static final class Builder {
        private String host = "127.0.0.1";
        private int port = 0;
        private Path caCertPath;
        private Path caKeyPath;
        private String caCertPem;
        private String caKeyPem;
        private boolean returnCaKey;

        private Builder() {
        }

        public Builder host(String host) {
            Objects.requireNonNull(host, "host");
            // The engine requires the intercept BIND host to be an IP literal (a hostname is rejected
            // engine-side with a confusing error); validate client-side with a clear message.
            if (!isIpLiteral(host)) {
                throw new IllegalArgumentException(
                        "intercept bind host must be an IP literal (e.g. 127.0.0.1 or 0.0.0.0), not '" + host + "'");
            }
            this.host = host;
            return this;
        }

        public Builder port(int port) {
            this.port = port;
            return this;
        }

        /**
         * Loads the intercept CA from {@code certPem}/{@code keyPem} files (both required, or omit
         * both to keep the default ephemeral CA) rather than minting a fresh one per listener —
         * letting independent engine instances share one trust anchor.
         */
        public Builder ca(Path certPem, Path keyPem) {
            requireBothOrNeither(certPem, keyPem);
            this.caCertPath = certPem;
            this.caKeyPath = keyPem;
            return this;
        }

        /**
         * Loads the intercept CA from in-memory PEM text (both required, or omit both). The PEM is
         * shipped inline in the start request (rift &ge; 0.13.4), so it works for a remote engine
         * without staging a file the engine must read.
         */
        public Builder ca(String certPem, String keyPem) {
            requireBothOrNeither(certPem, keyPem);
            this.caCertPem = certPem;
            this.caKeyPem = keyPem;
            return this;
        }

        /** Loads the intercept CA from in-memory PEM bytes (both required, or omit both). */
        public Builder ca(byte[] certPem, byte[] keyPem) {
            requireBothOrNeither(certPem, keyPem);
            return ca(certPem == null ? null : new String(certPem, StandardCharsets.UTF_8),
                    keyPem == null ? null : new String(keyPem, StandardCharsets.UTF_8));
        }

        /**
         * Loads the intercept CA from a {@code keyStore}'s first private-key entry (the cert and key
         * are PEM-encoded internally). {@code password} unlocks the key entry.
         */
        public Builder ca(KeyStore keyStore, char[] password) {
            Objects.requireNonNull(keyStore, "keyStore");
            String[] pem = extractPem(keyStore, password);
            return ca(pem[0], pem[1]);
        }

        /**
         * Have the engine generate a fresh CA and hand back its cert <em>and</em> key (rift &ge;
         * 0.13.4, rift#593), retrievable via {@link Intercept#caMaterial()} to persist and redistribute
         * — instead of pre-making one. Mutually exclusive with a supplied {@code ca(...)}.
         */
        public Builder generateCa() {
            this.returnCaKey = true;
            return this;
        }

        public InterceptOptions build() {
            if (returnCaKey && (caCertPem != null || caCertPath != null)) {
                throw new IllegalArgumentException(
                        "generateCa() cannot be combined with a supplied CA via ca(...)");
            }
            return new InterceptOptions(host, port, caCertPath, caKeyPath, caCertPem, caKeyPem, returnCaKey, false, null);
        }

        /** An IPv4 literal (dotted quad, each octet 0-255) or an IPv6 literal (contains a colon). */
        private static boolean isIpLiteral(String host) {
            if (host.indexOf(':') >= 0) {
                return true; // IPv6 literal (possibly bracketed)
            }
            String[] octets = host.split("\\.", -1);
            if (octets.length != 4) {
                return false;
            }
            for (String octet : octets) {
                if (octet.isEmpty() || octet.length() > 3) {
                    return false;
                }
                for (int i = 0; i < octet.length(); i++) {
                    if (!Character.isDigit(octet.charAt(i))) {
                        return false;
                    }
                }
                if (Integer.parseInt(octet) > 255) {
                    return false;
                }
            }
            return true;
        }

        private static void requireBothOrNeither(Object cert, Object key) {
            if ((cert == null) != (key == null)) {
                throw new IllegalArgumentException(
                        "InterceptOptions.ca(cert, key) requires both a cert and a key, or neither");
            }
        }

        private static String[] extractPem(KeyStore keyStore, char[] password) {
            try {
                Enumeration<String> aliases = keyStore.aliases();
                while (aliases.hasMoreElements()) {
                    String alias = aliases.nextElement();
                    if (keyStore.isKeyEntry(alias)) {
                        Key key = keyStore.getKey(alias, password);
                        Certificate cert = keyStore.getCertificate(alias);
                        if (key != null && cert != null) {
                            return new String[] {
                                    pem("CERTIFICATE", cert.getEncoded()), pem("PRIVATE KEY", key.getEncoded())};
                        }
                    }
                }
                throw new IllegalArgumentException("KeyStore has no private-key entry with a certificate");
            } catch (GeneralSecurityException e) {
                throw new IllegalArgumentException("failed to read the CA from the KeyStore: " + e.getMessage(), e);
            }
        }

        private static String pem(String type, byte[] der) {
            String body = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.UTF_8)).encodeToString(der);
            return "-----BEGIN " + type + "-----\n" + body + "\n-----END " + type + "-----\n";
        }
    }
}
