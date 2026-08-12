/*
 * Copyright 2015 The Apache Software Foundation.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.pdfbox.examples.signature;

import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.UnrecoverableKeyException;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.Hashtable;

import org.apache.pdfbox.pdmodel.interactive.digitalsignature.SignatureInterface;
import org.bouncycastle.asn1.ASN1ObjectIdentifier;
import org.bouncycastle.asn1.DERSet;
import org.bouncycastle.asn1.cms.Attribute;
import org.bouncycastle.asn1.cms.AttributeTable;
import org.bouncycastle.asn1.ess.ESSCertIDv2;
import org.bouncycastle.asn1.ess.SigningCertificateV2;
import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers;
import org.bouncycastle.cert.jcajce.JcaCertStore;
import org.bouncycastle.cms.CMSAttributeTableGenerator;
import org.bouncycastle.cms.CMSException;
import org.bouncycastle.cms.CMSSignedData;
import org.bouncycastle.cms.CMSSignedDataGenerator;
import org.bouncycastle.cms.DefaultSignedAttributeTableGenerator;
import org.bouncycastle.cms.jcajce.JcaSignerInfoGeneratorBuilder;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.OperatorCreationException;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.operator.jcajce.JcaDigestCalculatorProviderBuilder;

import lombok.Getter;
import lombok.Setter;

public abstract class CreateSignatureBase implements SignatureInterface {
    private PrivateKey privateKey;
    @Getter private Certificate[] certificateChain;
    @Setter private String tsaUrl;

    /**
     * Specifies whether the external signing scenario should be used. If set to {@code true},
     * external signing will be performed and {@link SignatureInterface} will be used for signing.
     * If set to {@code false}, internal signing will be performed.
     *
     * <p>Default: {@code false}
     *
     * @param externalSigning {@code true} if external signing should be performed; {@code false}
     *     for internal signing
     */
    @Setter @Getter private boolean externalSigning;

    protected CreateSignatureBase(Certificate[] certificateChain)
            throws CertificateException, IOException {
        if (certificateChain == null || certificateChain.length == 0) {
            throw new IOException("Could not find certificate");
        }
        Certificate cert = certificateChain[0];
        if (cert instanceof X509Certificate) {
            ((X509Certificate) cert).checkValidity();
        }
        this.certificateChain = certificateChain;
    }

    /**
     * Initialize the signature creator with a keystore (pkcs12) and pin that should be used for the
     * signature.
     *
     * @param keystore is a pkcs12 keystore.
     * @param pin is the pin for the keystore / private key
     * @throws KeyStoreException if the keystore has not been initialized (loaded)
     * @throws NoSuchAlgorithmException if the algorithm for recovering the key cannot be found
     * @throws UnrecoverableKeyException if the given password is wrong
     * @throws CertificateException if the certificate is not valid as signing time
     * @throws IOException if no certificate could be found
     */
    public CreateSignatureBase(KeyStore keystore, char[] pin)
            throws KeyStoreException,
                    UnrecoverableKeyException,
                    NoSuchAlgorithmException,
                    IOException,
                    CertificateException {
        // grabs the first alias from the keystore and get the private key. An
        // alternative method or constructor could be used for setting a specific
        // alias that should be used.
        Enumeration<String> aliases = keystore.aliases();
        String alias;
        Certificate cert = null;
        while (cert == null && aliases.hasMoreElements()) {
            alias = aliases.nextElement();
            privateKey = (PrivateKey) keystore.getKey(alias, pin);
            Certificate[] certChain = keystore.getCertificateChain(alias);
            if (certChain != null) {
                certificateChain = certChain;
                cert = certChain[0];
                if (cert instanceof X509Certificate) {
                    // avoid expired certificate
                    ((X509Certificate) cert).checkValidity();

                    //// SigUtils.checkCertificateUsage((X509Certificate) cert);
                }
            }
        }

        if (cert == null) {
            throw new IOException("Could not find certificate");
        }
    }

    public final void setPrivateKey(PrivateKey privateKey) {
        this.privateKey = privateKey;
    }

    public final void setCertificateChain(final Certificate[] certificateChain) {
        this.certificateChain = certificateChain;
    }

    /**
     * SignatureInterface sample implementation.
     *
     * <p>This method will be called from inside of the pdfbox and create the PKCS #7 signature. The
     * given InputStream contains the bytes that are given by the byte range.
     *
     * <p>This method is for internal use only.
     *
     * <p>Use your favorite cryptographic library to implement PKCS #7 signature creation. If you
     * want to create the hash and the signature separately (e.g. to transfer only the hash to an
     * external application), read <a href="https://stackoverflow.com/questions/41767351">this
     * answer</a> or <a href="https://stackoverflow.com/questions/56867465">this answer</a>.
     *
     * @throws IOException
     */
    @Override
    public byte[] sign(InputStream content) throws IOException {
        // cannot be done private (interface)
        try {
            CMSSignedDataGenerator gen = new CMSSignedDataGenerator();
            X509Certificate cert = (X509Certificate) certificateChain[0];
            ContentSigner signer =
                    new JcaContentSignerBuilder(resolveSignatureAlgorithm(privateKey))
                            .build(privateKey);
            JcaSignerInfoGeneratorBuilder signerInfoBuilder =
                    new JcaSignerInfoGeneratorBuilder(
                            new JcaDigestCalculatorProviderBuilder().build());
            signerInfoBuilder.setSignedAttributeGenerator(
                    createCadesSignedAttributeGenerator(cert));
            gen.addSignerInfoGenerator(signerInfoBuilder.build(signer, cert));
            gen.addCertificates(new JcaCertStore(Arrays.asList(certificateChain)));
            CMSProcessableInputStream msg = new CMSProcessableInputStream(content);
            CMSSignedData signedData = gen.generate(msg, false);
            signedData = addTimestampIfConfigured(signedData);
            return signedData.getEncoded();
        } catch (GeneralSecurityException
                | CMSException
                | OperatorCreationException
                | URISyntaxException e) {
            throw new IOException(e);
        }
    }

    protected final CMSSignedData addTimestampIfConfigured(CMSSignedData signedData)
            throws IOException, URISyntaxException {
        if (tsaUrl == null || tsaUrl.isBlank()) {
            return signedData;
        }
        try {
            return new ValidationTimeStamp(tsaUrl).addSignedTimeStamp(signedData);
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("Unable to initialise timestamp request", e);
        }
    }

    protected final CMSAttributeTableGenerator createCadesSignedAttributeGenerator(
            X509Certificate signingCertificate) throws GeneralSecurityException {
        byte[] certHash =
                MessageDigest.getInstance("SHA-256").digest(signingCertificate.getEncoded());
        ESSCertIDv2 essCertId = new ESSCertIDv2(certHash);
        SigningCertificateV2 signingCertificateV2 = new SigningCertificateV2(essCertId);
        Attribute signingCertificateAttribute =
                new Attribute(
                        PKCSObjectIdentifiers.id_aa_signingCertificateV2,
                        new DERSet(signingCertificateV2));
        Hashtable<ASN1ObjectIdentifier, Attribute> signedAttributes = new Hashtable<>();
        signedAttributes.put(
                PKCSObjectIdentifiers.id_aa_signingCertificateV2, signingCertificateAttribute);
        return new DefaultSignedAttributeTableGenerator(new AttributeTable(signedAttributes));
    }

    private String resolveSignatureAlgorithm(PrivateKey key) throws NoSuchAlgorithmException {
        return switch (key.getAlgorithm().toUpperCase()) {
            case "RSA" -> "SHA256WithRSA";
            case "EC", "ECDSA" -> "SHA256WithECDSA";
            default -> throw new NoSuchAlgorithmException(
                    "Unsupported private key algorithm: " + key.getAlgorithm());
        };
    }
}
