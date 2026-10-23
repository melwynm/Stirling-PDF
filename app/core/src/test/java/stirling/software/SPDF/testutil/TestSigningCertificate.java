package stirling.software.SPDF.testutil;

import java.io.ByteArrayOutputStream;
import java.io.StringWriter;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;

import org.bouncycastle.asn1.pkcs.PrivateKeyInfo;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.openssl.PKCS8Generator;
import org.bouncycastle.openssl.jcajce.JcaPEMWriter;
import org.bouncycastle.openssl.jcajce.JceOpenSSLPKCS8EncryptorBuilder;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.OutputEncryptor;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

/**
 * Self-signed RSA signing certificate generated at test runtime, with its validity window anchored
 * to a caller-supplied instant instead of a fixed date, so tests never depend on wall-clock expiry
 * of a committed fixture. One generated key pair is exported in every container format the signing
 * controllers accept (PKCS#12/PFX, JKS, encrypted PKCS#8 PEM key, PEM/CRT/CER certificate, DER
 * certificate).
 *
 * <p>Use {@link #valid()} for the normal case. {@link #expired(Instant)} and {@link
 * #notYetValid(Instant)} produce certificates whose validity window is deliberately outside the
 * given reference time; production validity checks must reject them.
 */
public final class TestSigningCertificate {

    public static final String ALIAS = "alias";
    public static final String PASSWORD = "password";

    private static final Duration DEFAULT_VALIDITY = Duration.ofHours(1);
    private static final Duration CLOCK_SKEW = Duration.ofMinutes(1);
    private static final X500Name SUBJECT =
            new X500Name("CN=Stirling-PDF Test Signer, O=Stirling-PDF, C=US");

    private final KeyPair keyPair;
    private final X509Certificate certificate;

    private TestSigningCertificate(KeyPair keyPair, X509Certificate certificate) {
        this.keyPair = keyPair;
        this.certificate = certificate;
    }

    /** Certificate valid from one minute before now until one hour from now. */
    public static TestSigningCertificate valid() {
        return valid(Instant.now());
    }

    /** Certificate valid from one minute before {@code now} until one hour after it. */
    public static TestSigningCertificate valid(Instant now) {
        return generate(now.minus(CLOCK_SKEW), now.plus(DEFAULT_VALIDITY));
    }

    /** Certificate whose validity window ended one hour before {@code now}. */
    public static TestSigningCertificate expired(Instant now) {
        return generate(now.minus(DEFAULT_VALIDITY.multipliedBy(2)), now.minus(DEFAULT_VALIDITY));
    }

    /** Certificate whose validity window starts one hour after {@code now}. */
    public static TestSigningCertificate notYetValid(Instant now) {
        return generate(now.plus(DEFAULT_VALIDITY), now.plus(DEFAULT_VALIDITY.multipliedBy(2)));
    }

    private static TestSigningCertificate generate(Instant notBefore, Instant notAfter) {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            KeyPair keyPair = generator.generateKeyPair();

            BigInteger serial = new BigInteger(64, new SecureRandom()).add(BigInteger.ONE);
            JcaX509v3CertificateBuilder builder =
                    new JcaX509v3CertificateBuilder(
                            SUBJECT,
                            serial,
                            Date.from(notBefore),
                            Date.from(notAfter),
                            SUBJECT,
                            keyPair.getPublic());
            builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(false));
            builder.addExtension(
                    Extension.keyUsage,
                    true,
                    new KeyUsage(KeyUsage.digitalSignature | KeyUsage.nonRepudiation));
            ContentSigner contentSigner =
                    new JcaContentSignerBuilder("SHA256WithRSA").build(keyPair.getPrivate());
            X509Certificate certificate =
                    new JcaX509CertificateConverter().getCertificate(builder.build(contentSigner));
            return new TestSigningCertificate(keyPair, certificate);
        } catch (Exception e) {
            throw new IllegalStateException("Could not generate test signing certificate", e);
        }
    }

    public PrivateKey privateKey() {
        return keyPair.getPrivate();
    }

    public X509Certificate certificate() {
        return certificate;
    }

    /** PKCS#12 keystore (also served as {@code .pfx}) protected with {@link #PASSWORD}. */
    public byte[] pkcs12Bytes() {
        return keyStoreBytes("PKCS12");
    }

    /** JKS keystore protected with {@link #PASSWORD}. */
    public byte[] jksBytes() {
        return keyStoreBytes("JKS");
    }

    /** In-memory PKCS#12 keystore holding the key under {@link #ALIAS}. */
    public KeyStore pkcs12KeyStore() {
        return keyStore("PKCS12");
    }

    /** Private key as an encrypted PKCS#8 PEM block ({@code ENCRYPTED PRIVATE KEY}). */
    public byte[] encryptedPkcs8PemBytes() {
        try {
            OutputEncryptor encryptor =
                    new JceOpenSSLPKCS8EncryptorBuilder(PKCS8Generator.AES_256_CBC)
                            .setPassword(PASSWORD.toCharArray())
                            .build();
            PKCS8Generator generator =
                    new PKCS8Generator(
                            PrivateKeyInfo.getInstance(keyPair.getPrivate().getEncoded()),
                            encryptor);
            return pem(generator);
        } catch (Exception e) {
            throw new IllegalStateException("Could not encode encrypted PKCS#8 key", e);
        }
    }

    /** Certificate as a PEM block ({@code CERTIFICATE}); what {@code .crt}/{@code .cer} carry. */
    public byte[] certificatePemBytes() {
        return pem(certificate);
    }

    /** Certificate as raw DER. */
    public byte[] certificateDerBytes() {
        try {
            return certificate.getEncoded();
        } catch (Exception e) {
            throw new IllegalStateException("Could not DER-encode certificate", e);
        }
    }

    private KeyStore keyStore(String type) {
        try {
            KeyStore keyStore = KeyStore.getInstance(type);
            keyStore.load(null, null);
            keyStore.setKeyEntry(
                    ALIAS,
                    keyPair.getPrivate(),
                    PASSWORD.toCharArray(),
                    new Certificate[] {certificate});
            return keyStore;
        } catch (Exception e) {
            throw new IllegalStateException("Could not build " + type + " keystore", e);
        }
    }

    private byte[] keyStoreBytes(String type) {
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            keyStore(type).store(output, PASSWORD.toCharArray());
            return output.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("Could not serialize " + type + " keystore", e);
        }
    }

    private static byte[] pem(Object pemObject) {
        try (StringWriter writer = new StringWriter();
                JcaPEMWriter pemWriter = new JcaPEMWriter(writer)) {
            pemWriter.writeObject(pemObject);
            pemWriter.flush();
            return writer.toString().getBytes(StandardCharsets.US_ASCII);
        } catch (Exception e) {
            throw new IllegalStateException("Could not PEM-encode object", e);
        }
    }
}
