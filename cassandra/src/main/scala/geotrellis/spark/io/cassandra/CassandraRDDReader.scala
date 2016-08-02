package geotrellis.spark.io.cassandra

import geotrellis.spark.io.avro.codecs.KeyValueRecordCodec
import geotrellis.spark.util.KryoWrapper
import geotrellis.spark.{Boundable, KeyBounds, LayerId}
import geotrellis.spark.io.avro.{AvroEncoder, AvroRecordCodec}
import geotrellis.spark.io.index.{IndexRanges, MergeQueue}

import scalaz.concurrent.Task
import scalaz.std.vector._
import scalaz.stream.{Process, nondeterminism}
import com.datastax.driver.core.querybuilder.QueryBuilder
import com.datastax.driver.core.querybuilder.QueryBuilder.{eq => eqs}
import org.apache.avro.Schema
import org.apache.spark.SparkContext
import org.apache.spark.rdd.RDD
import java.util.concurrent.Executors

import scala.collection.JavaConversions._
import scala.reflect.ClassTag

object CassandraRDDReader {
  def read[K: Boundable : AvroRecordCodec : ClassTag, V: AvroRecordCodec : ClassTag](
    instance: CassandraInstance,
    keyspace: String,
    table: String,
    layerId: LayerId,
    queryKeyBounds: Seq[KeyBounds[K]],
    decomposeBounds: KeyBounds[K] => Seq[(Long, Long)],
    filterIndexOnly: Boolean,
    writerSchema: Option[Schema] = None,
    numPartitions: Option[Int] = None
  )(implicit sc: SparkContext): RDD[(K, V)] = {
    if (queryKeyBounds.isEmpty) return sc.emptyRDD[(K, V)]

    val includeKey = (key: K) => queryKeyBounds.includeKey(key)
    val _recordCodec = KeyValueRecordCodec[K, V]
    val kwWriterSchema = KryoWrapper(writerSchema) //Avro Schema is not Serializable

    val ranges = if (queryKeyBounds.length > 1)
        MergeQueue(queryKeyBounds.flatMap(decomposeBounds))
      else
        queryKeyBounds.flatMap(decomposeBounds)

    val bins = IndexRanges.bin(ranges, numPartitions.getOrElse(sc.defaultParallelism))

    val query = QueryBuilder.select("value")
      .from(keyspace, table)
      .where(eqs("key", QueryBuilder.bindMarker()))
      .and(eqs("name", layerId.name))
      .and(eqs("zoom", layerId.zoom))
      .toString

    val rdd: RDD[(K, V)] =
      sc.parallelize(bins, bins.size)
        .mapPartitions { partition: Iterator[Seq[(Long, Long)]] =>
          instance.withSession { session =>
            val statement = session.prepare(query)

            val result = partition map { range =>
              val ranges: Process[Task, Iterator[Long]] = Process.unfold(range.toIterator) { iter =>
                if (iter.hasNext) {
                  val (start, end) = iter.next()
                  Some((start to end).toIterator, iter)
                }
                else None
              }

              val read: Iterator[Long] => Process[Task, Vector[(K, V)]] = {
                case iterator =>
                  Process.unfold(iterator) { iter =>
                    if(iter.hasNext) {
                      val index = iter.next()
                      val row = session.execute(statement.bind(index.asInstanceOf[java.lang.Long]))
                      if (row.nonEmpty) {
                        val bytes = row.one().getBytes("value").array()
                        val recs = AvroEncoder.fromBinary(kwWriterSchema.value.getOrElse(_recordCodec.schema), bytes)(_recordCodec)
                        if (filterIndexOnly) Some(recs, iter)
                        else Some(recs.filter { row => includeKey(row._1) }, iter)
                      } else {
                        Some(Vector.empty, iter)
                      }
                    } else {
                      None
                    }
                  }
              }

              nondeterminism.njoin(maxOpen = 32, maxQueued = 32) { ranges map read }.runFoldMap(identity).unsafePerformSync
            }

            /** Close partition session */
            (result ++ Iterator({
              session.closeAsync(); session.getCluster.closeAsync(); Seq.empty[(K, V)]
            })).flatten
          }
        }

    rdd
  }
}
