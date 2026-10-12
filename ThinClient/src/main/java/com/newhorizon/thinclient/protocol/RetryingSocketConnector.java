package com.newhorizon.thinclient.protocol;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.InetSocketAddress;
import java.nio.channels.SocketChannel;

/** Retries TCP establishment only: no replay of a login or an existing world. */
public final class RetryingSocketConnector implements AutoCloseable {
    public static final int ATTEMPTS = 5, TIMEOUT_MILLIS = 10_000, DELAY_MILLIS = 2_000;
    private final int attempts, timeout, delay;
    private volatile boolean closed;
    private SocketChannel active;
    public interface Observer {
        void attempting(int attempt, int total);
        void failed(int attempt, IOException failure, boolean retrying);
    }
    public RetryingSocketConnector() { this(ATTEMPTS, TIMEOUT_MILLIS, DELAY_MILLIS); }
    RetryingSocketConnector(int attempts, int timeout, int delay) {
        if (attempts < 1 || timeout < 1 || delay < 0) throw new IllegalArgumentException();
        this.attempts=attempts; this.timeout=timeout; this.delay=delay;
    }
    public SocketChannel connect(String host, int port, Observer observer) throws IOException {
        for (int attempt=1; attempt<=attempts; attempt++) {
            checkCancelled();
            observer.attempting(attempt, attempts);
            SocketChannel socket=SocketChannel.open();
            boolean connected=false;
            try {
                synchronized(this) { checkCancelled(); active=socket; }
                socket.configureBlocking(true);
                socket.socket().setTcpNoDelay(true);
                socket.socket().setKeepAlive(true);
                socket.socket().connect(new InetSocketAddress(host,port),timeout);
                checkCancelled();
                connected=true;
                return socket;
            } catch(IOException failure) {
                checkCancelled();
                observer.failed(attempt,failure,attempt<attempts);
                if(attempt==attempts) throw failure;
            } finally {
                if(!connected) {
                    try { socket.close(); } catch(IOException ignored) { }
                    synchronized(this) { if(active==socket) active=null; }
                }
            }
            awaitRetry();
        }
        throw new AssertionError();
    }
    private void checkCancelled() throws InterruptedIOException {
        if(closed || Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Connection cancelled");
    }
    private synchronized void awaitRetry() throws InterruptedIOException {
        long end=System.nanoTime()+delay*1_000_000L;
        while(true) {
            checkCancelled();
            long remaining=end-System.nanoTime();
            if(remaining<=0) return;
            try { wait(Math.max(1,remaining/1_000_000L)); }
            catch(InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new InterruptedIOException("Connection cancelled");
            }
        }
    }
    @Override public synchronized void close() {
        closed=true;
        if(active!=null) { try { active.close(); } catch(IOException ignored) { } active=null; }
        notifyAll();
    }
}
