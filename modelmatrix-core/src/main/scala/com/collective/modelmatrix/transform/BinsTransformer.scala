package com.collective.modelmatrix.transform

import com.collective.modelmatrix.BinColumn.BinValue
import com.collective.modelmatrix.transform.TransformSchemaError.{ExtractColumnNotFound, UnsupportedTransformDataType}
import com.collective.modelmatrix.{BinColumn, ModelFeature}
import com.typesafe.config.ConfigFactory
import org.apache.spark.sql.DataFrame
import org.apache.spark.sql.types._
import org.slf4j.LoggerFactory

import scalaz.\/
import scalaz.syntax.either._

class BinsTransformer(input: DataFrame) extends Transformer(input) with Binner {

  private val log = LoggerFactory.getLogger(classOf[BinsTransformer])

  private val config = ConfigFactory.load()

  private val sampleSize = config.getLong("modelmatrix.transform.bins.sample-size")

  private val supportedDataTypes = Seq(ShortType, IntegerType, LongType, DoubleType)

  protected case class Scan(columnId: Int = 0, columns: Seq[BinValue] = Seq.empty)

  def validate: PartialFunction[ModelFeature, TransformSchemaError \/ TypedModelFeature] = {
    case f@ModelFeature(_, _, _, e, Bins(_, _, _)) if inputDataType(e).isEmpty =>
      ExtractColumnNotFound(e).left

    case f@ModelFeature(_, _, _, e, Bins(_, _, _))
      if inputDataType(e).isDefined && supportedDataTypes.contains(inputDataType(e).get) =>
      TypedModelFeature(f, inputDataType(e).get).right

    case f@ModelFeature(_, _, _, e, b@Bins(_, _, _)) =>
      UnsupportedTransformDataType(e, inputDataType(e).get, b).left
  }

  def transform(feature: TypedModelFeature): Seq[BinColumn] = {
    require(feature.feature.transform.isInstanceOf[Bins],
      s"Illegal transform type: ${feature.feature.transform}")

    val ModelFeature(_, _, _, e, Bins(nbins, minPoints, minPct)) = feature.feature

    log.info(s"Calculate bins transformation for feature: ${feature.feature.feature}. " +
      s"Bins: $nbins. " +
      s"Min points: $minPoints. " +
      s"Min percentage: $minPct. " +
      s"Extract type: ${feature.extractType}")

    val inputSize = input.count()
    val fraction = if (sampleSize >= inputSize) 1.0D else sampleSize / inputSize
    val sample = input.select(e).sample(withReplacement = false, fraction)

    // Collect sample values
    val x = sample.collect().map {
      case row if feature.extractType == ShortType => row.getShort(0).toDouble
      case row if feature.extractType == IntegerType => row.getInt(0).toDouble
      case row if feature.extractType == LongType => row.getLong(0).toDouble
      case row if feature.extractType == DoubleType => row.getDouble(0)
    }

    log.debug(s"Collected sample size of: ${x.length}")

    val bins = optimalSplit(x, nbins, minPoints, minPct)
    log.debug(s"Calculated optimal bin split: ${bins.size}")

    assert(bins.size >= 2, s"Got less than 2 bins")

    val scan = bins.foldLeft(Scan()) {
      case (state@Scan(columnId, cols), bin) =>
        val column = BinColumn.BinValue(columnId + 1, bin.low, bin.high, bin.count, x.length)
        Scan(column.columnId, cols :+ column)
    }

    val columns = scan.columns

    // Update first and last bins to catch out-of-sample values
    BinColumn.toLowerBin(columns.head) +: columns.drop(1).dropRight(1) :+ BinColumn.toUpperBin(columns.last)
  }
}
