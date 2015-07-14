package org.apache.spark.sql.execution

import java.util.{ Calendar, Date }
import java.util.concurrent.locks.ReentrantReadWriteLock

import scala.collection.mutable
import scala.language.reflectiveCalls
import scala.reflect.ClassTag

import io.snappydata.util.NumberUtils
import org.apache.spark.sql.AnalysisException
import org.apache.spark.sql.collection.Utils._
import org.apache.spark.sql.collection.{ BoundedSortedSet, SegmentMap }
import org.apache.spark.sql.execution.cms.{ CountMinSketch, TopKCMS }
import org.apache.spark.sql.sources.CastLongTime
import org.apache.spark.sql.types.{ DataType, StructField, StructType }
import org.apache.spark.util.collection.OpenHashSet

final class TopKHokusai[T: ClassTag](cmsParams: CMSParams, val windowSize: Long,
  val epoch0: Long, val topKActual: Int, startIntervalGenerator: Boolean)
  extends Hokusai[T](cmsParams, windowSize, epoch0, startIntervalGenerator) {
  val topKInternal = topKActual * 2

  private val queryTillLastNTopK_Case1: (Array[T]) => () => Array[(T, Approximate)] = (combinedKeys: Array[T]) => {
    () =>
      {
        val topKCMS = this.taPlusIa.ta.aggregates(0).asInstanceOf[TopKCMS[T]]
        if (combinedKeys != null) {
          sortAndBound(TopKCMS.getCombinedTopKFromEstimators(Array(topKCMS),
            combinedKeys))
        } else {
          topKCMS.getTopK
        }
      }
  }

  private val queryTillLastNTopK_Case2: (Int, Array[T]) => Array[(T, Approximate)] =
    (sumUpTo: Int, combinedTopKKeys: Array[T]) => {
      val combinedKeys: Iterable[T] = if (combinedTopKKeys != null) {
        combinedTopKKeys
      } else {
        null
      }
      sortAndBound(getTopKBySummingTimeAggregates(sumUpTo, combinedKeys))
    }

  private val queryTillLastNTopK_Case3: (Int, Int, Int, Int, Array[T]) => Array[(T, Approximate)] = (lastNIntervals: Int,
    totalIntervals: Int, n: Int, nQueried: Int, combinedTopKKeys: Array[T]) =>
    if (lastNIntervals > totalIntervals) {
      val topKKeys: Iterable[T] = if (combinedTopKKeys != null) {
        combinedTopKKeys
      } else {
        null
      }
      sortAndBound(getTopKBySummingTimeAggregates(n, topKKeys))
    } else {
      val nearestPowerOf2NumGE = NumberUtils.nearestPowerOf2GE(lastNIntervals)
      val nearestPowerOf2Num = NumberUtils.nearestPowerOf2LE(lastNIntervals)
      //get all the unioned top k keys.
      val estimators = this.taPlusIa.ta.aggregates.slice(0,
        NumberUtils.isPowerOf2(nearestPowerOf2NumGE) + 1)
      val unionedTopKKeys: Iterable[T] = if (combinedTopKKeys != null) {
        combinedTopKKeys
      } else {
        TopKCMS.getUnionedTopKKeysFromEstimators(estimators)
      }
      //get the top k count till the last but one interval
      val mappings = getTopKBySummingTimeAggregates(NumberUtils.isPowerOf2(nearestPowerOf2Num),
        unionedTopKKeys)

      // the remaining interval will lie in the time interval range
      val lengthOfLastInterval = nearestPowerOf2Num

      val residualIntervals = lastNIntervals - nearestPowerOf2Num
      if (residualIntervals > (lengthOfLastInterval * 3) / 4) {
        //it would be better to find the time aggregation of last interval - the other intervals)
        unionedTopKKeys.foreach { item: T =>
          var total = this.queryTimeAggregateForInterval(item, lengthOfLastInterval)
          var count = this.taPlusIa.basicQuery(lastNIntervals + 1 to (2 * nearestPowerOf2Num).asInstanceOf[Int],
            item, nearestPowerOf2Num.asInstanceOf[Int], nearestPowerOf2Num.asInstanceOf[Int] * 2)
          if (count < total) {
            total = total - count
          } else {
            // what to do? ignore as count is abnormally high
          }
          val prevCount = mappings.getOrElse[Approximate](item,
            Approximate.zeroApproximate(cmsParams.confidence))
          mappings += (item -> (prevCount + count))
        }

      } else {
        unionedTopKKeys.foreach { item: T =>
          val count = this.taPlusIa.basicQuery(nearestPowerOf2Num.asInstanceOf[Int] + 1 to lastNIntervals,
            item, nearestPowerOf2Num.asInstanceOf[Int], nearestPowerOf2Num.asInstanceOf[Int] * 2)
          val prevCount = mappings.getOrElse[Approximate](item,
            Approximate.zeroApproximate(cmsParams.confidence))
          mappings += (item -> (prevCount + count))
        }

      }
      sortAndBound(mappings)
    }

  private val queryTillLastNTopK_Case4: (Int, Int, Int, Int, Array[T]) => Array[(T, Approximate)] =
    (lastNIntervals: Int, totalIntervals: Int, n: Int, nQueried: Int, combinedTopKKeys: Array[T]) =>
      { // the number of intervals so far elapsed is not of form 2 ^n. So the time aggregates are
        // at various stages of overlaps
        //Identify the total range of intervals by identifying the highest 2^n , greater than or equal to
        // the interval

        val lastNIntervalsToQuery = if (lastNIntervals > totalIntervals) {
          totalIntervals
        } else {
          lastNIntervals
        }

        val (bestPath, computedIntervalLength) = this.taPlusIa.intervalTracker.identifyBestPath(lastNIntervalsToQuery,
          true)
        // get all the unified top k keys of all the intervals in the path
        var estimators = bestPath.map { interval => taPlusIa.ta.aggregates(NumberUtils.isPowerOf2(interval) + 1).asInstanceOf[TopKCMS[T]] }
        estimators = this.taPlusIa.ta.aggregates(0).asInstanceOf[TopKCMS[T]] +: estimators
        val unifiedTopKKeys: Iterable[T] = if (combinedTopKKeys != null) {
          combinedTopKKeys
        } else {
          TopKCMS.getUnionedTopKKeysFromEstimators(estimators)
        }

        val skipLastInterval = if (computedIntervalLength > lastNIntervalsToQuery) {
          //this means that last one or many intervals lie within a time interval range
          //drop  the last interval from the count of  best path as it needs to be handled separately
          bestPath.last
        } else {
          -1
        }
        if (skipLastInterval != -1) {
          estimators = estimators.dropRight(1)
        }
        val topKs = TopKCMS.getCombinedTopKFromEstimators(estimators, unifiedTopKKeys)

        if (computedIntervalLength > lastNIntervalsToQuery) {
          // start individual query from interval
          // The accuracy becomes very poor if we query the first interval 1 using entity
          //aggregates . So subtraction approach needs to be looked at more carefully
          val residualLength = lastNIntervalsToQuery - (computedIntervalLength - skipLastInterval)
          if (residualLength > (skipLastInterval * 3) / 4) {
            //it will be better to add the whole time aggregate & substract the other intervals
            unifiedTopKKeys.foreach { item: T =>
              val total = this.queryTimeAggregateForInterval(item, skipLastInterval)
              val prevCount = topKs.getOrElse[Approximate](item,
                Approximate.zeroApproximate(cmsParams.confidence))
              topKs += (item -> (total + prevCount))
            }

            val begin = (lastNIntervals + 1).asInstanceOf[Int]
            val end = computedIntervalLength.asInstanceOf[Int]
            unifiedTopKKeys.foreach { item: T =>
              val total = this.taPlusIa.basicQuery(begin to end,
                item, skipLastInterval.asInstanceOf[Int], computedIntervalLength.asInstanceOf[Int])
              val prevCount = topKs.getOrElse[Approximate](item,
                Approximate.zeroApproximate(cmsParams.confidence))
              if (prevCount > total) {
                topKs += (item -> (prevCount - total))
              } else {
                ///ignore the values as they are abnormal. what to do?....
              }
            }

          } else {
            val begin = (computedIntervalLength - skipLastInterval + 1).asInstanceOf[Int]
            unifiedTopKKeys.foreach { item: T =>
              val total = this.taPlusIa.basicQuery(begin to lastNIntervalsToQuery,
                item, skipLastInterval.asInstanceOf[Int], computedIntervalLength.asInstanceOf[Int])
              val prevCount = topKs.getOrElse[Approximate](item, Approximate.zeroApproximate(cmsParams.confidence))
              topKs += (item -> (total + prevCount))
            }
          }
        }

        sortAndBound(topKs)

      }

  private val combinedKeysTillLastNTopK_Case1: () => Array[T] = () => {
    val topKCMS = this.taPlusIa.ta.aggregates(0).asInstanceOf[TopKCMS[T]]
    TopKCMS.getUnionedTopKKeysFromEstimators(Array(topKCMS)).toArray
  }

  private val combinedKeysTillLastNTopK_Case2: (Int) => Array[T] = (sumUpTo: Int) =>
    this.getTopKKeysBySummingTimeAggregates(sumUpTo).toArray

  private val combinedKeysTillLastNTopK_Case3: (Int, Int, Int, Int) => Array[T] = (lastNIntervals: Int,
    totalIntervals: Int, n: Int, nQueried: Int) =>
    if (lastNIntervals > totalIntervals) {
      this.getTopKKeysBySummingTimeAggregates(n).toArray

    } else {
      val nearestPowerOf2NumGE = NumberUtils.nearestPowerOf2GE(lastNIntervals)
      val nearestPowerOf2Num = NumberUtils.nearestPowerOf2LE(lastNIntervals)
      //get all the unioned top k keys.
      val estimators = this.taPlusIa.ta.aggregates.slice(0,
        NumberUtils.isPowerOf2(nearestPowerOf2NumGE) + 1)
      TopKCMS.getUnionedTopKKeysFromEstimators(estimators).toArray
    }

  private val combinedKeysTillLastNTopK_Case4: (Int, Int, Int, Int) => Array[T] =
    (lastNIntervals: Int, totalIntervals: Int, n: Int, nQueried: Int) =>
      { // the number of intervals so far elapsed is not of form 2 ^n. So the time aggregates are
        // at various stages of overlaps
        //Identify the total range of intervals by identifying the highest 2^n , greater than or equal to
        // the interval

        val lastNIntervalsToQuery = if (lastNIntervals > totalIntervals) {
          totalIntervals
        } else {
          lastNIntervals
        }

        val (bestPath, computedIntervalLength) = this.taPlusIa.intervalTracker.identifyBestPath(lastNIntervalsToQuery,
          true)
        // get all the unified top k keys of all the intervals in the path
        var estimators = bestPath.map { interval => taPlusIa.ta.aggregates(NumberUtils.isPowerOf2(interval) + 1).asInstanceOf[TopKCMS[T]] }
        estimators = this.taPlusIa.ta.aggregates(0).asInstanceOf[TopKCMS[T]] +: estimators
        TopKCMS.getUnionedTopKKeysFromEstimators(estimators).toArray

      }

  override val mergeCreator: ((Array[CountMinSketch[T]]) => CountMinSketch[T]) = estimators =>
    TopKCMS.merge[T](estimators(1).asInstanceOf[TopKCMS[T]].topKInternal * 2, estimators)

  def getTopKTillTime(epoch: Long, combinedKeys: Array[T] = null): Option[Array[(T, Approximate)]] = {
    this.rwlock.executeInReadLock(
      this.timeEpoch.timestampToInterval(epoch).flatMap(x => {
        val interval = if (x > timeEpoch.t) {
          timeEpoch.t
        } else {
          x
        }
        Some(this.taPlusIa.queryLastNIntervals[Array[(T, Approximate)]](
          this.taPlusIa.convertIntervalBySwappingEnds(interval.asInstanceOf[Int]).asInstanceOf[Int],
          queryTillLastNTopK_Case1(combinedKeys), queryTillLastNTopK_Case2(_, combinedKeys),
          queryTillLastNTopK_Case3(_, _, _, _, combinedKeys),
          queryTillLastNTopK_Case4(_, _, _, _, combinedKeys)))

      }), true)

  }

  def getTopKForCurrentInterval: Option[Array[(T, Approximate)]] =
    this.rwlock.executeInReadLock({
      Some(this.mBar.asInstanceOf[TopKCMS[T]].getTopK)
    }, true)

  def getTopKKeysForCurrentInterval: OpenHashSet[T] =
    this.rwlock.executeInReadLock({
      this.mBar.asInstanceOf[TopKCMS[T]].getTopKKeys
    }, true)

  def getForKeysInCurrentInterval(combinedKeys: Array[T]): Array[(T, Approximate)] =
    this.rwlock.executeInReadLock({
      val cms = this.mBar
      combinedKeys.map { k => (k, cms.estimateCountAsApproximate(k)) }
    }, true)

  def getCombinedTopKKeysBetweenTime(epochFrom: Long,
    epochTo: Long): Option[mutable.Set[T]] = {
    this.rwlock.executeInReadLock(
      {
        val (later, earlier) = convertEpochToIntervals(epochFrom, epochTo) match {
          case Some(x) => x
          case None => return None
        }
        Some(this.getCombinedTopKKeysBetween(later, earlier))
      }, true)
  }

  def getCombinedTopKKeysTillTime(epoch: Long): Option[Array[T]] = {
    this.rwlock.executeInReadLock(
      this.timeEpoch.timestampToInterval(epoch).flatMap(x => {
        val interval = if (x > timeEpoch.t) {
          timeEpoch.t
        } else {
          x
        }
        Some(this.taPlusIa.queryLastNIntervals[Array[T]](
          this.taPlusIa.convertIntervalBySwappingEnds(interval.asInstanceOf[Int]).asInstanceOf[Int],
          combinedKeysTillLastNTopK_Case1, combinedKeysTillLastNTopK_Case2(_),
          combinedKeysTillLastNTopK_Case3(_, _, _, _),
          combinedKeysTillLastNTopK_Case4(_, _, _, _)))

      }), true)

  }

  def getTopKBetweenTime(epochFrom: Long, epochTo: Long,
    combinedTopKKeys: Array[T] = null): Option[Array[(T, Approximate)]] =
    this.rwlock.executeInReadLock({
      val (later, earlier) = convertEpochToIntervals(epochFrom, epochTo) match {
        case Some(x) if x._1 > taPlusIa.ia.aggregates.size => (taPlusIa.ia.aggregates.size, x._2)
        case Some(x) => x
        case None => return None
      }
      Some(this.getTopKBetweenTime(later, earlier, combinedTopKKeys))
    }, true)

  def getTopKKeysBetweenTime(epochFrom: Long,
    epochTo: Long): Option[OpenHashSet[T]] =
    this.rwlock.executeInReadLock({
      val (later, earlier) = convertEpochToIntervals(epochFrom, epochTo) match {
        case Some(x) => x
        case None => return None
      }
      val topKKeys = this.getCombinedTopKKeysBetween(later, earlier)
      val result = new OpenHashSet[T](topKKeys.size)
      topKKeys.foreach(result.add)
      Some(result)
    }, true)

  def getTopKBetweenTime(later: Int, earlier: Int, combinedTopKKeys: Array[T]): Array[(T, Approximate)] = {
    val fromLastNInterval = this.taPlusIa.convertIntervalBySwappingEnds(later)
    val tillLastNInterval = this.taPlusIa.convertIntervalBySwappingEnds(earlier)
    if (fromLastNInterval == 1 && tillLastNInterval == 1) {
      queryTillLastNTopK_Case1(combinedTopKKeys)()
    } else {
      // Identify the best path
      val (bestPath, computedIntervalLength) = this.taPlusIa.intervalTracker.identifyBestPath(tillLastNInterval.asInstanceOf[Int],
        true, 1, fromLastNInterval.asInstanceOf[Int])
      var estimators = bestPath.map { interval => taPlusIa.ta.aggregates(NumberUtils.isPowerOf2(interval) + 1).asInstanceOf[TopKCMS[T]] }
      if (fromLastNInterval == 1) {
        estimators = this.taPlusIa.ta.aggregates(0).asInstanceOf[TopKCMS[T]] +: estimators
      }
      val unifiedTopKKeys: Iterable[T] = if (combinedTopKKeys != null) {
        combinedTopKKeys
      } else {
        TopKCMS.getUnionedTopKKeysFromEstimators(estimators)
      }

      var truncatedSeq = bestPath
      var start = if (fromLastNInterval == 1) {
        fromLastNInterval + 1
      } else {
        fromLastNInterval
      }
      val topKs = mutable.HashMap[T, Approximate]()
      var taIntervalStartsAt = computedIntervalLength - bestPath.aggregate[Long](0)(_ + _, _ + _) + 1

      bestPath.foreach { interval =>
        val lengthToDrop = truncatedSeq.head
        truncatedSeq = truncatedSeq.drop(1)
        val lengthTillInterval = computedIntervalLength - truncatedSeq.aggregate[Long](0)(_ + _, _ + _)
        val end = if (lengthTillInterval > tillLastNInterval) {
          tillLastNInterval
        } else {
          lengthTillInterval
        }

        if (start == taIntervalStartsAt && end == lengthTillInterval) {
          // can add the time series aggregation as whole interval is needed
          val topKCMS = this.taPlusIa.ta.aggregates(NumberUtils.isPowerOf2(interval) + 1).asInstanceOf[TopKCMS[T]]
          TopKCMS.getCombinedTopKFromEstimators(Array(topKCMS), unifiedTopKKeys, topKs)
        } else {
          unifiedTopKKeys.foreach { item: T =>
            val total = this.taPlusIa.basicQuery(start.asInstanceOf[Int] to end.asInstanceOf[Int],
              item, interval.asInstanceOf[Int], lengthTillInterval.asInstanceOf[Int])
            val prevCount = topKs.getOrElse[Approximate](item,
              Approximate.zeroApproximate(cmsParams.confidence))
            topKs += (item -> (total + prevCount))
          }
        }
        start = lengthTillInterval + 1
        taIntervalStartsAt += lengthToDrop

      }

      if (fromLastNInterval == 1) {
        TopKCMS.getCombinedTopKFromEstimators(
          Array(this.taPlusIa.ta.aggregates(0).asInstanceOf[TopKCMS[T]]), unifiedTopKKeys, topKs)

      }
      sortAndBound(topKs)
    }
  }

  def getCombinedTopKKeysBetween(later: Int, earlier: Int): mutable.Set[T] = {
    val fromLastNInterval = this.taPlusIa.convertIntervalBySwappingEnds(later)
    val tillLastNInterval = this.taPlusIa.convertIntervalBySwappingEnds(earlier)
    if (fromLastNInterval == 1 && tillLastNInterval == 1) {
      val topKCMS = this.taPlusIa.ta.aggregates(0).asInstanceOf[TopKCMS[T]]
      TopKCMS.getUnionedTopKKeysFromEstimators(Array(topKCMS))
    } else {
      // Identify the best path
      val (bestPath, computedIntervalLength) = this.taPlusIa.intervalTracker.identifyBestPath(tillLastNInterval.asInstanceOf[Int],
        true, 1, fromLastNInterval.asInstanceOf[Int])
      var estimators = bestPath.map { interval => taPlusIa.ta.aggregates(NumberUtils.isPowerOf2(interval) + 1).asInstanceOf[TopKCMS[T]] }
      if (fromLastNInterval == 1) {
        estimators = this.taPlusIa.ta.aggregates(0).asInstanceOf[TopKCMS[T]] +: estimators
      }
      TopKCMS.getUnionedTopKKeysFromEstimators(estimators)
    }
  }

  def queryTimeAggregateForInterval(item: T, interval: Long): Approximate = {
    assert(NumberUtils.isPowerOf2(interval) != -1)
    val topKCMS = this.taPlusIa.ta.aggregates(NumberUtils.isPowerOf2(interval) + 1).asInstanceOf[TopKCMS[T]]
    topKCMS.getFromTopKMap(item).getOrElse(this.taPlusIa.queryTimeAggregateForInterval(item, interval))
  }

  private def getTopKBySummingTimeAggregates(sumUpTo: Int,
    setOfTopKKeys: Iterable[T] = null): mutable.Map[T, Approximate] = {

    val estimators = this.taPlusIa.ta.aggregates.slice(0, sumUpTo + 1)

    val topKs = TopKCMS.getCombinedTopKFromEstimators(estimators,

      if (setOfTopKKeys != null) {
        setOfTopKKeys
      } else {
        TopKCMS.getUnionedTopKKeysFromEstimators(estimators)
      })
    topKs
  }

  private def getTopKKeysBySummingTimeAggregates(sumUpTo: Int): scala.collection.Set[T] = {
    val estimators = this.taPlusIa.ta.aggregates.slice(0, sumUpTo + 1)
    TopKCMS.getUnionedTopKKeysFromEstimators(estimators)
  }

  private def sortAndBound[T](topKs: mutable.Map[T, Approximate]): Array[(T, Approximate)] = {
    val sortedData = new BoundedSortedSet[T, Approximate](this.topKActual, false)
    topKs.foreach(sortedData.add(_))
    val iter = sortedData.iterator
    //topKs.foreach(sortedData.add(_))
    Array.fill[(T, Approximate)](sortedData.size())({
      //val (key, value) = iter.next
      //(key, value.longValue())
      iter.next

    })

  }

  override def createZeroCMS(powerOf2: Int): CountMinSketch[T] =
    if (powerOf2 == 0) {
      //TODO: fix this
      val x = if (this.topKInternal == 0) {
        2 * this.topKActual
      } else {
        this.topKInternal
      }
      TopKHokusai.newZeroCMS[T](cmsParams.depth, cmsParams.width, cmsParams.hashA, topKActual, x,
          cmsParams.confidence, cmsParams.eps)
    } else {
      TopKHokusai.newZeroCMS[T](cmsParams.depth, cmsParams.width, cmsParams.hashA, topKActual,
        topKInternal * (powerOf2 + 1), cmsParams.confidence, cmsParams.eps)
    }

}

object TopKHokusai {
  // TODO: Resolve the type of TopKHokusai
  private final val topKMap = new mutable.HashMap[String, TopKHokusai[_]]
  private final val mapLock = new ReentrantReadWriteLock

  def newZeroCMS[T: ClassTag](depth: Int, width: Int, hashA: Array[Long], topKActual: Int, 
      topKInternal: Int, confidence: Double, eps: Double) =
    new TopKCMS[T](topKActual, topKInternal, depth, width, hashA, confidence, eps)

  def apply[T](name: String): Option[TopKHokusai[T]] = {
    SegmentMap.lock(mapLock.readLock) {
      topKMap.get(name) match {
        case Some(tk) => Some(tk.asInstanceOf[TopKHokusai[T]])
        case None => None
      }
    }
  }

  def apply[T: ClassTag](name: String, cms: CMSParams, size: Int,
    tsCol: Int, timeInterval: Long, epoch0: () => Long) = {
    lookupOrAdd[T](name, cms, size, tsCol, timeInterval, epoch0)
  }

  private[sql] def lookupOrAdd[T: ClassTag](name: String, cms : CMSParams,
    size: Int, tsCol: Int, timeInterval: Long,
    epoch0: () => Long): TopKHokusai[T] = {
    SegmentMap.lock(mapLock.readLock) {
      topKMap.get(name)
    } match {
      case Some(topk) => topk.asInstanceOf[TopKHokusai[T]]
      case None =>
        // insert into global map but double-check after write lock
        SegmentMap.lock(mapLock.writeLock) {
          topKMap.getOrElse(name, {
            val topk = new TopKHokusai[T](cms, timeInterval,
              epoch0(), size, timeInterval > 0 && tsCol < 0)
            topKMap(name) = topk
            topk
          }).asInstanceOf[TopKHokusai[T]]
        }
    }
  }
}

protected[sql] final class TopKHokusaiWrapper(val name: String, val cms : CMSParams,
val size: Int, val timeSeriesColumn: Int,
  val timeInterval: Long, val schema: StructType, val key: StructField,
  val frequencyCol: Option[StructField], val epoch: Long)
  extends CastLongTime with Serializable {

  override protected def getNullMillis(getDefaultForNull: Boolean) =
    if (getDefaultForNull) System.currentTimeMillis() else -1L

  override def timeColumnType: Option[DataType] = {
    if (timeSeriesColumn >= 0) {
      Some(schema(timeSeriesColumn).dataType)
    } else {
      None
    }
  }

  override def module: String = "TopKHokusai"
}

object TopKHokusaiWrapper {

  def apply(name: String, options: Map[String, Any],
    schema: StructType): TopKHokusaiWrapper = {
    val keyTest = "key".ci
    val timeSeriesColumnTest = "timeSeriesColumn".ci
    val timeIntervalTest = "timeInterval".ci
    val depthTest = "depth".ci
    val widthTest = "width".ci
    val sizeTest = "size".ci
    val frequencyColTest = "frequencyCol".ci
    val epochTest = "epoch".ci
    val epsTest = "eps".ci
    val confidenceTest = "confidence".ci
    val cols = schema.fieldNames
    var epsAndcf = false;
    var widthAndDepth = false;
    // Using foldLeft to read key-value pairs and build into the result
    // tuple of (key, depth, width, size, frequencyCol) like an aggregate.
    // This "aggregate" simply keeps the last values for the corresponding
    // keys as found when folding the map.
    val (key, tsCol, timeInterval, depth, width, size, frequencyCol, epoch, eps, confidence) =
      options.foldLeft("", -1, 5L, 5, 200, 100, "", -1L, 0.01, 0.95) {
        case ((k, ts, ti, cf, e, s, fr, ep, eps, cfi), (opt, optV)) =>
          opt match {
            case keyTest() => (optV.toString, ts, ti, cf, e, s, fr, ep, eps, cfi)
            case depthTest() =>
              widthAndDepth = true
              optV match {
              case fs: String => (k, ts, ti, fs.toInt, e, s, fr, ep, eps, cfi)
              case fi: Int => (k, ts, ti, fi, e, s, fr, ep, eps, cfi)
              case _ => throw new AnalysisException(
                s"TopKCMS: Cannot parse int 'depth'=$optV")
            }
            case widthTest() =>
              widthAndDepth = true
              optV match {
              case fs: String => (k, ts, ti, cf, fs.toInt, s, fr, ep, eps, cfi)
              case fi: Int => (k, ts, ti, cf, fi, s, fr, ep, eps, cfi)
              case _ => throw new AnalysisException(
                s"TopKCMS: Cannot parse int 'width'=$optV")
            }
            case timeSeriesColumnTest() => optV match {
              case tss: String => (k, columnIndex(tss, cols), ti, cf, e, s, fr, ep, eps, cfi)
              case tsi: Int => (k, tsi, ti, cf, e, s, fr, ep, eps, cfi)
              case _ => throw new AnalysisException(
                s"TopKCMS: Cannot parse 'timeSeriesColumn'=$optV")
            }
            case timeIntervalTest() =>
              (k, ts, parseTimeInterval(optV, "TopKCMS"), cf, e, s, fr, ep, eps, cfi)
            case sizeTest() => optV match {
              case si: Int => (k, ts, ti, cf, e, si, fr, ep, eps, cfi)
              case ss: String => (k, ts, ti, cf, e, ss.toInt, fr, ep, eps, cfi)
              case sl: Long => (k, ts, ti, cf, e, sl.toInt, fr, ep, eps, cfi)
              case _ => throw new AnalysisException(
                s"TopKCMS: Cannot parse int 'size'=$optV")
            }
            case epochTest() => optV match {
              case si: Int => (k, ts, ti, cf, e, s, fr, si.toLong, eps, cfi)
              case ss: String =>
                try {
                  (k, ts, ti, cf, e, s, fr, ss.toLong, eps, cfi)
                } catch {
                  case nfe: NumberFormatException =>
                    try {
                      (k, ts, ti, cf, e, s, fr, CastLongTime.getMillis(
                        java.sql.Timestamp.valueOf(ss)), eps, cfi)
                    } catch {
                      case iae: IllegalArgumentException =>
                        throw new AnalysisException(
                          s"TopKCMS: Cannot parse timestamp 'epoch'=$optV")
                    }
                }
              case sl: Long => (k, ts, ti, cf, e, s, fr, sl, eps, cfi)
              case dt: Date => (k, ts, ti, cf, e, s, fr, dt.getTime, eps, cfi)
              case cal: Calendar => (k, ts, ti, cf, e, s, fr, cal.getTimeInMillis, eps, cfi)
              case _ => throw new AnalysisException(
                s"TopKCMS: Cannot parse int 'size'=$optV")
            }
            case frequencyColTest() => (k, ts, ti, cf, e, s, optV.toString, ep, eps, cfi)
            case epsTest() =>
              epsAndcf = true
              optV match {
              case es: String => (k, ts, ti, cf, e, s, fr, ep, es.toDouble, cfi)
              case ed: Double => (k, ts, ti, cf, e, s, fr, ep, ed, cfi)
              case _ => throw new AnalysisException(
                s"TopKCMS: Cannot parse double 'eps'=$optV")
            }
            case confidenceTest() =>
              epsAndcf = true
              optV match {
              case cfs: String => (k, ts, ti, cf, e, s, fr, ep, eps, cfs.toDouble)
              case cfd: Double => (k, ts, ti, cf, e, s, fr, ep, eps, cfd)
              case _ => throw new AnalysisException(
                s"TopKCMS: Cannot parse double 'confidence'=$optV")
            }
          }
      }
    if (epsAndcf && widthAndDepth)
      throw new AnalysisException("TopK parameters should specify either (eps, confidence) or (width, depth) and not both.")

    val cms = if (epsAndcf) CMSParams(eps, confidence) else CMSParams(width, depth)
    new TopKHokusaiWrapper(name, cms, size, tsCol, timeInterval,
      schema, schema(key),
      if (frequencyCol.isEmpty) None else Some(schema(frequencyCol)), epoch)
  }
}
