package com.newhorizon.thinclient.display;

/** Browser operations needed by displays, independent of MCEF and Forge APIs. */
public interface BrowserPort {
    void create(int browserId, String url, boolean transparent);

    void resize(int browserId, int width, int height);

    void navigate(int browserId, String url);

    void setVisible(int browserId, boolean visible);

    void setFocused(int browserId, boolean focused);

    void destroy(int browserId);
}


