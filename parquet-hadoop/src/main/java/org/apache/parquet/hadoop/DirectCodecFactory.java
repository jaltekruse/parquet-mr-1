/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.parquet.hadoop;


import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.nio.ByteBuffer;

import org.apache.hadoop.io.compress.CodecPool;
import org.apache.hadoop.io.compress.CompressionOutputStream;
import org.apache.hadoop.io.compress.Compressor;
import org.apache.parquet.Log;
import org.apache.parquet.Preconditions;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.io.compress.CompressionCodec;
import org.apache.hadoop.io.compress.Decompressor;
import org.xerial.snappy.Snappy;

import org.apache.parquet.bytes.ByteBufferAllocator;
import org.apache.parquet.bytes.BytesInput;
import org.apache.parquet.hadoop.metadata.CompressionCodecName;

/**
 * Factory to produce compressors and decompressors that operate on java
 * direct memory, without requiring a copy into heap memory (where possible).
 */
public class DirectCodecFactory extends CodecFactory implements AutoCloseable {
//  private static final Log LOG = Log.getLog(DirectCodecFactory.class);

  private final ByteBufferAllocator allocator;

  public DirectCodecFactory(Configuration config, ByteBufferAllocator allocator) {
    super(config);
    Preconditions.checkNotNull(allocator, "ByreBufferAllocator cannot be null.");
    this.allocator = allocator;
  }

  private ByteBuffer ensure(ByteBuffer buffer, int size) {
    if (buffer == null) {
      buffer = allocator.allocate(size);
    } else if (buffer.capacity() >= size) {
      buffer.clear();
    } else {
      allocator.release(buffer);
      release(buffer);
      buffer = allocator.allocate(size);
    }
    return buffer;
  }

  ByteBuffer release(ByteBuffer buffer) {
    if (buffer != null) {
      allocator.release(buffer);
    }
    return null;
  }

  public static class HeapBytesDecompressor extends BytesDecompressor {

    private final CompressionCodec codec;
    private final Decompressor decompressor;

    public HeapBytesDecompressor(CompressionCodec codec) {
      // This is only here for compatibility with the old interface, these are unused
      // in the constructor above
      super(null, codec);
      this.codec = codec;
      if (codec != null) {
        decompressor = CodecPool.getDecompressor(codec);
      } else {
        decompressor = null;
      }
    }

    public BytesInput decompress(BytesInput bytes, int uncompressedSize) throws IOException {
      final BytesInput decompressed;
      if (codec != null) {
        decompressor.reset();
        InputStream is = codec.createInputStream(bytes.toInputStream(), decompressor);
        decompressed = BytesInput.from(is, uncompressedSize);
      } else {
        decompressed = bytes;
      }
      return decompressed;
    }

    protected void release() {
      if (decompressor != null) {
        CodecPool.returnDecompressor(decompressor);
      }
    }
  }

  /**
   * Encapsulates the logic around hadoop compression
   *
   * @author Julien Le Dem
   *
   */
  public static class HeapBytesCompressor extends BytesCompressor {

    private final CompressionCodec codec;
    private final Compressor compressor;
    private final ByteArrayOutputStream compressedOutBuffer;
    private final CompressionCodecName codecName;

    public HeapBytesCompressor(CompressionCodecName codecName, CompressionCodec codec, int pageSize) {
      // This is only here for compatibility with the old interface, these are unused
      // in the constructor above
      super(codecName, codec, 0);
      this.codecName = codecName;
      this.codec = codec;
      if (codec != null) {
        this.compressor = CodecPool.getCompressor(codec);
        this.compressedOutBuffer = new ByteArrayOutputStream(pageSize);
      } else {
        this.compressor = null;
        this.compressedOutBuffer = null;
      }
    }

    public BytesInput compress(BytesInput bytes) throws IOException {
      final BytesInput compressedBytes;
      if (codec == null) {
        compressedBytes = bytes;
      } else {
        compressedOutBuffer.reset();
        if (compressor != null) {
          // null compressor for non-native gzip
          compressor.reset();
        }
        CompressionOutputStream cos = codec.createOutputStream(compressedOutBuffer, compressor);
        bytes.writeAllTo(cos);
        cos.finish();
        cos.close();
        compressedBytes = BytesInput.from(compressedOutBuffer);
      }
      return compressedBytes;
    }

    protected void release() {
      if (compressor != null) {
        CodecPool.returnCompressor(compressor);
      }
    }

    public CompressionCodecName getCodecName() {
      return codecName;
    }

  }

  @Override
  protected BytesCompressor createCompressor(final CompressionCodecName codecName, final CompressionCodec codec,
                                             int pageSize) {

    if (codec == null) {
      return new NoopCompressor();
    } else if (codecName == CompressionCodecName.SNAPPY) {
      // avoid using the Parquet Snappy codec since it allocates direct buffers at awkward spots.
      return new SnappyCompressor();
    } else {

      // todo: move zlib above since it also generates allocateDirect calls.
      return new HeapBytesCompressor(codecName, codec, pageSize);
    }
  }

  @Override
  protected BytesDecompressor createDecompressor(final CompressionCodec codec) {
    // This is here so that debugging can be done if we see inconsistencies between our decompression and upstream
    // decompression.
    // if (true) {
    // return new HeapFakeDirect(codec);
    // }

    if (codec == null) {
      return new NoopDecompressor();
    } else if (DirectCodecPool.INSTANCE.codec(codec).supportsDirectDecompression()) {
      return new FullDirectDecompressor(codec);
    } else {
      return new IndirectDecompressor(codec);
    }
  }

  public void close() {
    release();
  }

  /**
   * Keeping this here for future debugging versus using custom implementations below.
   */
  private class HeapFakeDirect extends DirectBytesDecompressor {

    private final ExposedHeapBytesDecompressor innerCompressor;

    public HeapFakeDirect(CompressionCodec codec){
      innerCompressor = new ExposedHeapBytesDecompressor(codec);
    }

    @Override
    public void decompress(ByteBuffer input, int compressedSize, ByteBuffer output, int uncompressedSize)
        throws IOException {
      BytesInput uncompressed = decompress(BytesInput.from(input, 0, compressedSize), uncompressedSize);
      output.clear();
    }

    @Override
    public BytesInput decompress(BytesInput paramBytesInput, int uncompressedSize) throws IOException {
      return innerCompressor.decompress(paramBytesInput, uncompressedSize);
    }

    @Override
    protected void release() {
      innerCompressor.release();
    }

  }

  private class ExposedHeapBytesDecompressor extends HeapBytesDecompressor {
    public ExposedHeapBytesDecompressor(CompressionCodec codec) {
      super(codec);
    }

    public void release() {
      super.release();
    }
  }

  public class IndirectDecompressor extends DirectBytesDecompressor {
    private final Decompressor decompressor;

    public IndirectDecompressor(CompressionCodec codec) {
      this.decompressor = DirectCodecPool.INSTANCE.codec(codec).borrowDecompressor();
    }

    @Override
    public BytesInput decompress(BytesInput bytes, int uncompressedSize) throws IOException {
      decompressor.reset();
      byte[] inputBytes = bytes.toByteArray();
      decompressor.setInput(inputBytes, 0, inputBytes.length);
      byte[] output = new byte[uncompressedSize];
      decompressor.decompress(output, 0, uncompressedSize);
      return BytesInput.from(output);
    }

    @Override
    public void decompress(ByteBuffer input, int compressedSize, ByteBuffer output, int uncompressedSize)
        throws IOException {

      decompressor.reset();
      byte[] inputBytes = new byte[compressedSize];
      input.position(0);
      input.get(inputBytes);
      decompressor.setInput(inputBytes, 0, inputBytes.length);
      byte[] outputBytes = new byte[uncompressedSize];
      decompressor.decompress(outputBytes, 0, uncompressedSize);
      output.clear();
      output.put(outputBytes);
    }

    @Override
    protected void release() {
      DirectCodecPool.INSTANCE.returnDecompressor(decompressor);
    }
  }

  public class FullDirectDecompressor extends DirectBytesDecompressor {
    private final Object decompressor;
    private ByteBuffer compressedBuffer;
    private ByteBuffer uncompressedBuffer;
    private ExposedHeapBytesDecompressor extraDecompressor;
    public FullDirectDecompressor(CompressionCodec codec){
      this.decompressor = DirectCodecPool.INSTANCE.codec(codec).borrowDirectDecompressor();
      this.extraDecompressor = new ExposedHeapBytesDecompressor(codec);
    }

    @Override
    public BytesInput decompress(BytesInput compressedBytes, int uncompressedSize) throws IOException {
    	return extraDecompressor.decompress(compressedBytes, uncompressedSize);
    }

    @Override
    public void decompress(ByteBuffer input, int compressedSize, ByteBuffer output, int uncompressedSize)
        throws IOException {
      output.clear();
      try {
        DirectCodecPool.DECOMPRESS_METHOD.invoke(decompressor, (ByteBuffer) input.limit(compressedSize), (ByteBuffer) output.limit(uncompressedSize));
      } catch (IllegalAccessException e) {
        e.printStackTrace();
      } catch (InvocationTargetException e) {
        e.printStackTrace();
      }
      output.position(uncompressedSize);
    }

    @Override
    protected void release() {
      compressedBuffer = DirectCodecFactory.this.release(compressedBuffer);
      uncompressedBuffer = DirectCodecFactory.this.release(uncompressedBuffer);
      DirectCodecPool.INSTANCE.returnDecompressor(decompressor);
      extraDecompressor.release();
    }

  }

  public class NoopDecompressor extends DirectBytesDecompressor {

    @Override
    public void decompress(ByteBuffer input, int compressedSize, ByteBuffer output, int uncompressedSize)
        throws IOException {
      Preconditions.checkArgument(compressedSize == uncompressedSize,
          "Non-compressed data did not have matching compressed and uncompressed sizes.");
      output.clear();
      output.put((ByteBuffer) input.duplicate().position(0).limit(compressedSize));
    }

    @Override
    public BytesInput decompress(BytesInput bytes, int uncompressedSize) throws IOException {
      return bytes;
    }

    @Override
    protected void release() {
    }

  }

  public class SnappyCompressor extends BytesCompressor {

    private ByteBuffer incoming;
    private ByteBuffer outgoing;

    public SnappyCompressor() {
      super(null,null,0);
    }

    @Override
    public BytesInput compress(BytesInput bytes) throws IOException {
      int maxOutputSize = Snappy.maxCompressedLength((int) bytes.size());
      ByteBuffer bufferIn = bytes.toByteBuffer();
      outgoing = ensure(outgoing, maxOutputSize);
      final int size;
      if (bufferIn.isDirect()) {
        size = Snappy.compress(bufferIn, outgoing);
      } else {
        this.incoming = ensure(this.incoming, (int) bytes.size());
        this.incoming.put(bufferIn);
        this.incoming.flip();
        size = Snappy.compress(this.incoming, outgoing);
      }

      return BytesInput.from(outgoing, 0, (int) size);
    }

    @Override
    public CompressionCodecName getCodecName() {
      return CompressionCodecName.SNAPPY;
    }

    @Override
    protected void release() {
      outgoing = DirectCodecFactory.this.release(outgoing);
      incoming = DirectCodecFactory.this.release(incoming);
    }

  }

  public static class NoopCompressor extends BytesCompressor {

    public NoopCompressor() {
      super(null, null, 0);
    }

    @Override
    public BytesInput compress(BytesInput bytes) throws IOException {
      return bytes;
    }

    @Override
    public CompressionCodecName getCodecName() {
      return CompressionCodecName.UNCOMPRESSED;
    }

    @Override
    protected void release() {}

  }

  public abstract class DirectBytesDecompressor extends CodecFactory.BytesDecompressor {
    public DirectBytesDecompressor() {
      super(null, null);
    }

    public abstract void decompress(ByteBuffer input, int compressedSize, ByteBuffer output, int uncompressedSize)
        throws IOException;
  }

}
