package com.newhorizon.thinclient.display;

import java.net.URI;
import java.net.URISyntaxException;

/** Navigation from private messages is restricted to ordinary web addresses. */
public final class TabletLinkUrl {
    private TabletLinkUrl() {}
    public static String validate(String value) {
        if (value == null || value.isEmpty() || value.length() > 2048)
            throw new IllegalArgumentException("Link inválido.");
        for (int i = 0; i < value.length(); i++)
            if (value.charAt(i) <= 32 || value.charAt(i) == 127 || value.charAt(i) == '\\')
                throw new IllegalArgumentException("Link inválido.");
        try {
            URI uri = new URI(value);
            if ((!"http".equalsIgnoreCase(uri.getScheme()) && !"https".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null || uri.getRawUserInfo() != null
                    || uri.getPort() > 65535)
                throw new IllegalArgumentException("Use um link http:// ou https://.");
            return uri.toASCIIString();
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("Link inválido.");
        }
    }
}
