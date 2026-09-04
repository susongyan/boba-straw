package io.github.susongyan.bobastraw.internal;

import io.github.susongyan.bobastraw.BobaStrawConnectionLimits;

/**
 * Thread-safe admission accounting kept separate from EventLoop-owned socket queues.
 *
 * <p>Producer threads reserve capacity before enqueuing EventLoop work, so a saturated
 * connection rejects a command before it can reach its socket. Command slots remain reserved
 * until a response is drained, while write bytes are returned incrementally as socket writes
 * consume the request frames.</p>
 */
final class ConnectionCapacity {
    private final int maxCommands;
    private final long maxBytes;
    private int commands;
    private long bytes;
    private long rejections;

    ConnectionCapacity(BobaStrawConnectionLimits limits) {
        if (limits == null) {
            throw new IllegalArgumentException("connectionLimits must not be null");
        }
        this.maxCommands = limits.maxInFlightCommands();
        this.maxBytes = limits.maxQueuedWriteBytes();
    }

    synchronized Reservation tryReserve(long commandBytes) {
        if (commandBytes < 0L) {
            throw new IllegalArgumentException("Connection capacity reservation is invalid");
        }
        if (commands == maxCommands || commandBytes > maxBytes || bytes > maxBytes - commandBytes) {
            rejections++;
            return null;
        }
        commands++;
        bytes += commandBytes;
        return new Reservation(this, commandBytes);
    }

    synchronized java.util.List<Reservation> tryReserveBatch(long[] commandBytes) {
        if (commandBytes == null || commandBytes.length == 0) {
            return java.util.Collections.emptyList();
        }
        long totalBytes = 0L;
        for (long bytesForCommand : commandBytes) {
            if (bytesForCommand < 0L || totalBytes > Long.MAX_VALUE - bytesForCommand) {
                throw new IllegalArgumentException("Connection capacity reservation is invalid");
            }
            totalBytes += bytesForCommand;
        }
        if (commandBytes.length > maxCommands || commands > maxCommands - commandBytes.length
            || totalBytes > maxBytes || bytes > maxBytes - totalBytes) {
            rejections++;
            return null;
        }
        commands += commandBytes.length;
        bytes += totalBytes;
        java.util.List<Reservation> reservations =
            new java.util.ArrayList<Reservation>(commandBytes.length);
        for (long bytesForCommand : commandBytes) {
            reservations.add(new Reservation(this, bytesForCommand));
        }
        return reservations;
    }

    synchronized int commands() {
        return commands;
    }

    synchronized long bytes() {
        return bytes;
    }

    synchronized long rejections() {
        return rejections;
    }

    private synchronized void releaseCommand() {
        commands--;
        if (commands < 0) {
            throw new IllegalStateException("Connection command capacity was released more than once");
        }
    }

    private synchronized void releaseBytes(long commandBytes) {
        bytes -= commandBytes;
        if (commands < 0 || bytes < 0L) {
            throw new IllegalStateException("Connection write capacity was released more than once");
        }
    }

    static final class Reservation {
        private final ConnectionCapacity capacity;
        private long remainingBytes;
        private boolean commandReleased;

        private Reservation(ConnectionCapacity capacity, long commandBytes) {
            this.capacity = capacity;
            this.remainingBytes = commandBytes;
        }

        synchronized void releaseWrittenBytes(long writtenBytes) {
            if (writtenBytes < 0L || writtenBytes > remainingBytes) {
                throw new IllegalStateException("Connection write capacity changed unexpectedly");
            }
            if (writtenBytes > 0L) {
                remainingBytes -= writtenBytes;
                capacity.releaseBytes(writtenBytes);
            }
        }

        synchronized void releaseAll() {
            if (remainingBytes > 0L) {
                capacity.releaseBytes(remainingBytes);
                remainingBytes = 0L;
            }
            if (!commandReleased) {
                commandReleased = true;
                capacity.releaseCommand();
            }
        }
    }
}
