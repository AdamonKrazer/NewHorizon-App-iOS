package com.newhorizon.thinclient.protocol;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;

/** Performs Mojang session join and creates the encrypted login response. */
public final class MinecraftSessionAuthenticator {
    private static final String JOIN_URL =
            "https://sessionserver.mojang.com/session/minecraft/join";

    public LoginEncryption authenticate(MinecraftPackets.EncryptionRequest request,
                                        SessionCredentials credentials)
            throws IOException, GeneralSecurityException, ProtocolException {
        if (!credentials.canJoinOnlineServer()) {
            throw new ProtocolException("Server requires online login credentials");
        }

        PublicKey publicKey = KeyFactory.getInstance("RSA").generatePublic(
                new X509EncodedKeySpec(request.publicKey));
        KeyGenerator generator = KeyGenerator.getInstance("AES");
        generator.init(128);
        SecretKey secret = generator.generateKey();

        MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
        sha1.update(request.serverId.getBytes(StandardCharsets.ISO_8859_1));
        sha1.update(secret.getEncoded());
        sha1.update(publicKey.getEncoded());
        String serverHash = new BigInteger(sha1.digest()).toString(16);
        joinSession(credentials, serverHash);

        Cipher rsa = Cipher.getInstance("RSA/ECB/PKCS1Padding");
        rsa.init(Cipher.ENCRYPT_MODE, publicKey);
        byte[] encryptedSecret = rsa.doFinal(secret.getEncoded());
        byte[] encryptedChallenge = rsa.doFinal(request.challenge);
        return new LoginEncryption(secret, encryptedSecret, encryptedChallenge);
    }

    private static void joinSession(SessionCredentials credentials, String serverHash)
            throws IOException, ProtocolException {
        HttpURLConnection connection = (HttpURLConnection) new URL(JOIN_URL).openConnection();
        connection.setConnectTimeout(10_000);
        connection.setReadTimeout(10_000);
        connection.setRequestMethod("POST");
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        connection.setDoOutput(true);
        byte[] body = ("{\"accessToken\":\"" + json(credentials.accessToken)
                + "\",\"selectedProfile\":\""
                + credentials.profileId.toString().replace("-", "")
                + "\",\"serverId\":\"" + json(serverHash) + "\"}")
                .getBytes(StandardCharsets.UTF_8);
        connection.setFixedLengthStreamingMode(body.length);
        try (OutputStream output = connection.getOutputStream()) {
            output.write(body);
        }
        int status = connection.getResponseCode();
        connection.disconnect();
        if (status != HttpURLConnection.HTTP_NO_CONTENT) {
            throw new ProtocolException("Session server rejected login (HTTP " + status + ")");
        }
    }

    private static String json(String value) {
        StringBuilder result = new StringBuilder(value.length() + 8);
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character == '\\' || character == '"') result.append('\\');
            result.append(character);
        }
        return result.toString();
    }

    public static final class LoginEncryption {
        public final SecretKey secret;
        public final byte[] encryptedSecret;
        public final byte[] encryptedChallenge;

        LoginEncryption(SecretKey secret, byte[] encryptedSecret,
                        byte[] encryptedChallenge) {
            this.secret = secret;
            this.encryptedSecret = encryptedSecret;
            this.encryptedChallenge = encryptedChallenge;
        }
    }
}


