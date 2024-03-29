/**
  * Licensed to the Apache Software Foundation (ASF) under one
  * or more contributor license agreements. See the NOTICE file
  * distributed with this work for additional information
  * regarding copyright ownership. The ASF licenses this file
  * to you under the Apache License, Version 2.0 (the
  * "License"); you may not use this file except in compliance
  * with the License. You may obtain a copy of the License at
  *
  * http://www.apache.org/licenses/LICENSE-2.0
  *
  * Unless required by applicable law or agreed to in writing,
  * software distributed under the License is distributed on an
  * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
  * KIND, either express or implied. See the License for the
  * specific language governing permissions and limitations
  * under the License.
  */

package org.apache.mahout.math.algorithms.clustering

import org.apache.mahout.math.algorithms.common.distance.{DistanceMetric, DistanceMetricSelector}
import org.apache.mahout.math._
import org.apache.mahout.math.drm._
import org.apache.mahout.math.drm.RLikeDrmOps._
import org.apache.mahout.math.function.VectorFunction
import org.apache.mahout.math.scalabindings._
import org.apache.mahout.math.scalabindings.RLikeOps._
import org.apache.mahout.math.{Matrix, Vector}

/**
 * CanopyClusteringModel extends ClusteringModel and stores the canopy centers and distance metric information.
 *
 * @param canopies The matrix storing the canopy centers.
 * @param dm The symbol indicating the distance metric used for calculating distances.
 *
 * @constructor Creates a new instance of the CanopyClusteringModel.
 *
 * @property canopyCenters The matrix storing the canopy centers.
 * @property distanceMetric The symbol indicating the distance metric used for calculating distances.
 */
class CanopyClusteringModel(canopies: Matrix, dm: Symbol) extends ClusteringModel {

  val canopyCenters = canopies
  val distanceMetric = dm

 /**
   * Assigns the input data points to their nearest canopy center.
   *
   * @param input The input data points to be assigned to canopies.
   * @return The data points assigned to their nearest canopy centers.
   */
  def cluster[K](input: DrmLike[K]): DrmLike[K] = {

    implicit val ctx = input.context
    implicit val ktag =  input.keyClassTag

    val bcCanopies = drmBroadcast(canopyCenters)
    val bcDM = drmBroadcast(dvec(DistanceMetricSelector.namedMetricLookup(distanceMetric)))

    input.mapBlock(1) {
      case (keys, block: Matrix) => {
        val outputMatrix = new DenseMatrix(block.nrow, 1)

        val localCanopies: Matrix = bcCanopies.value
        for (i <- 0 until block.nrow) {
          val distanceMetric = DistanceMetricSelector.select(bcDM.value.get(0))

          val cluster = (0 until localCanopies.nrow).foldLeft(-1, 9999999999999999.9)((l, r) => {
            val dist = distanceMetric.distance(localCanopies(r, ::), block(i, ::))
            if ((dist) < l._2) {
              (r, dist)
            }
            else {
              l
            }
          })._1
          outputMatrix(i, ::) = dvec(cluster)
        }
        keys -> outputMatrix
      }
    }
  }
}

/**
 * CanopyClustering extends ClusteringFitter and implements the fitting process for the Canopy Clustering algorithm.
 *
 * @constructor Creates a new instance of the CanopyClustering.
 *
 * @property t1 The loose distance used in the canopy clustering algorithm.
 * @property t2 The tight distance used in the canopy clustering algorithm.
 * @property t3 The loose distance used in merging canopy clusters.
 * @property t4 The tight distance used in merging canopy clusters.
 * @property distanceMeasure The symbol indicating the distance metric used for calculating distances.
 */
class CanopyClustering extends ClusteringFitter {

  var t1: Double = _  // loose distance
  var t2: Double = _  // tight distance
  var t3: Double = _
  var t4: Double = _
  var distanceMeasure: Symbol = _

 /**
   * Sets the standard hyperparameters for the Canopy Clustering algorithm.
   *
   * @param hyperparameters The hyperparameters to be set for the algorithm.
   */
  def setStandardHyperparameters(hyperparameters: Map[Symbol, Any] = Map('foo -> None)): Unit = {
    t1 = hyperparameters.asInstanceOf[Map[Symbol, Double]].getOrElse('t1, 0.5)
    t2 = hyperparameters.asInstanceOf[Map[Symbol, Double]].getOrElse('t2, 0.1)
    t3 = hyperparameters.asInstanceOf[Map[Symbol, Double]].getOrElse('t3, t1)
    t4 = hyperparameters.asInstanceOf[Map[Symbol, Double]].getOrElse('t4, t2)

    distanceMeasure = hyperparameters.asInstanceOf[Map[Symbol, Symbol]].getOrElse('distanceMeasure, 'Cosine)

  }

 /**
   * Fits the Canopy Clustering algorithm to the input data.
   *
   * @param input The input data to be fit to the algorithm.
   * @param hyperparameters The hyperparameters for the algorithm.
   * @return The CanopyClusteringModel with the fitted results.
   */
  def fit[K](input: DrmLike[K],
             hyperparameters: (Symbol, Any)*): CanopyClusteringModel = {

    setStandardHyperparameters(hyperparameters.toMap)
    implicit val ctx = input.context
    implicit val ktag =  input.keyClassTag

    val dmNumber = DistanceMetricSelector.namedMetricLookup(distanceMeasure)

    val distanceBC = drmBroadcast(dvec(t1,t2,t3,t4, dmNumber))
    val canopies = input.allreduceBlock(
      {

        // Assign All Points to Clusters
        case (keys, block: Matrix) => {
          val t1_local = distanceBC.value.get(0)
          val t2_local = distanceBC.value.get(1)
          val dm = distanceBC.value.get(4)
          CanopyFn.findCenters(block, DistanceMetricSelector.select(dm), t1_local, t2_local)
        }
      }, {
        // Optionally Merge Clusters that are close enough
        case (oldM: Matrix, newM: Matrix) => {
          val t3_local = distanceBC.value.get(2)
          val t4_local = distanceBC.value.get(3)
          val dm = distanceBC.value.get(4)
          CanopyFn.findCenters(oldM, DistanceMetricSelector.select(dm), t3_local, t4_local)
        }
      })

    val model = new CanopyClusteringModel(canopies, distanceMeasure)
    model.summary = s"""CanopyClusteringModel\n${canopies.nrow} Clusters\n${distanceMeasure} distance metric used for calculating distances\nCanopy centers stored in model.canopies where row n coresponds to canopy n"""
    model
  }


}

/**
 * CanopyFn implements functions used in the Canopy Clustering algorithm.
 */
object CanopyFn extends Serializable {
 
   /**
    * findCenters method takes in a Matrix, a DistanceMetric and t1 and t2 parameters. 
    * It returns a Matrix with the centers found.
    *
    * @param block The input matrix for which centers need to be found
    * @param distanceMeasure The distance metric to be used for calculating the distance between vectors
    * @param t1 The t1 parameter used in the Canopy algorithm
    * @param t2 The t2 parameter used in the Canopy algorithm
    * @return A matrix with the found centers
    */
  def findCenters(block: Matrix, distanceMeasure: DistanceMetric, t1: Double, t2: Double): Matrix = {
    var rowAssignedToCanopy = Array.fill(block.nrow) { false }
    val clusterBuf = scala.collection.mutable.ListBuffer.empty[org.apache.mahout.math.Vector]
    while (rowAssignedToCanopy.contains(false)) {
      val rowIndexOfNextUncanopiedVector = rowAssignedToCanopy.indexOf(false)
      clusterBuf += block(rowIndexOfNextUncanopiedVector, ::).cloned
      block(rowIndexOfNextUncanopiedVector, ::) = svec(Nil, cardinality = block.ncol)
      rowAssignedToCanopy(rowIndexOfNextUncanopiedVector) = true
      for (i <- 0 until block.nrow) {
        if (block(i, ::).getNumNonZeroElements > 0) { //
          distanceMeasure.distance(block(i, ::), clusterBuf.last) match {
            case d if d < t2 => {

              rowAssignedToCanopy(i) = true
              block(i, ::) = svec(Nil, cardinality = block.ncol)
            }
            case d if d < t1 => {

              rowAssignedToCanopy(i) = true
            }
            case d => {}
          }
        }
      }
    }
    dense(clusterBuf)
  }
}
