package com.collective.modelmatrix.cli.definition

import java.nio.file.Path
import java.time.Instant

import com.collective.modelmatrix.ModelConfigurationParser
import com.collective.modelmatrix.ModelMatrix.ModelMatrixCatalogAccess
import com.collective.modelmatrix.cli.Script
import com.typesafe.config.{ConfigFactory, ConfigResolveOptions}
import org.slf4j.LoggerFactory

import scalaz._

case class AddDefinition(
  config: Path,
  configPath: String,
  name: Option[String],
  comment: Option[String]
) extends Script with ModelMatrixCatalogAccess {

  private val log = LoggerFactory.getLogger(classOf[AddDefinition])

  private implicit val ec = Tag.unwrap(catalogExecutionContext)

  private val parser = new ModelConfigurationParser(
    ConfigFactory.parseFile(config.toFile).resolve(ConfigResolveOptions.defaults()),
    configPath
  )

  import com.collective.modelmatrix.cli.ASCIITableFormat._
  import com.collective.modelmatrix.cli.ASCIITableFormats._

  def run(): Unit = {
    log.info(s"Add Model Matrix definition. " +
      s"Config: $configPath @ $config. " +
      s"Name: $name. " +
      s"Comment: $comment")

    val features = parser.features()

    val errors = features collect { case (f, Failure(e)) => (f, e) }
    val success = features collect { case (_, Success(feature)) => feature }

    if (errors.nonEmpty) {
      Console.out.println(s"Incorrect configured model features: ${errors.size}")
      errors.printASCIITable()
    }

    if (success.nonEmpty && errors.isEmpty) {
      val addModelDefinition = modelDefinitions.add(
        name = name,
        source = parser.content,
        createdBy = System.getProperty("user.name"),
        createdAt = Instant.now(),
        comment = comment
      )

      val insert = for {
        id <- addModelDefinition
        featureId <- modelDefinitionFeatures.addFeatures(id, success: _*)
      } yield (id, featureId)

      import driver.api._
      val (modelDefinitionId, _) = blockOn(db.run(insert.transactionally))

      Console.out.println(s"Successfully created new model definition")
      Console.out.println(s"Matrix Model definition id: $modelDefinitionId")
    }
  }

}
