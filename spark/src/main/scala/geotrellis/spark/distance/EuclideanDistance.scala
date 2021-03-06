package geotrellis.spark.distance

import com.vividsolutions.jts.geom.Coordinate
import org.apache.spark.rdd.RDD

import geotrellis.raster._
import geotrellis.raster.distance._
import geotrellis.spark._
import geotrellis.spark.buffer.Direction
import geotrellis.spark.buffer.Direction._
import geotrellis.spark.tiling._
import geotrellis.vector._
import geotrellis.vector.triangulation._
import geotrellis.vector.voronoi._

import scala.collection.mutable.{ListBuffer, Set}

object EuclideanDistance {

  private[spark] def voronoiCells(centerStitched: StitchedDelaunay, initialEdge: Int, extent: Extent): Seq[(Polygon, Coordinate)] = {
    import centerStitched.halfEdgeTable._

    val queue = ListBuffer[(Int, Int)]((initialEdge, getDest(initialEdge)))
    val visited = Set.empty[Int]
    val result = ListBuffer.empty[(Polygon, Coordinate)]

    while (queue nonEmpty) {
      val (incoming, here) = queue.remove(0)

      if (!visited.contains(here)) {
        visited += here

        val poly = VoronoiDiagram.polygonalCell(centerStitched.halfEdgeTable, centerStitched.indexToCoord, extent)(incoming)

        if (poly isDefined) {
          result += ((poly.get, centerStitched.indexToCoord(here)))
          var e = getFlip(incoming)
          do {
            queue += ((e, getDest(e)))
            e = rotCWSrc(e)
          } while (e != getFlip(incoming))
        }
      }
    }

    result
  }

  private[spark] def neighborEuclideanDistance(center: DelaunayTriangulation, neighbors: Map[Direction, (BoundaryDelaunay, Extent)], re: RasterExtent): Option[Tile] = {
    val _neighbors = neighbors.map { case (dir, value) => (convertDirection(dir), value) }
    val stitched = StitchedDelaunay(center, _neighbors, false)

    def findBaseEdge(): Int = {
      import stitched.halfEdgeTable._

      var e = 0
      var bestdist = 1.0/0.0
      var best = -1
      do {
        while (getDest(e) == -1 && e < maxEdgeIndex)
          e += 1
        val dist = re.extent.distance(Point.jtsCoord2Point(stitched.indexToCoord(getDest(e))))
        if (dist < bestdist) {
          best = e
          bestdist = dist
        }
        e += 1
      } while (bestdist > 0 && e < maxEdgeIndex)

      best
    }

    if (stitched.pointSet.length == 0) {
      None
    } else {
      val baseEdge = 
        if (center.boundary != -1) {
          // center had edges
          stitched.halfEdgeTable.edgeIncidentTo(center.halfEdgeTable.getDest(center.boundary)) 
        } else {
          // center either has 1 or no points
          findBaseEdge()
        }

      val extent = re.extent
      val cells = voronoiCells(stitched, baseEdge, extent)

      val tile = DoubleArrayTile.empty(re.cols, re.rows)
      cells.foreach(EuclideanDistanceTile.rasterizeDistanceCell(re, tile))

      Some(tile)
    }
  }

  def apply(rdd: RDD[(SpatialKey, Array[Coordinate])], layoutDefinition: LayoutDefinition): RDD[(SpatialKey, Tile)] = {
    val triangulations: RDD[(SpatialKey, DelaunayTriangulation)] =
      rdd
        .map { case (key, points) =>
          (key, DelaunayTriangulation(points))
        }

    val borders: RDD[(SpatialKey, BoundaryDelaunay)] =
      triangulations
        .mapPartitions({ iter =>
          iter.map{ case (sk, dt) => {
            val ex: Extent = layoutDefinition.mapTransform(sk)
            (sk, BoundaryDelaunay(dt, ex))
          }}
        }, preservesPartitioning = true)

    borders
      .collectNeighbors
      .mapPartitions({ partition =>
        partition.map { case (key, neighbors) =>
          val newNeighbors =
            neighbors.map { case (direction, (key2, border)) =>
              val ex = layoutDefinition.mapTransform(key2)
              (direction, (border, ex))
            }
          (key, newNeighbors.toMap)
        }
      }, preservesPartitioning = true)
      .join(triangulations)
      .mapPartitions({ partition =>
        partition.flatMap { case (key, (borders, triangulation)) => { // : (Map[Direction, (BoundaryDelaunay, Extent)], DelaunayTriangulation)
          val extent = layoutDefinition.mapTransform(key)
          val re =
            RasterExtent(
              extent,
              layoutDefinition.tileCols,
              layoutDefinition.tileRows
            )

          neighborEuclideanDistance(triangulation, borders, re) match {
            case None => None
            case Some(tile) => Some(key, tile)
          }
        }}
      }, preservesPartitioning = true)

  }

}
