/**
 * (c) Copyright 2013 WibiData, Inc.
 *
 * See the NOTICE file distributed with this work for additional
 * information regarding copyright ownership.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.kiji.express.flow

import scala.collection.JavaConverters.asScalaIteratorConverter
import scala.ref.WeakReference

import java.io.Closeable

import org.kiji.annotations.ApiAudience
import org.kiji.annotations.ApiStability
import org.kiji.schema.{MapFamilyVersionIterator, ColumnVersionIterator, KijiRowData}
import org.kiji.express.flow.util.AvroUtil
import scala.collection.mutable

/**
 * TransientPagedKijiSeq provides a Seq-like API for paged columns in KijiExpress. It wraps a
 * KijiRowData and pages through it according to a ColumnInputSpec.
 *
 * The necessary open connection to Kiji is not closed until the shutdown hook is called.  This
 * means if the JVM is aborted, the open connections will not be cleanly closed,
 * but that would be the case in all implementations.  The reason the connections are not closed
 * during normal execution is to provide the Seq-like API for users of Express.
 *
 * When any `Seq` operation is called on a `TransientPagedKijiSeq`,
 * the `TransientPagedKijiSeq` will create a new iterator from the rowData,
 * create a stream from the iterator, and proxy the call through to the new stream. Finally,
 * the created stream is cached in a weak reference so any subsequent `Seq` operations on the
 * `TransientPagedKijiSeq` do not require creating a new iterator over the backing collection,
 * as long as the garbage collector has not claimed the cached stream.
 *
 * As a result of using a weak reference cache, it is safe to hold a reference to a
 * `TransientStream` and realize all values, for example by using `foreach`.  This is not possible
 * with a [[scala.collection.immutable.Stream]], because holding the `head` of a `Stream` will cause
 * it not to be garbage collected.  Note, however, that operations on `TransientStream` never return
 * another `TransientStream`, instead a `Stream` is returned, with all the normal stream caveats
 * attached.
 *
 * {{
 *    val tstream = new TransientPagedKijiSeq(...)
 *
 *    tstream.foreach { x => println(x) } // OK! if the collection is larger than memory,
 *                                        // the cached stream will be automatically GC'd
 *
 *    val stream = tstream.toStream // also could use tail, or any method resulting in a new seq
 *    stream.foreach { x => println(x) } // Danger! Will potentially result in an OutOfMemoryError,
 *                                       // because 'stream' holds a reference to the head
 * }}
 *
 * @param row to wrap.
 * @param columnSpec specifying how to page through row.
 * @tparam T the type of the FlowCells of that column in that row.
 */
@ApiAudience.Framework
@ApiStability.Stable
class TransientPagedKijiSeq[T](
    row: KijiRowData,
    columnSpec: ColumnInputSpec
) extends Stream[FlowCell[T]] {
  /** Default number of qualifiers to retrieve when paging in a map type family.*/
  private val qualifierPageSize: Int = 1000

  /**
   * Tracks all the resources to close when this instance is closed.  Should not be accessed
   * directly, since it may be null on deserialization of this object.  Use
   * {@link resourcesToClose()} instead.
   *
   * This list is mutated.
   */
  @transient
  private var resourcesBuffer: mutable.Buffer[WeakReference[Iterable]] = mutable.Buffer()

  /**
   * The cache of the actual stream.  It may be null after deserialization of this object.
   */
  @volatile @transient private var streamCache: WeakReference[Stream[FlowCell[T]]] =
    new WeakReference(null)

  // During construction of this object, register the JVM shutdown hook.
  Runtime.getRuntime.addShutdownHook(new ShutdownHook())

  /**
   * Returns the mutable buffer tracking all the resources this object needs to close.  If the
   * returned buffer is mutated, the underlying buffer is also mutated.
   *
   * @return
   */
  private def resourcesToClose(): mutable.Buffer[WeakReference[Closeable]] = {
    if (resourcesBuffer == null) {
      this.synchronized {
        if (resourcesBuffer == null) {
          resourcesBuffer = mutable.Buffer()
        }
        resourcesBuffer
      }
    }
    resourcesBuffer
  }

  def genItr(): Iterator[FlowCell[T]] = {
    columnSpec match {
      case QualifiedColumnInputSpec(family, qualifier, _, _, pagingSpec, _) => {
        val pageSize: Int = pagingSpec.cellsPerPage.get // TODO(remove) require pagingSpec
        val newItr = new ColumnVersionIterator(row, family, qualifier, pageSize)
        resourcesToClose.append(new WeakReference(newItr))
        newItr
          .asScala
          .map { entry: java.util.Map.Entry[java.lang.Long,_] =>
            FlowCell(
              family,
              qualifier,
              entry.getKey,
              AvroUtil.avroToScala(entry.getValue)
            )
          }
          .asInstanceOf[Iterator[FlowCell[T]]]
      }
      case ColumnFamilyInputSpec(family, _, _, pagingSpec, _) => {
        val pageSize: Int = pagingSpec.cellsPerPage.get // TODO(remove) require pagingSpec
        val newItr = new MapFamilyVersionIterator(row, family, qualifierPageSize, pageSize)
        resourcesToClose.append(new WeakReference(newItr))
        newItr
          .asScala
          .map { entry: MapFamilyVersionIterator.Entry[_] =>
            FlowCell(
              family,
              entry.getQualifier,
              entry.getTimestamp,
              AvroUtil.avroToScala(entry.getValue))
          }
          .asInstanceOf[Iterator[FlowCell[T]]]
      }
    }
  }

  override def toStream: Stream[FlowCell[T]] =
    if (null == streamCache) {
      this.synchronized {
        if (null == streamCache) {
          val newStream = genItr().toStream
          streamCache = new WeakReference(newStream)
          newStream
        } else {
          streamCache.get.getOrElse {
            val newStream = genItr().toStream
            streamCache = new WeakReference(newStream)
            newStream
          }
        }
      }
    } else {
      streamCache.get.getOrElse {
        this.synchronized {
          streamCache.get.getOrElse {
            val newStream = genItr().toStream
            streamCache = new WeakReference(newStream)
            newStream
          }
        }
      }
    }

  override protected def tailDefined: Boolean = streamCache.get.isDefined

  override def head: FlowCell[T] = toStream.head

  override def tail: Stream[FlowCell[T]] = toStream.tail

  override def force: Stream[FlowCell[T]] = toStream.force

  override def hasDefiniteSize: Boolean = streamCache.get.isDefined && toStream.hasDefiniteSize

  override def isEmpty: Boolean = toStream.isEmpty

  override def toString: String =
    Option(streamCache)
      .map(s => "Transient" + s.toString)
      .getOrElse("TransientPagedKijiSeq(?)")

  private def closeWeakReferences(): Unit = {
    this.synchronized {
      resourcesToClose.foreach { itr: WeakReference[Closeable] =>
        itr.get match {
          case Some(closeable) => closeable.close()
        }
      }
    }
  }

  /** Runs at JVM shutdown and closes open resources. */
  private final class ShutdownHook extends Thread {
    /** {@inheritDoc} */
    override def run {
      closeWeakReferences()
    }
  }
}
