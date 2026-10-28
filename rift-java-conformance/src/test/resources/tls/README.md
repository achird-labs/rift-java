# Test TLS material

Throwaway certificates for the live-engine ITs (`UpstreamTrustIT`, `ImposterClientAuthIT`, `ProxyClientCertificateIT`). They
secure nothing: every private key is checked in, and every certificate is valid for 100 years so a
test never fails on a calendar date.

| File | What it is |
|---|---|
| `ca.pem` | The test CA (`CN=rift-java test CA`). Its key was discarded after signing. |
| `leaf.pem`, `leaf-key.pem` | Server certificate for `127.0.0.1`/`localhost`, issued by `ca.pem` |
| `client.p12` | Client certificate issued by `ca.pem`, with its key (PKCS#12, password `changeit`) |
| `client.pem`, `client-key.pem` | The same client certificate and its key as PEM (unencrypted PKCS#8), for a `proxy`'s `cert`/`key` |
| `client-key-encrypted.pem` | The same key as encrypted PKCS#8, passphrase `changeit`, for a `proxy`'s `passphrase` |
| `untrusted-client.p12` | Client certificate from an unrelated CA, with its key (PKCS#12, password `changeit`) |

Regenerate with OpenSSL (P-256 keys):

```sh
openssl ecparam -name prime256v1 -genkey -noout -out ca.key
openssl req -x509 -new -key ca.key -sha256 -days 36500 -subj "/CN=rift-java test CA" \
  -addext "basicConstraints=critical,CA:TRUE" -addext "keyUsage=critical,keyCertSign,cRLSign" -out ca.pem

# server leaf
openssl ecparam -name prime256v1 -genkey -noout | openssl pkcs8 -topk8 -nocrypt -out leaf-key.pem
openssl req -new -key leaf-key.pem -subj "/CN=127.0.0.1" -out leaf.csr
printf "basicConstraints=critical,CA:FALSE\nkeyUsage=critical,digitalSignature\nextendedKeyUsage=serverAuth\nsubjectAltName=IP:127.0.0.1,DNS:localhost\n" > leaf.ext
openssl x509 -req -in leaf.csr -CA ca.pem -CAkey ca.key -CAcreateserial -days 36500 -sha256 -extfile leaf.ext -out leaf.pem

# client (repeat with a second, separately generated CA for untrusted-client.p12)
openssl ecparam -name prime256v1 -genkey -noout | openssl pkcs8 -topk8 -nocrypt -out client-key.pem
openssl req -new -key client-key.pem -subj "/CN=rift-java test client" -out client.csr
printf "basicConstraints=critical,CA:FALSE\nkeyUsage=critical,digitalSignature\nextendedKeyUsage=clientAuth\n" > client.ext
openssl x509 -req -in client.csr -CA ca.pem -CAkey ca.key -CAcreateserial -days 36500 -sha256 -extfile client.ext -out client.pem
openssl pkcs12 -export -in client.pem -inkey client-key.pem -name client -passout pass:changeit -out client.p12

# PEM forms of client.p12, for a proxy's cert/key/passphrase
openssl pkcs12 -in client.p12 -passin pass:changeit -clcerts -nokeys | openssl x509 -out client.pem
openssl pkcs12 -in client.p12 -passin pass:changeit -nocerts -nodes | openssl pkcs8 -topk8 -nocrypt -out client-key.pem
openssl pkcs8 -topk8 -in client-key.pem -v2 aes-256-cbc -v2prf hmacWithSHA256 -iter 2048 -passout pass:changeit \
  -out client-key-encrypted.pem
```
