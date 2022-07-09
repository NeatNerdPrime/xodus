/*
 * *
 *  * Copyright 2010 - 2022 JetBrains s.r.o.
 *  *
 *  * Licensed under the Apache License, Version 2.0 (the "License");
 *  * you may not use this file except in compliance with the License.
 *  * You may obtain a copy of the License at
 *  *
 *  * https://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  * Unless required by applicable law or agreed to in writing, software
 *  * distributed under the License is distributed on an "AS IS" BASIS,
 *  * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  * See the License for the specific language governing permissions and
 *  * limitations under the License.
 *
 */

package jetbrains.exodus.log;

import jetbrains.exodus.*;
import jetbrains.exodus.bindings.LongBinding;
import org.jetbrains.annotations.NotNull;

import java.nio.ByteBuffer;
import java.util.Arrays;

public class ByteBufferByteIterableWithAddress extends ByteIterableWithAddress implements ByteBufferIterable {
    @NotNull
    private final ByteBuffer buffer;
    private final int start;
    private final int end;


    ByteBufferByteIterableWithAddress(long address, @NotNull final ByteBuffer buffer, final int start,
                                      final int length) {
        super(address);

        this.buffer = buffer;
        this.start = start;
        this.end = Math.min(start + length, this.buffer.limit());
    }


    @Override
    public final byte byteAt(final int offset) {
        return buffer.get(start + offset);
    }

    @Override
    public final long nextLong(final int offset, final int length) {
        return LongBinding.entryToUnsignedLong(buffer, start + offset, length);
    }

    @Override
    public final int getCompressedUnsignedInt() {
        int result = 0;
        int shift = 0;
        for (int i = start; ; ++i) {
            final byte b = buffer.get(i);
            result += (b & 0x7f) << shift;
            if ((b & 0x80) != 0) {
                return result;
            }
            shift += 7;
        }
    }

    @Override
    public final ByteIteratorWithAddress iterator() {
        return iterator(0);
    }

    @Override
    public final ByteIteratorWithAddress iterator(final int offset) {
        return new ByteBufferIteratorWithAddress(offset);
    }

    @Override
    public final int compareTo(final int offset, final int len, @NotNull final ByteIterable right) {
        if (right instanceof ByteBufferIterable) {
            var firstBuffer = buffer.slice(start + offset, len);
            var secondBuffer = ((ByteBufferIterable) right).getByteBuffer();

            return ByteBufferComparator.INSTANCE.compare(firstBuffer, secondBuffer);
        }

        var firstArray = new byte[len];
        buffer.get(start + offset, firstArray);

        var secondArray = right.getBytesUnsafe();

        return Arrays.compareUnsigned(firstArray, 0, firstArray.length, secondArray,
                0, right.getLength());
    }

    @Override
    public final ByteIterableWithAddress clone(final int offset) {
        return new ByteBufferByteIterableWithAddress(getDataAddress() + offset, buffer,
                start + offset, end - start - offset);
    }

    @Override
    public final int getLength() {
        return end - start;
    }

    @NotNull
    @Override
    public final ByteIterable subIterable(final int offset, final int length) {
        final int adjustedLen = Math.min(length, Math.max(getLength() - offset, 0));
        return adjustedLen == 0 ? ArrayByteIterable.EMPTY : new SubIterable(
                buffer.slice(start + offset, adjustedLen).order(buffer.order()));
    }

    @Override
    public final String toString() {
        var array = new byte[end - start];
        buffer.get(0, array);

        return Arrays.toString(array);
    }


    @Override
    public final ByteBuffer getByteBuffer() {
        return buffer.slice(start, end).asReadOnlyBuffer().order(buffer.order());
    }

    private final class ByteBufferIteratorWithAddress extends ByteIteratorWithAddress {

        private int i;

        ByteBufferIteratorWithAddress(final int offset) {
            i = start + offset;
        }

        @Override
        public long getAddress() {
            return ByteBufferByteIterableWithAddress.this.getDataAddress() + i - start;
        }

        @Override
        public boolean hasNext() {
            return i < end;
        }

        @Override
        public byte next() {
            return buffer.get(i++);
        }

        @Override
        public long skip(final long bytes) {
            final int skipped = Math.min(end - i, (int) bytes);
            i += skipped;
            return skipped;
        }

        @Override
        public int getOffset() {
            return i;
        }

        @Override
        public long nextLong(final int length) {
            final long result = LongBinding.entryToUnsignedLong(buffer, i, length);
            i += length;
            return result;
        }
    }

    private static final class SubIterable implements ByteIterable, ByteBufferIterable {
        private final ByteBuffer buffer;

        SubIterable(@NotNull final ByteBuffer bytes) {
            this.buffer = bytes;
        }

        @Override
        public int compareTo(@NotNull final ByteIterable right) {
            if (right instanceof ByteBufferIterable) {
                return ByteBufferComparator.INSTANCE.compare(buffer,
                        ((ByteBufferIterable) right).getByteBuffer());
            }

            var secondArray = right.getBytesUnsafe();
            var secondBuffer = ByteBuffer.wrap(secondArray);
            return ByteBufferComparator.INSTANCE.compare(buffer, secondBuffer);
        }

        @Override
        public ByteIterator iterator() {
            return getIterator();
        }


        ByteIterator getIterator() {
            return new SubIterable.SubIterableByteIterator(buffer.asReadOnlyBuffer());
        }

        @Override
        public byte[] getBytesUnsafe() {
            var array = new byte[buffer.limit()];
            buffer.get(0, array);

            return array;
        }

        @Override
        public int getLength() {
            return buffer.limit();
        }

        @Override
        public @NotNull ByteIterable subIterable(int offset, int length) {
            return new SubIterable(buffer.slice(offset, length).order(buffer.order()));
        }

        @Override
        public ByteBuffer getByteBuffer() {
            return buffer.asReadOnlyBuffer().order(buffer.order());
        }

        private static class SubIterableByteIterator extends ByteIterator implements BlockByteIterator {
            final ByteBuffer buffer;

            private SubIterableByteIterator(final ByteBuffer buffer) {
                this.buffer = buffer;
            }

            @Override
            public boolean hasNext() {
                return buffer.remaining() > 0;
            }

            @Override
            public byte next() {
                return buffer.get();
            }

            @Override
            public long skip(long bytes) {
                final int result = Math.min(buffer.remaining(), (int) bytes);
                buffer.position(buffer.position() + result);
                return result;
            }

            @Override
            public int nextBytes(byte[] array, int off, int len) {
                final int result = Math.min(buffer.remaining(), len);
                buffer.get(array, off, len);
                return result;
            }
        }
    }
}
