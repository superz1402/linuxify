package com.termux.terminal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Pins the ByteQueue close semantics the terminal session depends on:
 * close() must mean "no NEW data", never "throw away buffered output".
 * Before the fix, read() returned -1 on a closed queue even when output
 * written by the child right before it exited was still buffered — the
 * terminal lost the tail of a command's output at session end.
 */
public class ByteQueueTest {

    @Test
    public void readReturnsBufferedBytesThenEofAfterClose() throws Exception {
        ByteQueue q = new ByteQueue(64);
        q.write("hello".getBytes("US-ASCII"), 0, 5);
        q.close();

        byte[] buf = new byte[16];
        // closed but NOT empty -> still delivers the stored bytes
        assertEquals(5, q.read(buf, false));
        assertEquals("hello", new String(buf, 0, 5, "US-ASCII"));
        // now empty -> EOF
        assertEquals(-1, q.read(buf, false));
    }

    @Test
    public void readDeliversAcrossRingWrapAfterClose() throws Exception {
        ByteQueue q = new ByteQueue(8);
        q.write("12345678".getBytes("US-ASCII"), 0, 8); // fills the ring exactly
        byte[] drain = new byte[8];
        assertEquals(8, q.read(drain, false));
        q.write("abcd".getBytes("US-ASCII"), 0, 4); // head is mid-ring now (wrapped)
        q.close();
        byte[] buf = new byte[8];
        assertEquals(4, q.read(buf, false));
        assertEquals("abcd", new String(buf, 0, 4, "US-ASCII"));
        assertEquals(-1, q.read(buf, false));
    }

    @Test
    public void emptyClosedQueueReturnsEofImmediately() {
        ByteQueue q = new ByteQueue(16);
        q.close();
        assertEquals(-1, q.read(new byte[4], false));
    }

    @Test
    public void openEmptyNonBlockingReadReturnsZero() {
        ByteQueue q = new ByteQueue(16);
        assertEquals(0, q.read(new byte[4], false));
    }

    @Test
    public void blockedWriterIsReleasedByClose() throws Exception {
        final ByteQueue q = new ByteQueue(4);
        q.write("1234".getBytes("US-ASCII"), 0, 4);
        Thread writer = new Thread(() -> {
            try {
                q.write("5678".getBytes("US-ASCII"), 0, 4); // must block until close
            } catch (Exception ignored) {
            }
        });
        writer.start();
        Thread.sleep(100);
        assertTrue("writer should still be blocked while queue is full and open", writer.isAlive());
        q.close();
        writer.join(2000);
        assertFalse("writer must be released by close()", writer.isAlive());
    }

    @Test
    public void blockedReaderGetsEofWhenClosedWhileEmpty() throws Exception {
        final ByteQueue q = new ByteQueue(16);
        Thread reader = new Thread(() -> {
            try {
                assertEquals(-1, q.read(new byte[4], true));
            } catch (Exception ignored) {
            }
        });
        reader.start();
        Thread.sleep(100);
        assertTrue(reader.isAlive()); // blocked waiting
        q.close();
        reader.join(2000);
        assertFalse("reader must wake on close with EOF", reader.isAlive());
    }
}
