package com.linkedin.gdmix.data

import org.apache.commons.cli.{BasicParser, CommandLine, CommandLineParser, Options}
import org.apache.spark.sql.{DataFrame, SparkSession}
import org.apache.spark.sql.functions.col

import com.linkedin.gdmix.utils.Constants.{AVRO, FLOAT, LONG}
import com.linkedin.gdmix.utils.IoUtils

/**
 * Update offset for fixed-effect data once random-effects are done. Only used in multiple coordinate descent iterations.
 */
object OffsetUpdater {

  def main(args: Array[String]): Unit = {

    // Define options.
    val options = new Options()
    options.addOption("trainInputDataPath", true, "training input dataset path")
    options.addOption("trainInputScorePath", true, "training input score path")
    options.addOption("trainPerCoordinateScorePath", true, "path to the per-coordinate training score of the previous iteration")
    options.addOption("trainOutputDataPath", true, "output partition data path for training")
    options.addOption("validInputDataPath", true, "validation input dataset path")
    options.addOption("validInputScorePath", true, "validation input score path")
    options.addOption("validPerCoordinateScorePath", true, "path to the per-coordinate validation score of the previous iteration")
    options.addOption("validOutputDataPath", true, "output partition data path for validation")
    options.addOption("predictionScore", true, "column name of prediction score")
    options.addOption("predictionScorePerCoordinate", true, "column name of prediction score per-coordinate")
    options.addOption("offset", true, "column name of offset")
    options.addOption("uid", true, "column name of unique id")
    options.addOption("inputMetadataFile", true, "input metadata file")
    options.addOption("outputMetadataFile", true, "output metadata file")
    options.addOption("dataFormat", true, "either avro or tfrecord")

    // Get the parser.
    val parser: CommandLineParser = new BasicParser()
    val cmd: CommandLine = parser.parse(options, args)

    // Parse the commandline option.
    val trainInputDataPath = cmd.getOptionValue("trainInputDataPath")
    val trainInputScorePath = cmd.getOptionValue("trainInputScorePath")
    val trainPerCoordinateScorePath = cmd.getOptionValue("trainPerCoordinateScorePath")
    val trainOutputDataPath = cmd.getOptionValue("trainOutputDataPath")
    val validInputDataPath = cmd.getOptionValue("validInputDataPath")
    val validInputScorePath = cmd.getOptionValue("validInputScorePath")
    val validPerCoordinateScorePath = cmd.getOptionValue("validPerCoordinateScorePath")
    val validOutputDataPath = cmd.getOptionValue("validOutputDataPath")
    val predictionScore = cmd.getOptionValue("predictionScore", "predictionScore")
    val predictionScorePerCoordinate = cmd.getOptionValue("predictionScorePerCoordinate", "predictionScorePerCoordinate")
    val offset = cmd.getOptionValue("offset", "offset")
    val uid = cmd.getOptionValue("uid", "uid")
    val inputMetadataFile = cmd.getOptionValue("inputMetadataFile")
    val outputMetadataFile = cmd.getOptionValue("outputMetadataFile")
    val dataFormat = cmd.getOptionValue("dataFormat", AVRO)

    // Sanity check.
    require(
      trainInputDataPath != null
        && trainInputScorePath != null
        && trainPerCoordinateScorePath != null
        && trainOutputDataPath != null,
      "Incorrect number of input parameters")

    // Create a Spark session.
    val spark = SparkSession.builder().appName(getClass.getName).getOrCreate()

    // Update offset in training data.
    val trainInputData = IoUtils.readDataFrame(spark, trainInputDataPath, dataFormat)
    // Scores in AVRO format
    val trainInputScore = IoUtils.readDataFrame(spark, trainInputScorePath, AVRO)
    // Scores in AVRO format
    val trainPerCoordinateScore = IoUtils.readDataFrame(spark, trainPerCoordinateScorePath, AVRO)
    val trainOutputData = updateOffset(
      trainInputData,
      trainInputScore,
      Some(trainPerCoordinateScore),
      predictionScore,
      predictionScorePerCoordinate,
      offset,
      uid)
    IoUtils.saveDataFrame(trainOutputData, trainOutputDataPath, dataFormat)

    // Update offset in validation data.
    if (validInputDataPath != null
      && validInputScorePath != null
      && validPerCoordinateScorePath != null
      && validOutputDataPath != null) {
      val validInputData = IoUtils.readDataFrame(spark, validInputDataPath, dataFormat)
      // scores in AVRO format
      val validInputScore = IoUtils.readDataFrame(spark, validInputScorePath, AVRO)
      // scores in AVRO format
      val validPerCoordinateScore = IoUtils.readDataFrame(spark, validPerCoordinateScorePath, AVRO)
      val validOutputData = updateOffset(
        validInputData,
        validInputScore,
        Some(validPerCoordinateScore),
        predictionScore,
        predictionScorePerCoordinate,
        offset,
        uid)
      IoUtils.saveDataFrame(validOutputData, validOutputDataPath, dataFormat)
    }

    MetadataGenerator.addColumnsToMetadata(
      trainOutputData.schema,
      inputMetadataFile,
      outputMetadataFile,
      dataFormat)

    // Terminate Spark session
    spark.stop()
  }

  /**
   * Update "offset" = "offset of last coordinate" - "offset of previous iteration for the same coordinate".
   *
   * @param data Data frame to train or validate
   * @param dFLastCoordinateOffset Data frame contains the offset of last coordinate
   * @param dFPerCoordinateScoreOpt Optional data frame contains per-coordinate score of last iteration
   * @param predictionScore Column name of prediction score
   * @param predictionScorePerCoordinate Column name of prediction score per coordinate
   * @param offset Column name of offset
   * @param uid Column name of uid
   * @return The data frame with offset updated.
   */
  def updateOffset(
    data: DataFrame,
    dFLastCoordinateOffset: DataFrame,
    dFPerCoordinateScoreOpt: Option[DataFrame],
    predictionScore: String,
    predictionScorePerCoordinate: String,
    offset: String,
    uid: String): DataFrame = {

    val lastCoordinateOffset = dFLastCoordinateOffset
      .select(col(uid), col(predictionScore) as offset)
      .withColumn(offset, col(offset).cast(FLOAT))
      .withColumn(uid, col(uid).cast(LONG))

    val offsetUpdated = dFPerCoordinateScoreOpt match {
      case Some(dFPerCoordinateScore) => {
        val perCoordinateScore = dFPerCoordinateScore.select(col(uid), col(predictionScorePerCoordinate))
        lastCoordinateOffset
          .join(perCoordinateScore, uid)
          .withColumn(offset, col(offset) - col(predictionScorePerCoordinate))
          .drop(predictionScorePerCoordinate)
      }

      case None =>
        lastCoordinateOffset
    }
    data.drop(offset).join(offsetUpdated, uid)
  }
}