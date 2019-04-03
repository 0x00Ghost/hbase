/**
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.hbase.replication.regionserver;

import java.io.EOFException;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.OptionalLong;
import java.util.concurrent.PriorityBlockingQueue;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hbase.HConstants;
import org.apache.hadoop.hbase.ServerName;
import org.apache.hadoop.hbase.regionserver.wal.ProtobufLogReader;
import org.apache.hadoop.hbase.util.CancelableProgressable;
import org.apache.hadoop.hbase.util.FSUtils;
import org.apache.hadoop.hbase.util.LeaseNotRecoveredException;
import org.apache.hadoop.hbase.wal.FSWALIdentity;
import org.apache.hadoop.hbase.wal.WAL.Entry;
import org.apache.hadoop.hbase.wal.WAL.Reader;
import org.apache.hadoop.hbase.wal.WALIdentity;
import org.apache.hadoop.hbase.wal.WALProvider;
import org.apache.hadoop.ipc.RemoteException;
import org.apache.yetus.audience.InterfaceAudience;
import org.apache.yetus.audience.InterfaceStability;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.google.common.annotations.VisibleForTesting;

/**
 * Streaming access to WAL entries. This class is given a queue of WAL {@link Path}, and continually
 * iterates through all the WAL {@link Entry} in the queue. When it's done reading from a Path, it
 * dequeues it and starts reading from the next.
 */
@InterfaceAudience.Private
@InterfaceStability.Evolving
public class FSWALEntryStream implements WALEntryStream {
  private static final Logger LOG = LoggerFactory.getLogger(FSWALEntryStream.class);

  private FileSystem fs;

  private boolean eofAutoRecovery;
  protected Reader reader;
  protected WALIdentity currentWAlIdentity;
  // cache of next entry for hasNext()
  protected Entry currentEntry;
  // position for the current entry. As now we support peek, which means that the upper layer may
  // choose to return before reading the current entry, so it is not safe to return the value below
  // in getPosition.
  protected long currentPositionOfEntry = 0;
  // position after reading current entry
  protected long currentPositionOfReader = 0;
  protected final PriorityBlockingQueue<WALIdentity> logQueue;
  protected final Configuration conf;
  // which region server the WALs belong to
  protected final ServerName serverName;
  protected final MetricsSource metrics;

  protected final WALProvider walProvider;

  /**
   * Create an entry stream over the given queue at the given start position
   * @param logQueue the queue of WAL walIds
   * @param conf {@link Configuration} to use to create {@link Reader} for this stream
   * @param startPosition the position in the first WAL to start reading at
   * @param serverName the server name which all WALs belong to
   * @param metrics replication metrics
   * @param walProvider wal provider
   */

  public FSWALEntryStream(FileSystem fs, PriorityBlockingQueue<WALIdentity> logQueue,
      Configuration conf, long startPosition, ServerName serverName, MetricsSource metrics,
      WALProvider walProvider) throws IOException {
    this.logQueue = logQueue;
    this.conf = conf;
    this.currentPositionOfEntry = startPosition;
    this.serverName = serverName;
    this.metrics = metrics;
    this.walProvider = walProvider;
    this.fs = fs;
    this.eofAutoRecovery = conf.getBoolean("replication.source.eof.autorecovery", false);
  }

  /**
   * @return true if there is another WAL {@link Entry}
   */
  @Override
  public boolean hasNext() throws IOException {
    if (currentEntry == null) {
      try {
        tryAdvanceEntry();
      } catch (IOException e) {
        handleIOException(logQueue.peek(), e);
      }
    }
    return currentEntry != null;
  }

  /**
   * Returns the next WAL entry in this stream but does not advance.
   */
  @Override
  public Entry peek() throws IOException {
    return hasNext() ? currentEntry : null;
  }

  /**
   * Returns the next WAL entry in this stream and advance the stream.
   */
  @Override
  public Entry next() throws IOException {
    Entry save = peek();
    currentPositionOfEntry = currentPositionOfReader;
    currentEntry = null;
    return save;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void close() throws IOException {
    closeReader();
  }

  /**
   * @return the position of the last Entry returned by next()
   */
  @Override
  public long getPosition() {
    return currentPositionOfEntry;
  }

  /**
   * @return the {@link WALIdentity} of the current WAL
   */
  @Override
  public WALIdentity getCurrentWalIdentity() {
    return currentWAlIdentity;
  }

  protected String getCurrentWalIdStat() {
    StringBuilder sb = new StringBuilder();
    if (currentWAlIdentity != null) {
      sb.append("currently replicating from: ").append(currentWAlIdentity).append(" at position: ")
          .append(currentPositionOfEntry).append("\n");
    } else {
      sb.append("no replication ongoing, waiting for new log");
    }
    return sb.toString();
  }

  /**
   * Should be called if the stream is to be reused (i.e. used again after hasNext() has returned
   * false)
   */
  @Override
  public void reset() throws IOException {
    if (reader != null && currentWAlIdentity != null) {
      resetReader();
    }
  }

  protected void setPosition(long position) {
    currentPositionOfEntry = position;
  }

  private void setCurrentWalId(WALIdentity walId) {
    this.currentWAlIdentity = walId;
  }

  private void tryAdvanceEntry() throws IOException {
    if (checkReader()) {
      boolean beingWritten = readNextEntryAndRecordReaderPosition();
      if (currentEntry == null && !beingWritten) {
        // no more entries in this log file, and the file is already closed, i.e, rolled
        // Before dequeueing, we should always get one more attempt at reading.
        // This is in case more entries came in after we opened the reader, and the log is rolled
        // while we were reading. See HBASE-6758
        resetReader();
        readNextEntryAndRecordReaderPosition();
        if (currentEntry == null) {
          if (checkAllBytesParsed()) { // now we're certain we're done with this log file
            dequeueCurrentLog();
            if (openNextLog()) {
              readNextEntryAndRecordReaderPosition();
            }
          }
        }
      }
      // if currentEntry != null then just return
      // if currentEntry == null but the file is still being written, then we should not switch to
      // the next log either, just return here and try next time to see if there are more entries in
      // the current file
    }
    // do nothing if we don't have a WAL Reader (e.g. if there's no logs in queue)
  }

  private void dequeueCurrentLog() throws IOException {
    LOG.debug("Reached the end of log {}", currentWAlIdentity);
    closeReader();
    logQueue.remove();
    setPosition(0);
    metrics.decrSizeOfLogQueue();
  }

  private void closeReader() throws IOException {
    if (reader != null) {
      reader.close();
      reader = null;
    }
  }

  // if we don't have a reader, open a reader on the next log
  private boolean checkReader() throws IOException {
    if (reader == null) {
      return openNextLog();
    }
    return true;
  }

  // open a reader on the next log in queue
  private boolean openNextLog() throws IOException {
    WALIdentity nextWalId = logQueue.peek();
    if (nextWalId != null) {
      openReader(nextWalId);
      if (reader != null) {
        return true;
      }
    } else {
      // no more files in queue, this could happen for recovered queue, or for a wal group of a sync
      // replication peer which has already been transited to DA or S.
      setCurrentWalId(null);
    }
    return false;
  }

  private void openReader(WALIdentity walId) throws IOException {
    try {
      // Detect if this is a new file, if so get a new reader else
      // reset the current reader so that we see the new data
      if (reader == null || !getCurrentWalIdentity().equals(walId)) {
        closeReader();
        reader = createReader(walId, conf);
        seek();
        setCurrentWalId(walId);
      } else {
        resetReader();
      }
    } catch (RemoteException re) {
      IOException ioe = re.unwrapRemoteException(FileNotFoundException.class);
      if (!(ioe instanceof FileNotFoundException)) {
        throw ioe;
      }
      handleIOException(walId, ioe);
    } catch (IOException ioe) {
      handleIOException(walId, ioe);
    } catch (NullPointerException npe) {
      // Workaround for race condition in HDFS-4380
      // which throws a NPE if we open a file before any data node has the most recent block
      // Just sleep and retry. Will require re-reading compressed WALs for compressionContext.
      LOG.warn("Got NPE opening reader, will retry.");
      reader = null;
    }
  }

  private void resetReader() throws IOException {
    try {
      currentEntry = null;
      reader.reset();
      seek();
    } catch (IOException io) {
      handleIOException(currentWAlIdentity, io);
    } catch (NullPointerException npe) {
      throw new IOException("NPE resetting reader, likely HDFS-4380", npe);
    }
  }

  private void seek() throws IOException {
    if (currentPositionOfEntry != 0) {
      reader.seek(currentPositionOfEntry);
    }
  }

  @Override
  public Entry next(Entry reuse) throws IOException {
    return reader.next(reuse);
  }

  @Override
  public void seek(long pos) throws IOException {
    reader.seek(pos);
  }

  // HBASE-15984 check to see we have in fact parsed all data in a cleanly closed file
  private boolean checkAllBytesParsed() throws IOException {
    // -1 means the wal wasn't closed cleanly.
    final long trailerSize = currentTrailerSize();
    FileStatus stat = null;
    try {
      stat = fs.getFileStatus(((FSWALIdentity) this.currentWAlIdentity).getPath());
    } catch (IOException exception) {
      LOG.warn("Couldn't get file length information about log {}, it {} closed cleanly {}",
        currentWAlIdentity, trailerSize < 0 ? "was not" : "was", getCurrentWalIdStat());
      metrics.incrUnknownFileLengthForClosedWAL();
    }
    // Here we use currentPositionOfReader instead of currentPositionOfEntry.
    // We only call this method when currentEntry is null so usually they are the same, but there
    // are two exceptions. One is we have nothing in the file but only a header, in this way
    // the currentPositionOfEntry will always be 0 since we have no change to update it. The other
    // is that we reach the end of file, then currentPositionOfEntry will point to the tail of the
    // last valid entry, and the currentPositionOfReader will usually point to the end of the file.
    if (stat != null) {
      if (trailerSize < 0) {
        if (currentPositionOfReader < stat.getLen()) {
          final long skippedBytes = stat.getLen() - currentPositionOfReader;
          LOG.debug(
            "Reached the end of WAL file '{}'. It was not closed cleanly,"
                + " so we did not parse {} bytes of data. This is normally ok.",
            currentWAlIdentity, skippedBytes);
          metrics.incrUncleanlyClosedWALs();
          metrics.incrBytesSkippedInUncleanlyClosedWALs(skippedBytes);
        }
      } else if (currentPositionOfReader + trailerSize < stat.getLen()) {
        LOG.warn("Processing end of WAL file '{}'. At position {}, which is too far away from"
            + " reported file length {}. Restarting WAL reading (see HBASE-15983 for details). {}",
          currentWAlIdentity, currentPositionOfReader, stat.getLen(), getCurrentWalIdStat());
        setPosition(0);
        resetReader();
        metrics.incrRestartedWALReading();
        metrics.incrRepeatedFileBytes(currentPositionOfReader);
        return false;
      }
    }
    if (LOG.isTraceEnabled()) {
      LOG.trace("Reached the end of log " + this.currentWAlIdentity
          + ", and the length of the file is " + (stat == null ? "N/A" : stat.getLen()));
    }
    metrics.incrCompletedWAL();
    return true;
  }

  private Path getArchivedLog(Path path) throws IOException {
    Path rootDir = FSUtils.getRootDir(conf);

    // Try found the log in old dir
    Path oldLogDir = new Path(rootDir, HConstants.HREGION_OLDLOGDIR_NAME);
    Path archivedLogLocation = new Path(oldLogDir, path.getName());
    if (fs.exists(archivedLogLocation)) {
      LOG.info("Log " + path + " was moved to " + archivedLogLocation);
      return archivedLogLocation;
    }

    // Try found the log in the seperate old log dir
    oldLogDir = new Path(rootDir, new StringBuilder(HConstants.HREGION_OLDLOGDIR_NAME)
        .append(Path.SEPARATOR).append(serverName.getServerName()).toString());
    archivedLogLocation = new Path(oldLogDir, path.getName());
    if (fs.exists(archivedLogLocation)) {
      LOG.info("Log " + path + " was moved to " + archivedLogLocation);
      return archivedLogLocation;
    }

    LOG.error("Couldn't locate log: " + path);
    return path;
  }

  private void handleFileNotFound(FSWALIdentity walId, FileNotFoundException fnfe)
      throws IOException {

    // If the log was archived, continue reading from there
    FSWALIdentity archivedLog = new FSWALIdentity(getArchivedLog(walId.getPath()));
    if (!walId.equals(archivedLog)) {
      openReader(archivedLog);
    } else {
      throw fnfe;
    }
  }

  // For HBASE-15019
  private void recoverLease(final Configuration conf, final Path path) {
    try {
      final FileSystem dfs = FSUtils.getCurrentFileSystem(conf);
      FSUtils fsUtils = FSUtils.getInstance(dfs, conf);
      fsUtils.recoverFileLease(dfs, path, conf, new CancelableProgressable() {
        @Override
        public boolean progress() {
          LOG.debug("recover WAL lease: " + path);
          return true;
        }
      });
    } catch (IOException e) {
      LOG.warn("unable to recover lease for WAL: " + path, e);
    }
  }

  private void handleIOException(WALIdentity walId, IOException e) throws IOException {
    try {
      throw e;
    } catch (FileNotFoundException fnfe) {
      handleFileNotFound((FSWALIdentity) walId, fnfe);
    } catch (EOFException eo) {
      handleEofException(eo);
    } catch (LeaseNotRecoveredException lnre) {
      // HBASE-15019 the WAL was not closed due to some hiccup.
      LOG.warn("Try to recover the WAL lease " + currentWAlIdentity, lnre);
      recoverLease(conf, ((FSWALIdentity) currentWAlIdentity).getPath());
      reader = null;
    }
  }

  private long currentTrailerSize() {
    long size = -1L;
    if (reader instanceof ProtobufLogReader) {
      final ProtobufLogReader pblr = (ProtobufLogReader) reader;
      size = pblr.trailerSize();
    }
    return size;
  }

  // if we get an EOF due to a zero-length log, and there are other logs in queue
  // (highly likely we've closed the current log), we've hit the max retries, and autorecovery is
  // enabled, then dump the log
  private void handleEofException(IOException e) {
    if ((e instanceof EOFException || e.getCause() instanceof EOFException) && logQueue.size() > 1
        && this.eofAutoRecovery) {
      WALIdentity walId = logQueue.peek();
      try {
        Path path = ((FSWALIdentity) walId).getPath();
        if (fs.getFileStatus(path).getLen() == 0) {
          LOG.warn("Forcing removal of 0 length log in queue: {} ", walId);
          logQueue.remove();
          currentPositionOfEntry = 0;
        }
      } catch (IOException ioe) {
        LOG.warn("Couldn't get file length information about log: {} ", walId);
      }
    }
  }

  private Reader createReader(WALIdentity walId, Configuration conf) throws IOException {
    Path path = ((FSWALIdentity) walId).getPath();
    return walProvider.createReader(fs, path, null, true);
  }

  /**
   * Returns whether the file is opened for writing.
   */
  protected boolean readNextEntryAndRecordReaderPosition() throws IOException {
    Entry readEntry = reader.next();
    long readerPos = reader.getPosition();
    OptionalLong fileLength = getWALFileLengthProvider()
        .getLogFileSizeIfBeingWritten(((FSWALIdentity) currentWAlIdentity).getPath());
    if (fileLength.isPresent() && readerPos > fileLength.getAsLong()) {
      // see HBASE-14004, for AsyncFSWAL which uses fan-out, it is possible that we read uncommitted
      // data, so we need to make sure that we do not read beyond the committed file length.
      if (LOG.isDebugEnabled()) {
        LOG.debug("The provider tells us the valid length for " + currentWAlIdentity + " is "
            + fileLength.getAsLong() + ", but we have advanced to " + readerPos);
      }
      resetReader();
      return true;
    }
    if (readEntry != null) {
      metrics.incrLogEditsRead();
      metrics.incrLogReadInBytes(readerPos - currentPositionOfEntry);
    }
    currentEntry = readEntry; // could be null
    this.currentPositionOfReader = readerPos;
    return fileLength.isPresent();
  }

  @VisibleForTesting
  public WALFileLengthProvider getWALFileLengthProvider() {
    return path -> this.walProvider.getWALs().stream()
        .map(w -> w.getLogFileSizeIfBeingWritten(path)).filter(o -> o.isPresent()).findAny()
        .orElse(OptionalLong.empty());
  }

}
