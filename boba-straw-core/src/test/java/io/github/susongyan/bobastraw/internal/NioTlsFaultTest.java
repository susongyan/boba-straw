package io.github.susongyan.bobastraw.internal;

import io.github.susongyan.bobastraw.BobaStrawConnectionException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketOption;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.channels.spi.SelectorProvider;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLEngineResult;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLSession;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Deterministic channel/engine fault injection; actual cryptography is covered by JSSE tests. */
@org.junit.jupiter.api.Tag("fault-injection")
class NioTlsFaultTest {
    @Test
    void zeroAndPartialWritesPreserveCiphertextAndDoNotConsumeTheNextFrame() throws Exception {
        onLoop((group, loop) -> {
            Wire wire = new Wire();
            Engine engine = new Engine(Mode.NORMAL);
            NioTlsTransport transport = transport(group, loop, engine, wire);
            ByteBuffer first = ByteBuffer.wrap(new byte[] {1, 2, 3});
            ByteBuffer next = ByteBuffer.wrap(new byte[] {4, 5});
            assertEquals(3L, transport.write(new ByteBuffer[] {first}, 1));
            assertEquals(0, wire.sent.size());
            assertTrue(transport.wantsWrite());
            assertEquals(0L, transport.write(new ByteBuffer[] {next}, 1));
            assertEquals(0, next.position());
            wire.writeLimit = 1;
            for (int index = 0; index < 20 && (next.hasRemaining() || transport.wantsWrite()); index++) {
                transport.resetBudget();
                if (next.hasRemaining()) {
                    transport.write(new ByteBuffer[] {next}, 1);
                } else {
                    transport.progress();
                }
            }
            assertArrayEquals(new byte[] {0x54, 0x57, 0x56, 0x51, 0x50}, wire.sent.toByteArray());
            assertFalse(transport.wantsWrite());
            assertEquals(5, transport.bytesWritten);
            transport.close();
        });
    }

    @Test
    void writeFailureAfterEncryptionIsNotRetriedByTransport() throws Exception {
        onLoop((group, loop) -> {
            Wire wire = new Wire();
            NioTlsTransport transport = transport(group, loop, new Engine(Mode.NORMAL), wire);
            ByteBuffer command = ByteBuffer.wrap(new byte[] {1, 2, 3});
            transport.write(new ByteBuffer[] {command}, 1);
            assertFalse(command.hasRemaining());
            wire.brokenWrite = true;
            assertThrows(IOException.class, transport::progress);
            assertEquals(0, wire.sent.size());
            transport.close();
        });
    }

    @Test
    void overflowAndUnconsumedCiphertextCannotGrowPastTheHardLimit() throws Exception {
        onLoop((group, loop) -> {
            for (Mode mode : new Mode[] {Mode.WRAP_OVERFLOW, Mode.UNWRAP_OVERFLOW, Mode.UNDERFLOW}) {
                Wire wire = new Wire();
                wire.input = ByteBuffer.allocate(1024 * 1024);
                NioTlsTransport transport = transport(group, loop, new Engine(mode), wire);
                SSLException failure = assertThrows(SSLException.class, () -> {
                    if (mode == Mode.WRAP_OVERFLOW) {
                        transport.write(new ByteBuffer[] {ByteBuffer.wrap(new byte[] {1})}, 1);
                    } else {
                        for (int turn = 0; turn < 64; turn++) {
                            transport.resetBudget();
                            transport.read(ByteBuffer.allocate(16));
                        }
                        fail("Unbounded or stalled TLS buffer growth");
                    }
                });
                assertTrue(failure.getMessage().contains("buffer limit"));
                assertTrue(wire.totalRead <= NioTlsTransport.MAX_BUFFER_BYTES);
                transport.close();
            }
        });
    }

    @Test
    void eofAndZeroProgressFailInsteadOfCompletingOrSpinning() throws Exception {
        onLoop((group, loop) -> {
            for (Mode mode : new Mode[] {Mode.NORMAL, Mode.ZERO_READ, Mode.ZERO_WRITE}) {
                Wire wire = new Wire();
                wire.eof = mode == Mode.NORMAL;
                wire.input = ByteBuffer.wrap(new byte[] {1});
                NioTlsTransport transport = transport(group, loop, new Engine(mode), wire);
                assertThrows(SSLException.class, () -> {
                    if (mode == Mode.ZERO_WRITE) {
                        transport.progress();
                    } else {
                        transport.read(ByteBuffer.allocate(32));
                    }
                });
                transport.close();
            }
        });
    }

    @Test
    void rejectedDelegatedTasksFailTheHandshakeLocally() throws Exception {
        onLoop((group, loop) -> {
            group.tlsTasks().shutdown();
            NioTlsTransport transport = transport(group, loop, new Engine(Mode.TASK), new Wire());
            SSLException failure = assertThrows(SSLException.class, transport::progress);
            assertTrue(failure.getMessage().contains("capacity"));
            assertFalse(transport.tasksRunning());
            transport.close();
        });
    }

    private static NioTlsTransport transport(NioEventLoopGroup group, NioEventLoop loop,
        Engine engine, Wire wire) throws Exception {
        return new NioTlsTransport(engine, wire, loop, group.tlsTasks(), error -> fail(error));
    }

    private static void onLoop(Check check) throws Exception {
        try (NioEventLoopGroup group = new NioEventLoopGroup(1)) {
            NioEventLoop loop = group.next();
            CompletableFuture<Void> result = new CompletableFuture<Void>();
            loop.execute(new NioEventLoop.Task() {
                public void run() {
                    try {
                        check.run(group, loop);
                        result.complete(null);
                    } catch (Throwable failure) {
                        result.completeExceptionally(failure);
                    }
                }
                public void reject(BobaStrawConnectionException failure) {
                    result.completeExceptionally(failure);
                }
            });
            result.get(5, TimeUnit.SECONDS);
        }
    }

    private interface Check {
        void run(NioEventLoopGroup group, NioEventLoop loop) throws Exception;
    }

    private enum Mode { NORMAL, WRAP_OVERFLOW, UNWRAP_OVERFLOW, UNDERFLOW, ZERO_READ, ZERO_WRITE, TASK }

    private static final class Engine extends SSLEngine {
        private final SSLEngine delegate;
        private final Mode mode;
        private boolean taskIssued;

        Engine(Mode mode) throws Exception {
            delegate = SSLContext.getDefault().createSSLEngine();
            this.mode = mode;
        }

        private SSLEngineResult result(SSLEngineResult.Status status, int consumed, int produced) {
            return new SSLEngineResult(status, getHandshakeStatus(), consumed, produced);
        }

        public SSLEngineResult wrap(ByteBuffer[] sources, int offset, int length, ByteBuffer target) {
            if (mode == Mode.WRAP_OVERFLOW) {
                return result(SSLEngineResult.Status.BUFFER_OVERFLOW, 0, 0);
            }
            int count = 0;
            for (int index = offset; index < offset + length; index++) {
                while (sources[index].hasRemaining() && target.hasRemaining()) {
                    target.put((byte) (sources[index].get() ^ 0x55));
                    count++;
                }
            }
            return result(SSLEngineResult.Status.OK, count, count);
        }

        public SSLEngineResult unwrap(ByteBuffer source, ByteBuffer[] targets, int offset, int length) {
            if (mode == Mode.UNWRAP_OVERFLOW) {
                return result(SSLEngineResult.Status.BUFFER_OVERFLOW, 0, 0);
            }
            return result(mode == Mode.UNDERFLOW ? SSLEngineResult.Status.BUFFER_UNDERFLOW
                : SSLEngineResult.Status.OK, 0, 0);
        }

        public Runnable getDelegatedTask() {
            if (mode != Mode.TASK || taskIssued) {
                return null;
            }
            taskIssued = true;
            return () -> { };
        }
        public SSLEngineResult.HandshakeStatus getHandshakeStatus() {
            if (mode == Mode.ZERO_WRITE) {
                return SSLEngineResult.HandshakeStatus.NEED_WRAP;
            }
            return mode == Mode.TASK ? SSLEngineResult.HandshakeStatus.NEED_TASK
                : SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING;
        }
        public void beginHandshake() { }
        public void closeInbound() throws SSLException { throw new SSLException("Truncated test record"); }
        public void closeOutbound() { }
        public boolean isInboundDone() { return false; }
        public boolean isOutboundDone() { return false; }
        public SSLSession getSession() { return delegate.getSession(); }
        public String[] getSupportedCipherSuites() { return delegate.getSupportedCipherSuites(); }
        public String[] getEnabledCipherSuites() { return delegate.getEnabledCipherSuites(); }
        public void setEnabledCipherSuites(String[] value) { delegate.setEnabledCipherSuites(value); }
        public String[] getSupportedProtocols() { return delegate.getSupportedProtocols(); }
        public String[] getEnabledProtocols() { return delegate.getEnabledProtocols(); }
        public void setEnabledProtocols(String[] value) { delegate.setEnabledProtocols(value); }
        public void setUseClientMode(boolean value) { delegate.setUseClientMode(value); }
        public boolean getUseClientMode() { return delegate.getUseClientMode(); }
        public void setNeedClientAuth(boolean value) { delegate.setNeedClientAuth(value); }
        public boolean getNeedClientAuth() { return delegate.getNeedClientAuth(); }
        public void setWantClientAuth(boolean value) { delegate.setWantClientAuth(value); }
        public boolean getWantClientAuth() { return delegate.getWantClientAuth(); }
        public void setEnableSessionCreation(boolean value) { delegate.setEnableSessionCreation(value); }
        public boolean getEnableSessionCreation() { return delegate.getEnableSessionCreation(); }
    }

    private static final class Wire extends SocketChannel {
        private final ByteArrayOutputStream sent = new ByteArrayOutputStream();
        private ByteBuffer input = ByteBuffer.allocate(0);
        private int writeLimit;
        private int totalRead;
        private boolean eof;
        private boolean brokenWrite;

        Wire() { super(SelectorProvider.provider()); }
        public int write(ByteBuffer source) throws IOException {
            if (brokenWrite) { throw new IOException("Injected partial-write failure"); }
            int count = Math.min(writeLimit, source.remaining());
            for (int index = 0; index < count; index++) { sent.write(source.get()); }
            return count;
        }
        public int read(ByteBuffer target) {
            if (eof) { return -1; }
            int count = Math.min(target.remaining(), input.remaining());
            for (int index = 0; index < count; index++) { target.put(input.get()); }
            totalRead += count;
            return count;
        }
        public long write(ByteBuffer[] sources, int offset, int length) { throw new UnsupportedOperationException(); }
        public long read(ByteBuffer[] targets, int offset, int length) { throw new UnsupportedOperationException(); }
        protected void implCloseSelectableChannel() { }
        protected void implConfigureBlocking(boolean value) { }
        public SocketChannel bind(SocketAddress value) { return this; }
        public <T> SocketChannel setOption(SocketOption<T> option, T value) { return this; }
        public <T> T getOption(SocketOption<T> option) { return null; }
        public Set<SocketOption<?>> supportedOptions() { return Collections.emptySet(); }
        public SocketChannel shutdownInput() { return this; }
        public SocketChannel shutdownOutput() { return this; }
        public Socket socket() { throw new UnsupportedOperationException(); }
        public boolean isConnected() { return true; }
        public boolean isConnectionPending() { return false; }
        public boolean connect(SocketAddress value) { return true; }
        public boolean finishConnect() { return true; }
        public SocketAddress getRemoteAddress() { return null; }
        public SocketAddress getLocalAddress() { return null; }
    }
}
