package com.collective.modelmatrix.cli.instance

import java.time.Instant

import com.collective.modelmatrix.catalog.{ModelDefinitionFeature, ModelMatrixCatalog}
import com.collective.modelmatrix.cli.{CliModelCatalog, CliSparkContext, Script, Source, _}
import com.collective.modelmatrix.transform._
import com.collective.modelmatrix.{ModelMatrix, BinColumn, CategorialColumn, ModelFeature}
import com.typesafe.config.Config
import org.apache.spark.sql.hive.HiveContext
import org.apache.spark.sql.types.DataType
import org.slf4j.LoggerFactory
import slick.dbio.DBIO

import scala.concurrent.ExecutionContext
import scalaz.concurrent.Task
import scalaz.stream._
import scalaz.{-\/, @@, Tag}


case class AddInstance(
  modelDefinitionId: Int,
  source: Source,
  name: Option[String],
  comment: Option[String],
  concurrencyLevel: Int,
  dbName: String,
  dbConfig: Config
)(implicit val ec: ExecutionContext @@ ModelMatrixCatalog)
  extends Script with CliModelCatalog with CliSparkContext with Transformers {

  private val log = LoggerFactory.getLogger(classOf[AddInstance])

  private implicit val unwrap = Tag.unwrap(ec)

  private implicit lazy val sqlContext = ModelMatrix.hiveContext(sc)

  import com.collective.modelmatrix.cli.ASCIITableFormat._
  import com.collective.modelmatrix.cli.ASCIITableFormats._

  // Add instance feature commands
  sealed trait AddInstanceFeatureCommand

  case class AddIdentityFeature(
    extractType: DataType
  ) extends AddInstanceFeatureCommand

  case class AddTopFeature(
    extractType: DataType,
    columns: Seq[CategorialColumn]
  ) extends AddInstanceFeatureCommand

  case class AddIndexFeature(
    extractType: DataType,
    columns: Seq[CategorialColumn]
  ) extends AddInstanceFeatureCommand

  case class AddBinsFeature(
    extractType: DataType,
    columns: Seq[BinColumn]
  ) extends AddInstanceFeatureCommand

  def run(): Unit = {

    log.info(s"Add Model Matrix instance. " +
      s"Model definition id: $modelDefinitionId. " +
      s"Source: $source. " +
      s"Name: $name. " +
      s"Comment: $comment. " +
      s"Concurrency: $concurrencyLevel. " +
      s"Database: $dbName @ ${dbConfig.origin()}")

    val features = blockOn(db.run(modelDefinitionFeatures.features(modelDefinitionId))).filter(_.feature.active == true)
    require(features.nonEmpty, s"No active features are defined for model definition: $modelDefinitionId. " +
      s"Ensure that this model definition exists")

    // Cache feature columns
    val input = Transformer.selectFeatures(source.asDataFrame, features.map(_.feature))
    val transformers = new Transformers(input)

    // Validate each feature
    val validate = features.filter(_.feature.active).map { case mdf@ModelDefinitionFeature(_, _, feature) =>
      mdf -> transformers.validate(feature)
    }

    // Check that input schema match with model definition
    val invalidFeatures = validate.collect { case (mdf, -\/(error)) => mdf -> error }
    if (invalidFeatures.nonEmpty) {
      Console.err.println(s"Can't create model instance for definition id: $modelDefinitionId from input: $source")
      Console.err.println(s"Input schema errors:")
      invalidFeatures.printASCIITable()
      return
    }

    // Get transformation for each feature
    val typedFeatures: Process[Task, (ModelDefinitionFeature, TypedModelFeature)] =
      Process.emitAll(validate.map { case (mdf, e) => (mdf, e.toOption.get) })

    def onError(err: Throwable) = {
      Console.err.println(s"Can't compute input data transformation. Error: $err")
      err.printStackTrace(Console.err)
      throw err
    }

    // Run with given concurrency level
    val transformed = typedFeatures
      .concurrently(concurrencyLevel)(transformerChannel(transformers))
      .runLog.attemptRun.fold(onError, identity)

    // Create model instance

    val addModelInstance = modelInstances.add(
      name = name,
      modelDefinitionId = modelDefinitionId,
      createdBy = System.getProperty("user.name"),
      createdAt = Instant.now(),
      comment = comment
    )

    var columnId: Int = 0

    val insert = for {
      modelInstanceId <- addModelInstance
      featureId <- DBIO.sequence(transformed.map {
        case (ModelDefinitionFeature(featureDefinitionId, _, _), AddIdentityFeature(extractType)) =>
          columnId += 1
          modelInstanceFeatures.addIdentityFeature(modelInstanceId, featureDefinitionId, extractType, columnId)

        case (ModelDefinitionFeature(featureDefinitionId, _, _), AddTopFeature(extractType, columns)) =>
          val baseColumnId = columnId
          columnId += columns.size
          val rebased = columns.map(_.rebaseColumnId(baseColumnId))
          modelInstanceFeatures.addTopFeature(modelInstanceId, featureDefinitionId, extractType, rebased)

        case (ModelDefinitionFeature(featureDefinitionId, _, _), AddIndexFeature(extractType, columns)) =>
          val baseColumnId = columnId
          columnId += columns.size
          val rebased = columns.map(_.rebaseColumnId(baseColumnId))
          modelInstanceFeatures.addIndexFeature(modelInstanceId, featureDefinitionId, extractType, rebased)

        case (ModelDefinitionFeature(featureDefinitionId, _, _), AddBinsFeature(extractType, columns)) =>
          val baseColumnId = columnId
          columnId += columns.size
          val rebased = columns.map(_.rebaseColumnId(baseColumnId))
          modelInstanceFeatures.addBinsFeature(modelInstanceId, featureDefinitionId, extractType, rebased)
      })
    } yield (modelInstanceId, featureId)

    import driver.api._
    val (modelInstanceId, featuresId) = blockOn(db.run(insert.transactionally))

    Console.out.println(s"Successfully created new model instance")
    Console.out.println(s"Matrix Model instance id: $modelInstanceId")
  }

  private type In = (ModelDefinitionFeature, TypedModelFeature)
  private type Out = (ModelDefinitionFeature, AddInstanceFeatureCommand)

  private def transformerChannel(transformers: Transformers): Channel[Task, In, Out] = channel.lift[Task, In, Out] {
    case (mdf, TypedModelFeature(ModelFeature(_, _, _, _, Identity), extractType)) =>
      Task.now(mdf -> AddIdentityFeature(extractType))

    case (mdf, typed@TypedModelFeature(ModelFeature(_, _, _, _, top: Top), extractType)) =>
      Task.apply(mdf -> AddTopFeature(extractType, transformers.top.transform(typed)))

    case (mdf, typed@TypedModelFeature(ModelFeature(_, _, _, _, index: Index), extractType)) =>
      Task.apply(mdf -> AddIndexFeature(extractType, transformers.index.transform(typed)))

    case (mdf, typed@TypedModelFeature(ModelFeature(_, _, _, _, bins: Bins), extractType)) =>
      Task.apply(mdf -> AddBinsFeature(extractType, transformers.bins.transform(typed)))
  }

}
