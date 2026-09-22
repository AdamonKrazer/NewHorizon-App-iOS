package com.newhorizon.thinclient.protocol;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.IvParameterSpec;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;

/** Stateful AES/CFB8 pair used after the login encryption response. */
public final class MinecraftStreamCipher {
    private final Cipher decrypt;
    private final Cipher encrypt;

    public MinecraftStreamCipher(SecretKey secret) throws GeneralSecurityException {
        IvParameterSpec iv = new IvParameterSpec(secret.getEncoded());
        decrypt = Cipher.getInstance("AES/CFB8/NoPadding");
        encrypt = Cipher.getInstance("AES/CFB8/NoPadding");
        decrypt.init(Cipher.DECRYPT_MODE, secret, iv);
        encrypt.init(Cipher.ENCRYPT_MODE, secret, iv);
    }

    public void decrypt(ByteBuffer source, ByteBuffer destination)
            throws GeneralSecurityException {
        int expected = source.remaining();
        int written = decrypt.update(source, destination);
        if (written != expected) throw new GeneralSecurityException("Short decrypt output");
    }

    public void encrypt(ByteBuffer source, ByteBuffer destination)
            throws GeneralSecurityException {
        int expected = source.remaining();
        int written = encrypt.update(source, destination);
        if (written != expected) throw new GeneralSecurityException("Short encrypt output");
    }
}


