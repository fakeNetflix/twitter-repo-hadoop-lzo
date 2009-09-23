/*
 * This file is part of Hadoop-Gpl-Compression.
 *
 * Hadoop-Gpl-Compression is free software: you can redistribute it
 * and/or modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation, either version 3 of
 * the License, or (at your option) any later version.
 *
 * Hadoop-Gpl-Compression is distributed in the hope that it will be
 * useful, but WITHOUT ANY WARRANTY; without even the implied warranty
 * of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Hadoop-Gpl-Compression.  If not, see
 * <http://www.gnu.org/licenses/>.
 */

package com.hadoop.compression.lzo;

import java.io.EOFException;
import java.io.IOException;
import java.util.Arrays;

import org.apache.hadoop.conf.Configurable;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.compress.CompressionCodec;
import org.apache.hadoop.io.compress.CompressionCodecFactory;

/**
 * Represents the lzo index.
 */
public class LzoIndex {
  public static final String LZO_INDEX_SUFFIX = ".index";
  public static final long NOT_FOUND = -1;

  private long[] blockPositions_;

  /**
   * Create an empty index, typically indicating no index file exists.
   */
  public LzoIndex() { }

  /**
   * Create an index specifying the number of LZO blocks in the file.
   * @param blocks
   *          The number of blocks in the LZO file the index is representing.
   */
  public LzoIndex(int blocks) {
    blockPositions_ = new long[blocks];
  }

  /**
   * Set the position for the block.
   * 
   * @param blockNumber
   *          Block to set pos for.
   * @param pos
   *          Position.
   */
  public void set(int blockNumber, long pos) {
    blockPositions_[blockNumber] = pos;
  }

  /**
   * Find the next lzo block start from the given position.
   * 
   * @param pos
   *          The position to start looking from.
   * @return Either the start position of the block or -1 if it couldn't be
   *         found.
   */
  public long findNextPosition(long pos) {
    int block = Arrays.binarySearch(blockPositions_, pos);

    if (block >= 0) {
      // direct hit on a block start position
      return blockPositions_[block];
    } else {
      block = Math.abs(block) - 1;
      if (block > blockPositions_.length - 1) {
        return NOT_FOUND;
      }
      return blockPositions_[block];
    }
  }

  /**
   * Return true if the index has no blocks set.
   * 
   * @return true if the index has no blocks set.
   */
  public boolean isEmpty() {
    return blockPositions_ == null || blockPositions_.length == 0;
  }

  /**
   * Nudge a given file slice start to the nearest LZO block start no earlier than
   * the current slice start.
   * 
   * @param start
   *          The current slice start
   * @param end
   *          The current slice end
   * @return The smallest block offset in the index between [start, end), or
   *         NOT_FOUND if there is none such.
   */
  public long alignSliceStartToIndex(long start, long end) {
    if (start != 0) {
      // find the next block position from
      // the start of the split
      long newStart = findNextPosition(start);
      if (newStart == NOT_FOUND || newStart >= end) {
        return NOT_FOUND;
      }
      start = newStart;
    }
    return start;
  }

  /**
   * Nudge a given file slice end to the nearest LZO block end no earlier than
   * the current slice end.
   * 
   * @param end
   *          The current slice end
   *          
   * @param fileSize
   *          The size of the file, i.e. the max end position.
   *          
   * @return The smallest block offset in the index between [end, fileSize].
   */
  public long alignSliceEndToIndex(long end, long fileSize) {
    long newEnd = findNextPosition(end);
    if (newEnd != NOT_FOUND) {
      end = newEnd;
    } else {
      // didn't find the next position
      // we have hit the end of the file
      end = fileSize;
    }
    return end;
  }

  /**
   * Read the index of the lzo file.
   * 
   * @param split
   *          Read the index of this file.
   * @param fs
   *          The index file is on this file system.
   * @throws IOException
   */
  public static LzoIndex readIndex(FileSystem fs, Path lzoFile) throws IOException {
    FSDataInputStream indexIn = null;
    try {
      Path indexFile = new Path(lzoFile.toString() + LZO_INDEX_SUFFIX);
      if (!fs.exists(indexFile)) {
        // return empty index, fall back to the unsplittable mode
        return new LzoIndex();
      }

      long indexLen = fs.getFileStatus(indexFile).getLen();
      int blocks = (int) (indexLen / 8);
      LzoIndex index = new LzoIndex(blocks);
      indexIn = fs.open(indexFile);
      for (int i = 0; i < blocks; i++) {
        index.set(i, indexIn.readLong());
      }
      return index;
    } finally {
      if (indexIn != null) {
        indexIn.close();
      }
    }
  }

  /**
   * Index an lzo file to allow the input format to split them into separate map
   * jobs.
   * 
   * @param fs
   *          File system that contains the file.
   * @param lzoFile
   *          the lzo file to index.
   * @throws IOException
   */
  public static void createIndex(FileSystem fs, Path lzoFile)
  throws IOException {

    Configuration conf = fs.getConf();
    CompressionCodecFactory factory = new CompressionCodecFactory(conf);
    CompressionCodec codec = factory.getCodec(lzoFile);
    ((Configurable) codec).setConf(conf);

    FSDataInputStream is = null;
    FSDataOutputStream os = null;
    Path outputFile = new Path(lzoFile.toString() + LZO_INDEX_SUFFIX);
    Path tmpOutputFile = outputFile.suffix(".tmp");

    try {
      is = fs.open(lzoFile);
      os = fs.create(tmpOutputFile);
      LzopDecompressor decompressor = (LzopDecompressor) codec.createDecompressor();
      // Solely for reading the header
      codec.createInputStream(is, decompressor);

      int numChecksums = decompressor.getChecksumsCount();

      while (true) {
        // read and ignore, we just want to get to the next int
        int uncompressedBlockSize = is.readInt();
        if (uncompressedBlockSize == 0) {
          break;
        } else if (uncompressedBlockSize < 0) {
          throw new EOFException();
        }

        int compressedBlockSize = is.readInt();
        if (compressedBlockSize <= 0) {
          throw new IOException("Could not read compressed block size");
        }

        long pos = is.getPos();
        // write the pos of the block start
        os.writeLong(pos - 8);
        // seek to the start of the next block, skip any checksums
        is.seek(pos + compressedBlockSize + (4 * numChecksums));
      }
    } finally {
      if (is != null) {
        is.close();
      }

      if (os != null) {
        os.close();
      }
    }

    fs.rename(tmpOutputFile, outputFile);
  }
}
